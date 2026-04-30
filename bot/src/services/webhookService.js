const express = require('express')
require('dotenv').config({ path: require('path').resolve(__dirname, '../../.env') })

function startWebhookServer(stateStore, listingSubmissionService) {
    const app  = express()
    const port = process.env.BOT_WEBHOOK_PORT || 3001

    app.use(express.json())

    app.post('/webhook/seller-registered', async (req, res) => {
        const apiKey = req.headers['x-bot-api-key']
        if (!apiKey || apiKey !== process.env.BOT_API_KEY) {
            return res.status(401).json({ error: 'Unauthorized' })
        }

        let { phoneNumber } = req.body
        if (!phoneNumber) {
            return res.status(400).json({ error: 'phoneNumber is required' })
        }

        // Normalize: Strip all non-digits (removes '+', spaces, etc.) to match bot's userState keys
        phoneNumber = phoneNumber.replace(/\D/g, '')

        const state = stateStore.get(phoneNumber)
        if (!state || !state.registrationPending) {
            return res.status(404).json({ error: 'No pending registration for this number' })
        }

        // respond immediately so Spring isn't blocked
        res.status(200).json({ status: 'processing' })

        try {
            const contact = { number: phoneNumber }
            await listingSubmissionService.submitListingDraft({
                contact,
                phoneNumber,
                replyChatId: `${phoneNumber}@c.us`,
                registrationMessage: 'Could not find your account. Make sure you registered with this phone number!',
                markConsentedOnSuccess: true
            })
        } catch (error) {
            console.error('Webhook processing failed for', phoneNumber, error)
        }
    })

    app.listen(port, () => {
        console.log(`Webhook server listening on port ${port}`)
    })
}

module.exports = { startWebhookServer }
