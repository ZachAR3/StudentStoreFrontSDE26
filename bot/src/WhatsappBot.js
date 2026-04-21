const fs                                          = require('fs')
const { Client, LocalAuth }                        = require('whatsapp-web.js')
const qrcode                                       = require('qrcode-terminal')
const { uploadImage }                              = require('./services/claudinary.js')
const { createPost, getSellerByPhone, confirmWhatsAppLogin } = require('./services/springServices.js')
const { GeminiMessageParser, GeminiContextClassifier } = require('./services/botGeminiService.js')

// ─── Persistent state ────────────────────────────────────────────────────────
const NOT_CONSENTED_TO_MESSAGE_UPLOAD     = new Set()
const consentedUsers                      = new Set()
const userState                       = new Map()

// Persistence mechanism that loads consented users from disk (consentedUsersPersistence.JSON file)
try {
    JSON.parse(fs.readFileSync('consentedUsersPersistence.json', 'utf8'))
    .forEach(number => consentedUsers.add(number))
}
catch (error) {
    console.error('Error loading consented users:', error)
}

// ─── Config ───────────────────────────────────────────────────────────────────
const TARGET_GROUP = '120363406751456779@g.us'
const LISTING_EXPIRY_HOURS = 12
// const TEST_USER = '40723136087' FOR TESTING ONLY


// ─── Helper function ───────────────────────────────────────────────────────────────────

async function processListing(contact, currentUserListing) {
    
// Cloudinary upload 
    const uploadResults = await Promise.all(
        currentUserListing.imageUrls.map(image => {
            const base64 = `data:${image.mimetype};base64,${image.data}`
            return uploadImage(base64)
        })
    )
    const cloudinaryUrls = uploadResults.filter(url => url !== null)

    // const parsedListing = await GeminiMessageParser(currentUserListing.messages.join('\n')) -> To be uncommented when API works
    const parsedListing = { title: 'Test', price: 100, description: 'Test description long enough', category: 'ELECTRONICS' }

    const seller = await getSellerByPhone(contact.number)
    if (!seller) return null

    const result = await createPost(parsedListing, cloudinaryUrls, seller.sellerId)
    if (!result) return false
    return true
}


// ─── WhatsApp client ──────────────────────────────────────────────────────────
const client = new Client({ authStrategy: new LocalAuth() })

client.once('ready', () => console.log('Bot is ready!'))
client.on('qr', qr => qrcode.generate(qr, { small: true }))

// ─── Message handler ────────────────────────────────────────────────────────
client.on('message', async msg => {
    console.log('author:', msg.author, '| from:', msg.from)

    const contact = await msg.getContact()

    if (!userState.has(contact.number)) {
        userState.set(contact.number, {
            listing: {
                imageUrls: [],
                messages: [],
                createdAt: Date.now(),
                isListing: false
            },
            consentPending: false,
            registrationPending: false,
            timer: null
        })
    }

    // ── Group messages ────────────────────────────────────────────────────────
    if (msg.from.endsWith('@g.us') && msg.from === TARGET_GROUP) {

        // Reset the 5-minute classification timer on every new message
        const classificationTimer = setTimeout(async () => {
            console.log('5 min passed — sending to Gemini for classification')

            // const geminiResponse = await GeminiContextClassifier(userState.get(contact.number).listing.messages)
            const geminiResponse = 'YES'   // MOCK — remove when API works

            if (geminiResponse === 'YES') {
                if (!userState.has(contact.number)) return
                userState.get(contact.number).listing.isListing = true
                console.log('Gemini: valid listing detected — asking user for consent')

                if (NOT_CONSENTED_TO_MESSAGE_UPLOAD.has(contact.number)) {
                    console.log('User previously declined consent — skipping')
                    return
                }

                if (!consentedUsers.has(contact.number) && !userState.get(contact.number).consentPending) {
                    try {
                        await client.sendMessage(
                            contact.number + '@c.us',
                            'Hello! I am the StudentStoreFront bot. ' +
                            'I noticed you may be selling something. ' +
                            'Do you consent to adding your listing to our marketplace? Reply YES or NO.'
                        )
                        userState.get(contact.number).consentPending = true
                    } catch (error) {
                        console.error('Failed to send consent message:', error)
                    }
                } else if (consentedUsers.has(contact.number)) {
                    const success = await processListing(contact, userState.get(contact.number).listing)

                    try {
                        if (success === true) {
                            await client.sendMessage(contact.number + '@c.us', 'Your listing has been uploaded successfully!')
                        } else if (success === null) {
                            await client.sendMessage(contact.number + '@c.us', 'Could not find your account. Please re-register.')
                        } else {
                            await client.sendMessage(contact.number + '@c.us', 'Something went wrong uploading your listing. Please try again later.')
                        }
                    } catch (error) {
                        console.error('Failed to send post-listing message:', error)
                    }
                    userState.delete(contact.number)
                }
            } else {
                userState.delete(contact.number)
                console.log('Gemini: not a listing — discarding')
            }

        }, 30000)   //TODO:  change back to 300000 (5 min) for production

        clearTimeout(userState.get(contact.number).timer)
        userState.get(contact.number).timer = classificationTimer

        // Collect media
        if (msg.hasMedia) {
            const picture = await msg.downloadMedia()
            userState.get(contact.number).listing.imageUrls.push({
                data     : picture.data,
                mimetype : picture.mimetype
            })
        }

        // Collect text
        if (msg.body !== '') {
            userState.get(contact.number).listing.messages.push(msg.body)
        }

    }

    // ── DM responses (consent flow) ───────────────────────────────────────────
    if (msg.from.endsWith('@c.us') || msg.from.endsWith('@lid')) {
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
                fs.writeFileSync('consentedUsersPersistence.json', JSON.stringify([...consentedUsers]))
            } catch (error) {
                console.error('Error saving consented users:', error)
            }

            try {
                await client.sendMessage(
                    contact.number + '@c.us',
                    'Your listing has been uploaded successfully!'
                )
                userState.delete(contact.number)
            } catch (error) {
                console.error('Failed to send success message:', error)
            }

        } else if (dmResponse === 'no') {
            NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(contact.number)
            userState.delete(contact.number)
            console.log('User declined consent')
        }

        // This is the handle for the registration. Next sprint I'll add a webhook so the bot knows when the registration is done
        else if (dmResponse === 'registered') {
            if (userState.get(contact.number)?.registrationPending) {
                const success = await processListing(contact, userState.get(contact.number).listing)
                if (success) {
                    try {
                        await client.sendMessage(contact.number + '@c.us', 'Your listing has been uploaded successfully!')
                        userState.delete(contact.number)
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

    }
})

// ─── Cleanup: remove stale listings every 3 hours (12-hour expiry) ────────────
setInterval(() => {
    userState.forEach((state, phoneNumber) => {
        if (state.listing.createdAt + LISTING_EXPIRY_HOURS * 60 * 60 * 1000 < Date.now()) {
            userState.delete(phoneNumber)
            console.log('Expired listing removed for:', phoneNumber)
        }
    })
}, 3 * 60 * 60 * 1000)

client.initialize()
