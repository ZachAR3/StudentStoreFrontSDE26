const crypto = require('crypto')

// Traces sent to Langfuse identify a user by a salted hash, not their phone
// number (WA-02 / X-09). Set TRACE_ID_SALT so the IDs can't be reversed by
// hashing known numbers; the same phone still maps to the same trace user.
const SALT = process.env.TRACE_ID_SALT || process.env.BOT_API_KEY || 'student-storefront'

function traceUserId(phoneNumber) {
    const digits = String(phoneNumber || '').replace(/\D/g, '')
    if (!digits) return 'anonymous'
    return 'u_' + crypto.createHmac('sha256', SALT).update(digits).digest('hex').slice(0, 16)
}

module.exports = { traceUserId }
