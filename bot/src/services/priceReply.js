// A reply that is only a price ("15", "15€", "€ 12.50") or per-item prices
// ("1=20, 2=15"). These must reach the draft before shopping commands, which
// treat a bare number as "select item N".
function looksLikePriceReply(rawBody) {
    const body = String(rawBody || '').trim()
    if (!body) return false
    const amount = '(?:€\\s*)?\\d{1,6}(?:[.,]\\d{1,2})?\\s*(?:€|eur|euro|euros)?'
    const single = new RegExp(`^${amount}$`, 'i')
    const perItem = new RegExp(`^\\d{1,2}\\s*[=:]\\s*${amount}(?:\\s*[,;\\n]\\s*\\d{1,2}\\s*[=:]\\s*${amount})*$`, 'i')
    return single.test(body) || perItem.test(body)
}

module.exports = { looksLikePriceReply }
