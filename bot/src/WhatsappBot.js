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
const { MessageParser, ContextClassifier, getActiveProviderConfig } = require('./services/llm/modelRouter')
const langfuse = require('./services/langfuseService')
const { startWebhookServer } = require('./services/webhookService')
const { isDirectMessage, getContactNumber, getDirectChatId, normalizePhoneNumber } = require('./services/chatIdentity')

const NOT_CONSENTED_TO_MESSAGE_UPLOAD = new Set()
const consentedUsers = new Set()

cleanupTempImages()
checkImageMagick()

const CONSENTED_USERS_FILE = path.join(__dirname, '../consentedUsersPersistence.json')
const USER_STATE_FILE = path.join(__dirname, '../userStatePersistence.json')

try {
    JSON.parse(fs.readFileSync(CONSENTED_USERS_FILE, 'utf8'))
        .forEach(number => consentedUsers.add(number))
} catch (_) {}

const TARGET_GROUP = process.env.TARGET_GROUP_JID || '120363406751456779@g.us'
const LISTING_EXPIRY_HOURS = 24
const LISTING_INACTIVITY_MS = 30000   //TODO: change back to 300000 (5 min) for production
const processedMessageIds = new Set()

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

const client = new Client({ authStrategy: new LocalAuth() })
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
    resolveMediaUrlsByHash: springServices.resolveMediaUrlsByHash,
    getSellerByPhone: springServices.getSellerByPhone,
    messageParser: MessageParser,
    langfuse,
    appBaseUrl: process.env.APP_BASE_URL || 'http://localhost:8080'
})

client.once('ready', () => {
    const active = getActiveProviderConfig()
    console.log(`Bot is ready! LLM primary provider: ${active.primary}${active.fallbacks.length ? ` | fallbacks: ${active.fallbacks.join(', ')}` : ''}`)
})
client.on('qr', qr => qrcode.generate(qr, { small: true }))
client.on('message_reaction', reaction => {
    shoppingChat.handleReaction(reaction).catch(error => console.error('Shopping reaction handler failed:', error))
})

function rememberProcessedMessage(messageId) {
    if (!messageId) return
    processedMessageIds.add(messageId)
    if (processedMessageIds.size > 2000) {
        const oldestId = processedMessageIds.values().next().value
        processedMessageIds.delete(oldestId)
    }
}

function isKnownBotCommand(rawBody) {
    const body = (rawBody || '').trim()
    if (!body) return false

    if (/^login:[0-9a-f-]{36}$/i.test(body)) return true
    if (/^(yes|no|registered)$/i.test(body)) return true

    return shoppingChat.isKnownCommandText(body)
}

async function downloadListingImage(msg) {
    if (!msg.hasMedia) return null
    const picture = await msg.downloadMedia()
    return {
        data: picture.data,
        mimetype: picture.mimetype
    }
}

async function collectListingInput(phoneNumber, msg) {
    const image = await downloadListingImage(msg)
    if (image) stateStore.appendListingImage(phoneNumber, image)
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

function scheduleGroupClassification(contact, phoneNumber, replyChatId) {
    stateStore.setTimer(
        phoneNumber,
        setTimeout(async () => {
            try {
                const state = stateStore.get(phoneNumber)
                if (!state) return

                console.log('Inactivity window elapsed — running LLM classification')
                const classificationTrace = langfuse.trace({
                    name: 'ClassificationTimer',
                    sessionId: phoneNumber,
                    userId: phoneNumber
                })

                const modelDecision = await ContextClassifier(state.listing.messages, {
                    sessionId: phoneNumber,
                    userId: phoneNumber,
                    parent: classificationTrace
                })

                classificationTrace.update({ output: modelDecision })
                await langfuse.flush()

                if (modelDecision !== 'YES') {
                    stateStore.clear(phoneNumber)
                    console.log('LLM: not a listing — discarding')
                    return
                }

                stateStore.markListing(phoneNumber, { isListing: true })
                console.log('LLM: valid listing detected — asking user for consent')

                if (NOT_CONSENTED_TO_MESSAGE_UPLOAD.has(phoneNumber)) {
                    console.log('User previously declined consent — skipping')
                    return
                }

                if (!consentedUsers.has(phoneNumber) && !stateStore.get(phoneNumber)?.consentPending) {
                    if (!replyChatId) {
                        console.warn('Could not resolve a DM chat for consent flow.')
                        return
                    }

                    await client.sendMessage(
                        replyChatId,
                        'Hello! I am the Student-Store Front bot. ' +
                        'I noticed you may be selling something. ' +
                        'Do you consent to adding your listing to our marketplace? Reply YES or NO.'
                    )
                    stateStore.setConsentPending(phoneNumber, true)
                    stateStore.save()
                    return
                }

                if (replyChatId) {
                    await listingSubmissionService.submitListingDraft({
                        contact,
                        phoneNumber,
                        replyChatId
                    })
                }
            } catch (error) {
                console.error('Group classification/post processing failed:', error)
            }
        }, LISTING_INACTIVITY_MS)
    )
}

async function handleTargetGroupMessage(msg, contact, phoneNumber) {
    if (isKnownBotCommand(msg.body)) {
        console.log('Known command detected in target group — skipping LLM classification.')
        await shoppingChat.handleMessage(msg, contact)
        return
    }

    const replyChatId = getDirectChatId(contact)
    stateStore.ensure(phoneNumber)
    await collectListingInput(phoneNumber, msg)
    scheduleGroupClassification(contact, phoneNumber, replyChatId)
}

async function handleLoginCommand(msg, contact, normalizedPhoneNumber) {
    const body = String(msg.body || '').trim()
    const loginMatch = body.match(/login:([0-9a-f-]{36})/i)
    if (!loginMatch) return false

    const senderPhone = String(contact?.number || normalizedPhoneNumber || '').replace(/\D/g, '')
    if (!senderPhone) {
        await client.sendMessage(msg.from, 'Could not read your phone number from WhatsApp. Please try again.')
        return true
    }

    const result = await confirmWhatsAppLogin(loginMatch[1], senderPhone)
    if (result === 'OK') {
        await client.sendMessage(msg.from, 'Login successful! Go back to your browser.')
    } else if (result === 'EXPIRED') {
        await client.sendMessage(msg.from, 'This login link has expired. Please request a new QR code.')
    } else if (result === 'PHONE_NOT_LINKED') {
        await client.sendMessage(msg.from, 'This number is not registered. Please sign up at http://localhost:8080.')
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
        if (shoppingChat.isKnownCommandText(msg.body)) return false

        await collectListingInput(phoneNumber, msg)
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

    if (state?.mode !== 'dm-post' || response === 'registered') return false
    if (shoppingChat.isKnownCommandText(msg.body)) return false

    await collectListingInput(phoneNumber, msg)
    scheduleDirectPostProcessing(contact, phoneNumber, msg.from)
    return true
}

async function handleConsentResponse(msg, contact, phoneNumber) {
    const response = msg.body.toLowerCase().trim()
    const state = stateStore.get(phoneNumber)
    if (!state) return false

    if (response === 'yes' && state.consentPending) {
        console.log('User consented — processing listing')
        await listingSubmissionService.submitListingDraft({
            contact,
            phoneNumber,
            replyChatId: msg.from,
            markConsentedOnSuccess: true
        })
        return true
    }

    if (response === 'no' && state.consentPending) {
        NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(phoneNumber)
        stateStore.clear(phoneNumber)
        console.log('User declined consent')
        return true
    }

    if (response === 'registered' && state.registrationPending) {
        const registrationRetryMessage = 'Could not find your account. Make sure you registered with this phone number!'
        await listingSubmissionService.submitListingDraft({
            contact,
            phoneNumber,
            replyChatId: msg.from,
            registrationMessage: registrationRetryMessage,
            markConsentedOnSuccess: true
        })
        return true
    }

    return false
}

async function handleDirectMessage(msg, contact, phoneNumber) {
    if (msg.fromMe) return

    if (await handleLoginCommand(msg, contact, phoneNumber)) return
    if (await handleConsentResponse(msg, contact, phoneNumber)) return
    if (await handleDmPostFlow(msg, contact, phoneNumber)) return
    if (shoppingChat.isKnownCommandText(msg.body) && stateStore.get(phoneNumber)?.mode === 'dm-post') {
        stateStore.clear(phoneNumber)
    }
    if (await shoppingChat.handleMessage(msg, contact)) return
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

client.on('message', async msg => {
    await handleIncomingMessage(msg)
})

client.on('message_create', async msg => {
    if (!msg?.fromMe) return
    await handleIncomingMessage(msg)
})

setInterval(() => {
    stateStore.cleanupExpiredStates()
}, 3 * 60 * 60 * 1000)

client.initialize()
startWebhookServer(stateStore, listingSubmissionService, client)
