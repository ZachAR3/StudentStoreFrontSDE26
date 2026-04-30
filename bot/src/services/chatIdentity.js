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

function getContactNumber(contact, fallbackChatId = null) {
    return contact?.number || contact?.id?.user || extractUserFromChatId(fallbackChatId || '')
}

function getDirectChatId(contact, fallbackChatId = null) {
    if (isDirectChatId(fallbackChatId || '')) return fallbackChatId

    const serializedId = contact?.id?._serialized
    if (isDirectChatId(serializedId || '')) return serializedId

    const user = getContactNumber(contact, fallbackChatId)
    const server = contact?.id?.server
    if (user && isDirectChatId(`${user}@${server || 'c.us'}`)) {
        return `${user}@${server || 'c.us'}`
    }

    if (!user) return null
    return `${user}@c.us`
}

function normalizePhoneNumber(phoneNumber = '') {
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
