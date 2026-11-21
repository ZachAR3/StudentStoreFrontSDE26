const fs = require('fs')
const path = require('path')
require('dotenv').config({ path: path.resolve(__dirname, '../.env') })
const { Client, LocalAuth } = require('whatsapp-web.js')
const qrcode = require('qrcode-terminal')
const { cleanupTempImages, checkImageMagick } = require('./services/imagePreprocessor')
const { uploadImage } = require('./services/claudinary')
const springServices = require('./services/springServices')
const { confirmWhatsAppLogin } = springServices
const ListingSubmissionService = require('./services/ListingSubmissionService')
const BotStateStore = require('./services/BotStateStore')
const WhatsAppShoppingChat = require('./shopping/WhatsAppShoppingChat')
const { looksLikePriceReply } = require('./services/priceReply')
const { MessageParser, ContextClassifier, getActiveProviderConfig } = require('./services/llm/modelRouter')
const langfuse = require('./services/langfuseService')
const { startWebhookServer } = require('./services/webhookService')
const { isDirectMessage, getContactNumber, getDirectChatId, normalizePhoneNumber } = require('./services/chatIdentity')
const PersistentSet = require('./services/persistentSet')
const { traceUserId } = require('./services/privacy')

// No production fallbacks: a dev instance started without .env must not read
// the real group or send people to the live site (WA-09).
function requireEnv(name) {
    const value = String(process.env[name] || '').trim()
    if (!value) {
        console.error(`Missing required environment variable ${name}. See bot/.env.example.`)
        process.exit(1)
    }
    return value
}
const TARGET_GROUP = requireEnv('TARGET_GROUP_JID')
const APP_BASE_URL = requireEnv('APP_BASE_URL').replace(/\/+$/, '')
const REGISTER_URL = `${APP_BASE_URL}/#view=register`

console.log(`Starting WhatsApp bot process ${process.pid}`)
process.on('unhandledRejection', error => {
    console.error('Unhandled rejection:', error)
})
process.on('uncaughtException', error => {
    console.error('Uncaught exception:', error)
})
process.on('exit', code => {
    console.log(`WhatsApp bot process exiting with code ${code}`)
})

const consentedUsers = new Set()

cleanupTempImages()
checkImageMagick()

const CONSENTED_USERS_FILE = path.join(__dirname, '../consentedUsersPersistence.json')
const USER_STATE_FILE = path.join(__dirname, '../userStatePersistence.json')
// People who answered NO are remembered across restarts (WA-02).
const declinedUsers = new PersistentSet(path.join(__dirname, '../declinedUsersPersistence.json'))
// Recently handled message IDs survive restarts, so WhatsApp's replay of
// recent messages on reconnect isn't processed twice (WA-05).
const processedMessageIds = new PersistentSet(path.join(__dirname, '../processedMessageIds.json'), {
    maxSize: 2000,
    saveDelayMs: 5000
})

try {
    JSON.parse(fs.readFileSync(CONSENTED_USERS_FILE, 'utf8'))
        .forEach(number => consentedUsers.add(number))
} catch (error) {
    console.warn(`Could not restore consented users from ${CONSENTED_USERS_FILE}:`, error.message)
}

const LISTING_EXPIRY_HOURS = 24
const LISTING_INACTIVITY_MS = Number(process.env.LISTING_INACTIVITY_MS || 300000)

const stateStore = new BotStateStore({
    filePath: USER_STATE_FILE,
    listingExpiryHours: LISTING_EXPIRY_HOURS
})

function persistConsentedUsers() {
    try {
        fs.writeFileSync(CONSENTED_USERS_FILE, JSON.stringify([...consentedUsers]))
    } catch (error) {
        console.error('Error saving consented users:', error)
    }
}

const puppeteerOptions = {
    args: [
        '--no-sandbox',
        '--disable-setuid-sandbox',
        '--disable-dev-shm-usage',
        '--disable-gpu'
    ]
}

if (process.env.WA_CHROME_EXECUTABLE_PATH) {
    puppeteerOptions.executablePath = process.env.WA_CHROME_EXECUTABLE_PATH
}

const client = new Client({
    authStrategy: new LocalAuth(),
    puppeteer: puppeteerOptions
})
const shoppingChat = new WhatsAppShoppingChat(client, { 
    postService: springServices,
    stateStore: stateStore 
})
const listingSubmissionService = new ListingSubmissionService({
    client,
    stateStore,
    consentedUsers,
    persistConsentedUsers,
    uploadImage,
    createPost: springServices.createPost,
    getSellerByPhone: springServices.getSellerByPhone,
    resolveMediaUrlsByHash: springServices.resolveMediaUrlsByHash,
    messageParser: MessageParser,
    contextClassifier: ContextClassifier,
    langfuse,
    appBaseUrl: APP_BASE_URL
})

client.once('ready', () => {
    const active = getActiveProviderConfig()
    console.log(`Bot is ready! LLM primary provider: ${active.primary}${active.fallbacks.length ? ` | fallbacks: ${active.fallbacks.join(', ')}` : ''}`)
    rescheduleRestoredDrafts()
})
client.on('qr', qr => qrcode.generate(qr, { small: true }))
client.on('message_reaction', reaction => {
    shoppingChat.handleReaction(reaction).catch(error => console.error('Shopping reaction handler failed:', error))
})

function rememberProcessedMessage(messageId) {
    if (messageId) processedMessageIds.add(messageId)
}

// In the group, only slash commands (/shop, /search desk) are for the bot.
// Bare "2", "yes" or "n" are usually people talking to each other (BOT-07).
function isGroupBotCommand(rawBody) {
    const body = (rawBody || '').trim()
    return body.startsWith('/') && shoppingChat.isKnownCommandText(body)
}

const YES_REPLY = /^(yes|y|yeah|yep|sure|ok|okay|ja|jo)[.!]*$/i
const NO_REPLY = /^(no|n|nope|nah|nein)[.!]*$/i

// Cheap local check, run before anything is stored or sent anywhere, for
// whether a group message from someone who hasn't consented might be a sale.
const LISTING_HINT = /(€|\beur\b|\beuro|\bsell|\bselling|for sale|\bprice|\bverkauf|\bzu verkaufen|\d+\s*(€|eur|euro))/i
function looksLikeListingCandidate(msg) {
    if (msg.hasMedia && (!msg.type || msg.type === 'image')) return true
    return LISTING_HINT.test(String(msg.body || ''))
}

const CONSENT_PROMPT =
    'Hello! I am the Student-Store Front bot. It looks like you may be selling something in the group.\n' +
    'May I turn your group posts into marketplace listings? To do that I read the text and photos and send them to an AI service that drafts the listing.\n' +
    'Reply YES or NO. Until you answer, I don\'t store or send anything you wrote.'

const MAX_LISTING_IMAGES = 5
const LOGIN_CONFIRMATION_TTL_MS = 2 * 60 * 1000
const pendingLoginConfirmations = new Map()

async function downloadListingImage(msg) {
    if (!msg.hasMedia) return null
    // Only photos become listing images; videos, documents, voice notes and
    // stickers would fail the upload and keep the draft retrying forever.
    if (msg.type && msg.type !== 'image') return null
    let picture
    try {
        picture = await msg.downloadMedia()
    } catch (error) {
        console.warn('Listing media download failed:', error.message)
        return null
    }
    if (!picture?.data || !/^image\//i.test(picture.mimetype || '')) return null
    return {
        data: picture.data,
        mimetype: picture.mimetype
    }
}

async function collectListingInput(phoneNumber, msg, replyChatId = null) {
    const existingImages = stateStore.get(phoneNumber)?.listing?.imageUrls?.length || 0
    if (msg.hasMedia && existingImages >= MAX_LISTING_IMAGES) {
        if (replyChatId) {
            await client.sendMessage(replyChatId, `A listing can have at most ${MAX_LISTING_IMAGES} photos, so I skipped that one.`)
        }
    } else {
        const image = await downloadListingImage(msg)
        if (image) stateStore.appendListingImage(phoneNumber, image)
    }
    if (msg.body !== '') stateStore.appendListingText(phoneNumber, msg.body)
    stateStore.save()
}

function scheduleDirectPostProcessing(contact, phoneNumber, replyChatId) {
    stateStore.setTimer(
        phoneNumber,
        setTimeout(async () => {
            try {
                const state = stateStore.get(phoneNumber)
                if (!state || state.mode !== 'dm-post') return
                if (state.listing.messages.length === 0 && state.listing.imageUrls.length === 0) return

                console.log('DM post inactivity window elapsed — processing explicit post request')
                await listingSubmissionService.submitListingDraft({
                    contact,
                    phoneNumber,
                    replyChatId
                })
            } catch (error) {
                console.error('DM post processing failed:', error)
            }
        }, LISTING_INACTIVITY_MS)
    )
}

/**
 * Classifies a consented user's group draft and posts it if it's a listing.
 * Only called for people in consentedUsers (WA-02).
 */
async function classifyAndSubmitGroupDraft(contact, phoneNumber, replyChatId) {
    const state = stateStore.get(phoneNumber)
    if (!state) return
    if (state.listing.messages.length === 0 && state.listing.imageUrls.length === 0) return

    console.log('Inactivity window elapsed — running LLM classification')
    const classificationTrace = langfuse.trace({
        name: 'ClassificationTimer',
        sessionId: traceUserId(phoneNumber),
        userId: traceUserId(phoneNumber)
    })

    const modelDecision = state.listing.messages.length === 0
        ? 'YES'
        : await ContextClassifier(state.listing.messages, {
            sessionId: traceUserId(phoneNumber),
            userId: traceUserId(phoneNumber),
            parent: classificationTrace
        })

    classificationTrace.update({ output: modelDecision })
    await langfuse.flush()

    if (modelDecision === 'ERROR') {
        // Provider outage: keep the draft and try again later
        // instead of silently discarding a real listing.
        console.warn('LLM classifier unavailable — keeping draft and retrying later')
        scheduleGroupClassification(contact, phoneNumber, replyChatId)
        return
    }

    if (modelDecision !== 'YES') {
        stateStore.clear(phoneNumber)
        console.log('LLM: not a listing — discarding')
        return
    }

    stateStore.markListing(phoneNumber, { isListing: true })
    if (!replyChatId) {
        console.warn('Could not resolve a DM chat for the listing reply.')
        return
    }
    await listingSubmissionService.submitListingDraft({
        contact,
        phoneNumber,
        replyChatId
    })
}

function scheduleGroupClassification(contact, phoneNumber, replyChatId) {
    stateStore.setTimer(
        phoneNumber,
        setTimeout(async () => {
            try {
                await classifyAndSubmitGroupDraft(contact, phoneNumber, replyChatId)
            } catch (error) {
                console.error('Group classification/post processing failed:', error)
            }
        }, LISTING_INACTIVITY_MS)
    )
}

/**
 * Someone who hasn't answered the consent question posted what may be a
 * listing. Keep only the message ID and ask once; the text and photos are
 * fetched from WhatsApp only after a YES (WA-02).
 */
async function rememberUnconsentedGroupMessage(msg, phoneNumber, replyChatId) {
    if (!looksLikeListingCandidate(msg)) return
    const messageId = msg?.id?._serialized
    if (!messageId || !replyChatId) return
    stateStore.appendPendingMessageId(phoneNumber, messageId)
    const state = stateStore.get(phoneNumber)
    if (!state.consentPending) {
        await client.sendMessage(replyChatId, CONSENT_PROMPT)
        stateStore.setConsentPending(phoneNumber, true)
    }
    stateStore.save()
}

/** After consent, pull the referenced group messages into the draft. */
async function loadPendingGroupMessages(phoneNumber) {
    const ids = [...(stateStore.get(phoneNumber)?.listing?.pendingMessageIds || [])]
    let loaded = 0
    for (const id of ids) {
        try {
            const original = await client.getMessageById(id)
            if (!original) continue
            await collectListingInput(phoneNumber, original)
            loaded += 1
        } catch (error) {
            console.warn('Could not reload a group message after consent:', error.message)
        }
    }
    const state = stateStore.get(phoneNumber)
    if (state) {
        state.listing.pendingMessageIds = []
        stateStore.save()
    }
    return loaded
}

/**
 * Timers don't survive a restart; drafts restored from disk get theirs back
 * so they aren't left waiting until they expire (WA-05).
 */
function rescheduleRestoredDrafts() {
    let rescheduled = 0
    stateStore.userState.forEach((state, phoneNumber) => {
        if (state.timer || state.consentPending || state.registrationPending) return
        if (state.mode && state.mode !== 'dm-post') return
        const listing = state.listing || {}
        if (!listing.messages?.length && !listing.imageUrls?.length) return
        const contact = { number: phoneNumber }
        const chatId = `${phoneNumber}@c.us`
        if (state.mode === 'dm-post') scheduleDirectPostProcessing(contact, phoneNumber, chatId)
        else if (consentedUsers.has(phoneNumber)) scheduleGroupClassification(contact, phoneNumber, chatId)
        else return
        rescheduled += 1
    })
    if (rescheduled > 0) console.log(`Rescheduled ${rescheduled} restored draft(s)`)
}

async function handleTargetGroupMessage(msg, contact, phoneNumber) {
    if (isGroupBotCommand(msg.body)) {
        console.log('Slash command detected in target group — skipping LLM classification.')
        await shoppingChat.handleMessage(msg, contact)
        return
    }

    if (declinedUsers.has(phoneNumber)) return

    const replyChatId = getDirectChatId(contact)
    if (!consentedUsers.has(phoneNumber)) {
        await rememberUnconsentedGroupMessage(msg, phoneNumber, replyChatId)
        return
    }

    stateStore.ensure(phoneNumber)
    await collectListingInput(phoneNumber, msg)
    scheduleGroupClassification(contact, phoneNumber, replyChatId)
}

async function handleLoginCommand(msg, contact, normalizedPhoneNumber) {
    const body = String(msg.body || '').trim()
    const senderPhone = String(contact?.number || normalizedPhoneNumber || '').replace(/\D/g, '')

    // The whole message must be the login code; a code buried in other text
    // is what a crafted wa.me link would send on someone's behalf.
    const loginMatch = body.match(/^login:([0-9a-f-]{36})$/i)
    if (loginMatch) {
        if (!senderPhone) {
            await client.sendMessage(msg.from, 'Could not read your phone number from WhatsApp. Please try again.')
            return true
        }
        pendingLoginConfirmations.set(senderPhone, {
            token: loginMatch[1],
            expiresAt: Date.now() + LOGIN_CONFIRMATION_TTL_MS
        })
        await client.sendMessage(
            msg.from,
            'A browser is asking to log in to your Student-Store Front account with this WhatsApp number.\n' +
            'If you just scanned the login QR code yourself, reply YES within 2 minutes.\n' +
            'If you did not, ignore this message and nobody will be logged in.'
        )
        return true
    }

    if (!YES_REPLY.test(body) || !senderPhone) return false
    const pending = pendingLoginConfirmations.get(senderPhone)
    if (!pending) return false
    pendingLoginConfirmations.delete(senderPhone)
    if (pending.expiresAt < Date.now()) {
        await client.sendMessage(msg.from, 'That login request has expired. Please scan a new QR code.')
        return true
    }

    const result = await confirmWhatsAppLogin(pending.token, senderPhone)
    if (result === 'OK') {
        await client.sendMessage(msg.from, 'Login successful! Go back to your browser.')
    } else if (result === 'EXPIRED') {
        await client.sendMessage(msg.from, 'This login link has expired. Please request a new QR code.')
    } else if (result === 'PHONE_NOT_LINKED') {
        await client.sendMessage(msg.from, `This number is not registered. Please sign up at ${REGISTER_URL}`)
    } else if (result === 'ALREADY_USED') {
        await client.sendMessage(msg.from, 'This login link was already used.')
    } else {
        await client.sendMessage(msg.from, 'Login failed due to a server error. Please try again.')
    }

    return true
}

async function handleDmPostFlow(msg, contact, phoneNumber) {
    const response = msg.body.toLowerCase().trim()
    const state = stateStore.get(phoneNumber)

    if (response === 'post' || response === '/post') {
        stateStore.resetDraft(phoneNumber, 'dm-post')
        await client.sendMessage(
            msg.from,
            'Send the posting details here as messages and photos. Include the item name, price, category, and a short description. ' +
            'When you stop sending messages for a moment, I will turn it into a marketplace post. Send "stop" to cancel.'
        )
        return true
    }

    if (['stop', '/stop', 'cancel', '/cancel'].includes(response) && ['dm-post', 'awaiting-price'].includes(state?.mode)) {
        stateStore.clear(phoneNumber)
        await client.sendMessage(msg.from, 'Post draft cancelled. Send "post" whenever you want to start again.')
        return true
    }

    if (state?.mode === 'awaiting-price') {
        if (!looksLikePriceReply(msg.body) && shoppingChat.isKnownCommandText(msg.body)) return false

        await collectListingInput(phoneNumber, msg, msg.from)
        await listingSubmissionService.submitListingDraft({
            contact,
            phoneNumber,
            replyChatId: msg.from,
            missingPriceMessage: 'I still could not find a valid price in your reply. Please send a clear numeric price only (for example: "15"). Your draft will expire after 24 hours.',
            stageOnMissingPrice: true,
            clearStateOnFailure: false
        })
        return true
    }

    if (state?.mode !== 'dm-post') return false
    if (!looksLikePriceReply(msg.body) && shoppingChat.isKnownCommandText(msg.body)) return false

    await collectListingInput(phoneNumber, msg, msg.from)
    scheduleDirectPostProcessing(contact, phoneNumber, msg.from)
    return true
}

async function handleConsentResponse(msg, contact, phoneNumber) {
    const response = String(msg.body || '').trim()
    const state = stateStore.get(phoneNumber)
    if (!state?.consentPending) return false

    if (YES_REPLY.test(response)) {
        console.log('User consented — loading their group messages')
        consentedUsers.add(phoneNumber)
        declinedUsers.delete(phoneNumber)
        persistConsentedUsers()
        stateStore.setConsentPending(phoneNumber, false)
        stateStore.save()

        const reloaded = await loadPendingGroupMessages(phoneNumber)
        const draft = stateStore.get(phoneNumber)?.listing
        if (!reloaded && !draft?.messages?.length && !draft?.imageUrls?.length) {
            stateStore.clear(phoneNumber)
            await client.sendMessage(msg.from, 'Thanks! I could not read your earlier group messages any more. Send "post" here to create a listing, or post in the group again.')
            return true
        }
        await client.sendMessage(msg.from, 'Thanks! I am turning your group post into a marketplace listing now.')
        await classifyAndSubmitGroupDraft({ ...contact, number: contact?.number || phoneNumber }, phoneNumber, msg.from)
        return true
    }

    if (NO_REPLY.test(response)) {
        declinedUsers.add(phoneNumber)
        stateStore.clear(phoneNumber)
        console.log('User declined consent')
        await client.sendMessage(msg.from, 'Okay, I will not use your group messages. If you change your mind, send "post" here to create a listing yourself.')
        return true
    }

    // Let commands through ("shop", "post", ...); nudge on anything else.
    if (response.toLowerCase() === 'post' || shoppingChat.isKnownCommandText(response)) return false
    await client.sendMessage(msg.from, 'Please reply YES to turn your group post into a marketplace listing, or NO to leave it alone.')
    return true
}

const HELP_HINT =
    'Hi! I am the Student-Store Front bot. Send "post" to sell something, "shop" to see recent listings, ' +
    '"search desk" to search, or "help" for all commands.'

async function handleDirectMessage(msg, contact, phoneNumber) {
    if (msg.fromMe) return

    if (await handleLoginCommand(msg, contact, phoneNumber)) return
    if (await handleConsentResponse(msg, contact, phoneNumber)) return
    if (await handleDmPostFlow(msg, contact, phoneNumber)) return
    if (shoppingChat.isKnownCommandText(msg.body) && stateStore.get(phoneNumber)?.mode === 'dm-post') {
        stateStore.clear(phoneNumber)
    }
    if (await shoppingChat.handleMessage(msg, contact)) return
    // Nothing matched: point people at what the bot can do instead of
    // leaving them without an answer (BOT-06).
    if (String(msg.body || '').trim() || msg.hasMedia) {
        await client.sendMessage(msg.from, HELP_HINT)
    }
}

async function handleIncomingMessage(msg) {
    const messageId = msg?.id?._serialized || msg?.id?.id || null
    if (messageId && processedMessageIds.has(messageId)) return
    rememberProcessedMessage(messageId)

    console.log('author:', msg.author, '| from:', msg.from)

    const contact = await msg.getContact()
    const phoneNumber = normalizePhoneNumber(getContactNumber(contact, msg.from))
    const directMessage = isDirectMessage(msg)

    if (directMessage) {
        if (!phoneNumber) {
            // Privacy-addressed (@lid) contacts may not expose a phone number;
            // a LID is not a phone number, so don't guess (WA-04).
            if (!msg.fromMe) {
                await client.sendMessage(msg.from, 'I could not read your phone number from WhatsApp, so I cannot link this chat to an account. Please make sure your number is visible to contacts and try again.')
            }
            return
        }
        await handleDirectMessage(msg, contact, phoneNumber)
        return
    }

    if (msg.fromMe || msg.from !== TARGET_GROUP) return
    if (!phoneNumber) {
        console.warn('Skipping target-group message because WhatsApp did not expose a contact number.')
        return
    }

    await handleTargetGroupMessage(msg, contact, phoneNumber)
}

async function handleIncomingMessageSafely(msg) {
    try {
        await handleIncomingMessage(msg)
    } catch (error) {
        console.error('Message handling failed:', error)
        // Tell DM senders something went wrong rather than going quiet (BOT-06).
        if (msg && !msg.fromMe && isDirectMessage(msg)) {
            await client.sendMessage(msg.from, 'Sorry, something went wrong on my side. Please try again in a moment.').catch(() => {})
        }
    }
}

client.on('message', handleIncomingMessageSafely)

client.on('message_create', async msg => {
    if (!msg?.fromMe) return
    await handleIncomingMessageSafely(msg)
})

// Drafts expire 24h after their last message; sweep often enough that this
// means 24h, not up to 27h (BOT-10).
setInterval(() => {
    stateStore.cleanupExpiredStates()
}, 15 * 60 * 1000)

setInterval(() => {
    const now = Date.now()
    pendingLoginConfirmations.forEach((pending, phone) => {
        if (pending.expiresAt < now) pendingLoginConfirmations.delete(phone)
    })
}, LOGIN_CONFIRMATION_TTL_MS)

client.initialize()
startWebhookServer(stateStore, listingSubmissionService, client, {
    consentedUsers,
    persistConsentedUsers
})
