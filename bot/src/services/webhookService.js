const express = require('express')
const crypto = require('crypto')
require('dotenv').config({ path: require('path').resolve(__dirname, '../../.env') })
const { normalizePhoneNumber } = require('./chatIdentity')

function matchesBotKey(candidate) {
    const expected = process.env.BOT_API_KEY || ''
    if (!candidate || !expected) return false
    const provided = Buffer.from(String(candidate))
    const configured = Buffer.from(expected)
    return provided.length === configured.length && crypto.timingSafeEqual(provided, configured)
}

function startWebhookServer(stateStore, listingSubmissionService, client, options = {}) {
    const app  = express()
    const port = process.env.BOT_WEBHOOK_PORT || 3001
    const consentedUsers = options.consentedUsers
    const persistConsentedUsers = options.persistConsentedUsers || (() => {})

    app.use(express.json())

    app.post('/send-message', async (req, res) => {
        const apiKey = req.headers['x-bot-api-key']
        if (!matchesBotKey(apiKey)) {
            return res.status(401).json({ error: 'Unauthorized' })
        }

        const phoneNumber = normalizePhoneNumber(req.body.phone || '')
        const message = String(req.body.message || '').trim()
        if (!phoneNumber || !message) {
            return res.status(400).json({ error: 'phone and message are required' })
        }

        try {
            await client.sendMessage(`${phoneNumber}@c.us`, message)
            return res.status(200).json({ status: 'sent' })
        } catch (error) {
            console.error('Failed to send webhook message:', error.message)
            return res.status(502).json({ error: 'Could not send WhatsApp message' })
        }
    })

    app.post('/webhook/user-registered', async (req, res) => {
        const apiKey = req.headers['x-bot-api-key']
        if (!matchesBotKey(apiKey)) {
            return res.status(401).json({ error: 'Unauthorized' })
        }

        const rawPhoneNumber = req.body.phoneNumber
        if (!rawPhoneNumber) {
            return res.status(400).json({ error: 'phoneNumber is required' })
        }

        // Use centralized normalization
        const phoneNumber = normalizePhoneNumber(rawPhoneNumber)

        const state = stateStore.get(phoneNumber)
        if (!state || !state.registrationPending) {
            return res.status(200).json({ status: 'ignored' })
        }

        // Posting the draft can take longer than the backend's 10s call
        // timeout (LLM parse, uploads), so this is accepted for processing
        // (202), not reported as delivered. The draft stays in the bot's state
        // until it is posted, and per-draft idempotency keys make a backend
        // retry harmless (WA-07). /send-message, by contrast, only answers
        // after WhatsApp accepted the message.
        res.status(202).json({ status: 'accepted' })

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

    app.post('/webhook/account-deleted', (req, res) => {
        const apiKey = req.headers['x-bot-api-key']
        if (!matchesBotKey(apiKey)) {
            return res.status(401).json({ error: 'Unauthorized' })
        }

        const phoneNumber = normalizePhoneNumber(req.body.phoneNumber || '')
        if (!phoneNumber) {
            return res.status(400).json({ error: 'phoneNumber is required' })
        }

        stateStore.clear(phoneNumber)
        if (consentedUsers?.delete(phoneNumber)) persistConsentedUsers()
        return res.status(200).json({ status: 'cleared' })
    })

    // Only the co-located backend calls this server, so listen on loopback by
    // default. Set BOT_WEBHOOK_HOST (e.g. 0.0.0.0) only when the backend runs
    // on another host, and then put TLS and a firewall in front of it.
    const host = process.env.BOT_WEBHOOK_HOST || '127.0.0.1'
    app.listen(port, host, () => {
        console.log(`Webhook server listening on ${host}:${port}`)
    })
}

module.exports = { startWebhookServer }
