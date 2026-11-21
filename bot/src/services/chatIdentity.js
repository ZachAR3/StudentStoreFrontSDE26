function isDirectChatId(chatId = '') {
    return chatId.endsWith('@c.us') || chatId.endsWith('@lid')
}

function extractUserFromChatId(chatId = '') {
    if (!chatId || !chatId.includes('@')) return null
    return chatId.split('@')[0] || null
}

function isDirectMessage(msg) {
    return isDirectChatId(msg?.from || '')
}

function isLidId(value = '') {
    return String(value).endsWith('@lid')
}

/**
 * The sender's phone number, or null. For privacy-addressed contacts (@lid)
 * the id "user" part is a LID, not a phone number, so it is never used as
 * one (WA-04); only contact.number is trusted there.
 */
function getContactNumber(contact, fallbackChatId = null) {
    if (contact?.number) return contact.number
    const lidContact = contact?.id?.server === 'lid' || isLidId(contact?.id?._serialized)
    if (contact?.id?.user && !lidContact) return contact.id.user
    if (fallbackChatId && !isLidId(fallbackChatId)) return extractUserFromChatId(fallbackChatId)
    return null
}

function getDirectChatId(contact, fallbackChatId = null) {
    if (isDirectChatId(fallbackChatId || '')) return fallbackChatId

    const serializedId = contact?.id?._serialized
    if (isDirectChatId(serializedId || '')) return serializedId

    // A phone number always addresses a @c.us chat.
    const user = getContactNumber(contact, fallbackChatId)
    return user ? `${user}@c.us` : null
}

function normalizePhoneNumber(phoneNumber = '') {
    // null/undefined (no readable number) normalise to ''.
    if (!phoneNumber) return ''
    return phoneNumber.replace(/\D/g, '')
}

module.exports = {
    isDirectChatId,
    isDirectMessage,
    getContactNumber,
    getDirectChatId,
    normalizePhoneNumber
}
