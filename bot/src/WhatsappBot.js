const fs                                          = require('fs')
const { Client, LocalAuth }                        = require('whatsapp-web.js')
const qrcode                                       = require('qrcode-terminal')
const { uploadImage }                              = require('./services/claudinary.js')
const { createPost }                               = require('./services/springServices.js')
const { GeminiMessageParser, GeminiContextClassifier } = require('./services/botGeminiService.js')

// ─── Persistent state ────────────────────────────────────────────────────────
const NOT_CONSENTED_TO_MESSAGE_UPLOAD = new Set()
const consentedUsers                  = new Set()
const pendingResponses                = new Map()   // users who were asked but haven't replied
const pendingListings                 = new Map()   // in-progress listings per user
const userTimers                      = new Map()   // per-user 5-min classification timers

// Persistence mechanism that loads consented users from disk (consentedUsersPersistence.JSON file)
JSON.parse(fs.readFileSync('consentedUsersPersistence.json', 'utf8'))
    .forEach(number => consentedUsers.add(number))

// ─── Config ───────────────────────────────────────────────────────────────────
const TARGET_GROUP = '120363406751456779@g.us'
// const TEST_USER = '40723136087' FOR TESTING ONLY

// ─── WhatsApp client ──────────────────────────────────────────────────────────
const client = new Client({ authStrategy: new LocalAuth() })

client.once('ready', () => console.log('Bot is ready!'))
client.on('qr', qr => qrcode.generate(qr, { small: true }))

// ─── Message handler ────────────────────────────────────────────────────────
client.on('message', async msg => {
    console.log('author:', msg.author, '| from:', msg.from)

    const contact = await msg.getContact()

    if (!pendingListings.has(contact.number)) {
        pendingListings.set(contact.number, {
            imageUrls : [],   // { data, mimetype } objects
            messages  : [],   // text bodies
            createdAt : Date.now(),
            isListing : false
        })
    }

    // ── Group messages ────────────────────────────────────────────────────────
    if (msg.from.endsWith('@g.us') && msg.from === TARGET_GROUP) {

        // Reset the 5-minute classification timer on every new message
        const classificationTimer = setTimeout(async () => {
            console.log('5 min passed — sending to Gemini for classification')

            // TODO: uncomment when API key is available
            // const geminiResponse = await GeminiContextClassifier(pendingListings.get(contact.number).messages)
            const geminiResponse = 'YES'   // MOCK — remove when API works

            if (geminiResponse === 'YES') {
                pendingListings.get(contact.number).isListing = true
                console.log('Gemini: valid listing detected — asking user for consent')

                if (!consentedUsers.has(contact.number) && !pendingResponses.has(contact.number)) {
                    await client.sendMessage(
                        contact.number + '@c.us',
                        'Hello! I am the StudentStoreFront bot. ' +
                        'I noticed you may be selling something. ' +
                        'Do you consent to adding your listing to our marketplace? Reply YES or NO.'
                    )
                    pendingResponses.set(contact.number, contact.number)
                } else if (consentedUsers.has(contact.number)) {
                    //TODO: implement the pasting to the group directly
                }
            } else {
                pendingListings.delete(contact.number)
                console.log('Gemini: not a listing — discarding')
            }

        }, 30000)   //TODO:  change back to 300000 (5 min) for production

        clearTimeout(userTimers.get(contact.number))
        userTimers.set(contact.number, classificationTimer)

        // Collect media
        if (msg.hasMedia) {
            const picture = await msg.downloadMedia()
            pendingListings.get(contact.number).imageUrls.push({
                data     : picture.data,
                mimetype : picture.mimetype
            })
        }

        // Collect text
        if (msg.body !== '') {
            pendingListings.get(contact.number).messages.push(msg.body)
        }

    }

    // ── DM responses (consent flow) ───────────────────────────────────────────
    if (msg.from.endsWith('@c.us') || msg.from.endsWith('@lid')) {
        const dmResponse = msg.body.toLowerCase().trim()

        if (dmResponse === 'yes') {
            console.log('User consented — processing listing')

            consentedUsers.add(contact.number)
            fs.writeFileSync('consentedUsersPersistence.json', JSON.stringify([...consentedUsers]))

            const currentUserListing = pendingListings.get(contact.number)

            // Upload images to Cloudinary
            const cloudinaryUrls = []
            for (const image of currentUserListing.imageUrls) {
                const base64 = `data:${image.mimetype};base64,${image.data}`
                const url    = await uploadImage(base64)
                cloudinaryUrls.push(url)
            }
            console.log('Cloudinary URLs:', cloudinaryUrls)

            // Parse listing fields with Gemini
            // TODO: uncomment when API key is available
            // const parsedListing = await GeminiMessageParser(currentUserListing.messages.join('\n'))
            const parsedListing = {   // MOCK — remove when API works
                title       : 'Test Item',
                price       : 100,
                description : 'Test description that is long enough for validation',
                category    : 'ELECTRONICS'
            }

            // TODO: look up sellerId by contact.number via GET /api/sellers/phone/{number}
            const sellerId = 1   // PLACEHOLDER — replace with real DB lookup

            await createPost(parsedListing, cloudinaryUrls, sellerId)

            await client.sendMessage(
                contact.number + '@c.us',
                'Your listing has been uploaded successfully!'
            )

            pendingResponses.delete(contact.number)
            pendingListings.delete(contact.number)

        } else if (dmResponse === 'no') {
            NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(contact.number)
            console.log('User declined consent')
        }
    }
})

// ─── Cleanup: remove stale listings every 3 hours (12-hour expiry) ────────────
setInterval(() => {
    pendingListings.forEach((listing, phoneNumber) => {
        if (listing.createdAt + 1000 * 60 * 60 * 12 < Date.now()) {
            pendingListings.delete(phoneNumber)
            console.log('Expired listing removed for:', phoneNumber)
        }
    })
}, 3 * 60 * 60 * 1000)

client.initialize()