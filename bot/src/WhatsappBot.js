const fs                                          = require('fs')
const { Client, LocalAuth }                        = require('whatsapp-web.js')
const qrcode                                       = require('qrcode-terminal')
const { uploadImage }                              = require('./services/claudinary.js')
const springServices                                = require('./services/springServices.js')
const { createPost, getSellerByPhone, confirmWhatsAppLogin } = springServices
const { MessageParser, ContextClassifier, ImageDescriber, getActiveProviderConfig } = require('./services/llm/modelRouter.js')
const { startWebhookServer }                          = require('./services/webhookService.js')
const WhatsAppShoppingChat                            = require('./shopping/WhatsAppShoppingChat.js')
const langfuse                                        = require('./services/langfuseService')

// ─── Persistent state ────────────────────────────────────────────────────────
const NOT_CONSENTED_TO_MESSAGE_UPLOAD = new Set()
const consentedUsers                  = new Set()
const userState                       = new Map()

const path = require('path')
const CONSENTED_USERS_FILE = path.join(__dirname, '../consentedUsersPersistence.json')
const USER_STATE_FILE      = path.join(__dirname, '../userStatePersistence.json')

// Load consented users from disk
try {
    JSON.parse(fs.readFileSync(CONSENTED_USERS_FILE, 'utf8'))
        .forEach(number => consentedUsers.add(number))
} catch (_) {}

// Load userState from disk — timers are always null on reload
try {
    const saved = JSON.parse(fs.readFileSync(USER_STATE_FILE, 'utf8'))
    Object.entries(saved).forEach(([phone, state]) => {
        userState.set(phone, { ...state, timer: null })
    })
    if (userState.size > 0) console.log(`Restored ${userState.size} user state(s) from disk`)
} catch (_) {}

function saveUserState() {
    const serializable = {}
    userState.forEach((state, phone) => {
        serializable[phone] = {
            listing:             state.listing,
            consentPending:      state.consentPending,
            registrationPending: state.registrationPending
        }
    })
    try {
        fs.writeFileSync(USER_STATE_FILE, JSON.stringify(serializable, null, 2))
    } catch (err) {
        console.error('Error saving userState:', err)
    }
}

// ─── Config ───────────────────────────────────────────────────────────────────
const TARGET_GROUP = process.env.TARGET_GROUP_JID || '120363406751456779@g.us'
const LISTING_EXPIRY_HOURS = 12
const processedMessageIds = new Set()

// ─── Helper function ───────────────────────────────────────────────────────────────────

async function processListing(contact, currentUserListing) {
    const trace = langfuse.trace({
        name: "ProcessListing",
        sessionId: contact.number,
        userId: contact.number
    });

    // Cloudinary upload and image description run in parallel
    const cloudinarySpan = trace.span({ name: "CloudinaryUpload" });
    const [cloudinaryUrls, imageDescription] = await Promise.all([
        Promise.all(
            currentUserListing.imageUrls.map(image => {
                const base64 = `data:${image.mimetype};base64,${image.data}`
                return uploadImage(base64)
            })
        ).then(results => {
            const urls = results.filter(url => url !== null)
            cloudinarySpan.end({ output: { count: urls.length } })
            return urls
        }),
        currentUserListing.imageUrls.length > 0
            ? ImageDescriber(currentUserListing.imageUrls, { sessionId: contact.number, userId: contact.number, parent: trace })
            : Promise.resolve(null)
    ]);

    const messages = [...currentUserListing.messages]
    if (imageDescription) {
        messages.push(`Image description: ${imageDescription}`)
    }

    const parsedListing = await MessageParser(messages.join('\n'), {
        sessionId: contact.number,
        userId: contact.number,
        parent: trace
    })
    
    if (!parsedListing) {
        console.error('Failed to parse listing with configured LLM provider(s)')
        trace.end({ level: "ERROR", statusMessage: "LLM parsing failed" });
        await langfuse.flush()
        return false
    }

    const seller = await getSellerByPhone(contact.number)
    if (!seller) {
        trace.end({ level: "WARNING", statusMessage: "Seller not found" });
        await langfuse.flush()
        return null
    }

    // Spring create post span
    const springSpan = trace.span({ name: "CreatePost" });
    const result = await createPost(parsedListing, cloudinaryUrls, seller.sellerId)
    springSpan.end({ output: result ? "SUCCESS" : "FAILED" });
    
    trace.end({ output: result ? "SUCCESS" : "FAILED" });
    await langfuse.flush()

    if (!result) return false
    return true
}


// ─── WhatsApp client ──────────────────────────────────────────────────────────
const client = new Client({ authStrategy: new LocalAuth() })
const shoppingChat = new WhatsAppShoppingChat(client, { postService: springServices })

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

function ensureUserState(phoneNumber) {
    if (userState.has(phoneNumber)) return userState.get(phoneNumber)

    const state = {
        listing: {
            imageUrls: [],
            messages: [],
            createdAt: Date.now(),
            isListing: false
        },
        consentPending: false,
        registrationPending: false,
        timer: null
    }
    userState.set(phoneNumber, state)
    saveUserState()
    return state
}

async function handleIncomingMessage(msg, source) {
    const messageId = msg?.id?._serialized || msg?.id?.id || null
    if (messageId && processedMessageIds.has(messageId)) {
        return
    }
    rememberProcessedMessage(messageId)

    console.log('author:', msg.author, '| from:', msg.from)

    const contact = await msg.getContact()
    const contactNumber = contact?.number

    // Ignore known bot commands in target group so we do not spend LLM tokens on them.
    if (msg.from === TARGET_GROUP && isKnownBotCommand(msg.body)) {
        console.log('Known command detected in target group — skipping LLM classification.')
        return
    }

    // ── Group messages ────────────────────────────────────────────────────────
    if (!msg.fromMe && msg.from === TARGET_GROUP) {
        if (!contactNumber) {
            console.warn('Skipping target-group message because WhatsApp did not expose a contact number.')
            return
        }

        const stateForContact = ensureUserState(contactNumber)

        // Reset the inactivity timer on every new message
        const classificationTimer = setTimeout(async () => {
            console.log('Inactivity window elapsed — running LLM classification')

            const state = userState.get(contactNumber)
            if (!state) return

            // Trace the classification decision for this user's messages
            const classificationTrace = langfuse.trace({
                name: "ClassificationTimer",
                sessionId: contactNumber,
                userId: contactNumber
            });

            const modelDecision = await ContextClassifier(state.listing.messages, {
                sessionId: contactNumber,
                userId: contactNumber,
                parent: classificationTrace
            });

            classificationTrace.update({ output: modelDecision });
            await langfuse.flush()

            if (modelDecision === 'YES') {
                if (!userState.has(contactNumber)) return
                userState.get(contactNumber).listing.isListing = true
                console.log('LLM: valid listing detected — asking user for consent')

                if (NOT_CONSENTED_TO_MESSAGE_UPLOAD.has(contactNumber)) {
                    console.log('User previously declined consent — skipping')
                    return
                }

                if (!consentedUsers.has(contactNumber) && !userState.get(contactNumber).consentPending) {
                    try {
                        await client.sendMessage(
                            contactNumber + '@c.us',
                            'Hello! I am the StudentStoreFront bot. ' +
                            'I noticed you may be selling something. ' +
                            'Do you consent to adding your listing to our marketplace? Reply YES or NO.'
                        )
                        userState.get(contactNumber).consentPending = true
                        saveUserState()
                    } catch (error) {
                        console.error('Failed to send consent message:', error)
                    }
                } else if (consentedUsers.has(contactNumber)) {
                    const success = await processListing(contact, userState.get(contactNumber).listing)

                    try {
                        if (success === true) {
                            await client.sendMessage(contactNumber + '@c.us', 'Your listing has been uploaded successfully!')
                            userState.delete(contactNumber)
                            saveUserState()
                        } else if (success === null) {
                            const appBaseUrl = process.env.APP_BASE_URL || 'http://localhost:8080'
                            await client.sendMessage(contactNumber + '@c.us',
                                `You need to register first! Visit ${appBaseUrl} and click Sign Up. ` +
                                'Reply "registered" when done and I will upload your listing automatically.')
                            userState.get(contactNumber).registrationPending = true
                            saveUserState()
                        } else {
                            await client.sendMessage(contactNumber + '@c.us', 'Something went wrong uploading your listing. Please try again later.')
                            userState.delete(contactNumber)
                            saveUserState()
                        }
                    } catch (error) {
                        console.error('Failed to send post-listing message:', error)
                    }
                }
            } else {
                userState.delete(contactNumber)
                saveUserState()
                console.log('LLM: not a listing — discarding')
            }

        }, 30000)   //TODO:  change back to 300000 (5 min) for production

        clearTimeout(stateForContact.timer)
        stateForContact.timer = classificationTimer

        // Collect media
        if (msg.hasMedia) {
            const picture = await msg.downloadMedia()
            stateForContact.listing.imageUrls.push({
                data     : picture.data,
                mimetype : picture.mimetype
            })
        }

        // Collect text
        if (msg.body !== '') {
            stateForContact.listing.messages.push(msg.body)
        }

        saveUserState()
    }

    // ── DM responses (consent flow) ───────────────────────────────────────────
    if (msg.from.endsWith('@c.us') || msg.from.endsWith('@lid')) {
        if (msg.fromMe) return
        const dmResponse = msg.body.toLowerCase().trim()

        // ── QR login handler ─────────────────────────────────────────────────
        const loginMatch = msg.body.match(/^login:([0-9a-f-]{36})$/i)
        if (loginMatch) {
            const result = await confirmWhatsAppLogin(loginMatch[1], contact.number)
            if (result === 'OK') {
                await client.sendMessage(contact.number + '@c.us', 'Login successful! Go back to your browser.')
            } else if (result === 'EXPIRED') {
                await client.sendMessage(contact.number + '@c.us', 'This login link has expired. Please request a new QR code.')
            } else if (result === 'PHONE_NOT_LINKED') {
                await client.sendMessage(contact.number + '@c.us', 'This number is not registered. Please sign up at http://localhost:8080.')
            } else if (result === 'ALREADY_USED') {
                await client.sendMessage(contact.number + '@c.us', 'This login link was already used.')
            } else {
                await client.sendMessage(contact.number + '@c.us', 'Login failed due to a server error. Please try again.')
            }
            return
        }

        if (dmResponse === 'yes') {
            if (!userState.get(contact.number)?.consentPending) return
            console.log('User consented — processing listing')

            const success = await processListing(contact, userState.get(contact.number).listing)

            if (success === null) {
                try {
                    const appBaseUrl = process.env.APP_BASE_URL || 'http://localhost:8080'
                    await client.sendMessage(contact.number + '@c.us',
                        `You need to register first! Visit ${appBaseUrl} and click Sign Up. ` +
                        'Reply "registered" when done and I will upload your listing automatically.')
                    userState.get(contact.number).registrationPending = true
                    saveUserState()
                } catch (error) {
                    console.error('Failed to send registration prompt:', error)
                }
                return
            }

            if (success === false) {
                try {
                    await client.sendMessage(contact.number + '@c.us',
                        'Something went wrong uploading your listing. Please try again later.')
                } catch (error) {
                    console.error('Failed to send error message:', error)
                }
                return
            }

            consentedUsers.add(contact.number)
            try {
                fs.writeFileSync(CONSENTED_USERS_FILE, JSON.stringify([...consentedUsers]))
            } catch (error) {
                console.error('Error saving consented users:', error)
            }

            try {
                await client.sendMessage(
                    contact.number + '@c.us',
                    'Your listing has been uploaded successfully!'
                )
                userState.delete(contact.number)
                saveUserState()
            } catch (error) {
                console.error('Failed to send success message:', error)
            }

        } else if (dmResponse === 'no') {
            NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(contact.number)
            userState.delete(contact.number)
            saveUserState()
            console.log('User declined consent')
        }

        // This is the handle for the registration. Next sprint I'll add a webhook so the bot knows when the registration is done
        else if (dmResponse === 'registered') {
            if (userState.get(contact.number)?.registrationPending) {
                const success = await processListing(contact, userState.get(contact.number).listing)
                if (success) {
                    try {
                        await client.sendMessage(contact.number + '@c.us', 'Your listing has been uploaded successfully!')
                        consentedUsers.add(contact.number)
                        fs.writeFileSync(CONSENTED_USERS_FILE, JSON.stringify([...consentedUsers]))
                        userState.delete(contact.number)
                        saveUserState()
                    } catch (error) {
                        console.error('Failed to send success message:', error)
                    }
                } else {
                    try {
                        await client.sendMessage(contact.number + '@c.us', 'Could not find your account. Make sure you registered with this phone number!')
                    } catch (error) {
                        console.error('Failed to send error message:', error)
                    }
                }
            }
        }

        else if (await shoppingChat.handleMessage(msg, contact)) {
            return
        }

    }
}

// ─── Message handlers ───────────────────────────────────────────────────────
client.on('message', async msg => {
    await handleIncomingMessage(msg, 'message')
})

// Some self-sent outbound messages only appear on message_create.
client.on('message_create', async msg => {
    if (!msg?.fromMe) return
    await handleIncomingMessage(msg, 'message_create')
})

// ─── Cleanup: remove stale listings every 3 hours (12-hour expiry) ────────────
setInterval(() => {
    let anyDeleted = false
    userState.forEach((state, phoneNumber) => {
        if (state.listing.createdAt + LISTING_EXPIRY_HOURS * 60 * 60 * 1000 < Date.now()) {
            userState.delete(phoneNumber)
            anyDeleted = true
            console.log('Expired listing removed for:', phoneNumber)
        }
    })
    if (anyDeleted) saveUserState()
}, 3 * 60 * 60 * 1000)

client.initialize()
startWebhookServer(client, userState, processListing, consentedUsers)
