const dns = require('dns').promises
const net = require('net')
const http = require('http')
const https = require('https')
const { URL } = require('url')

const MAX_BYTES = 5 * 1024 * 1024
const TIMEOUT_MS = 10000

function isPrivateAddress(address) {
    if (net.isIPv4(address)) {
        const octets = address.split('.').map(Number)
        const [a, b, c] = octets
        return a === 0 ||
            a === 10 ||
            (a === 100 && b >= 64 && b <= 127) ||
            a === 127 ||
            (a === 169 && b === 254) ||
            (a === 172 && b >= 16 && b <= 31) ||
            (a === 192 && b === 0 && (c === 0 || c === 2)) ||
            (a === 192 && b === 168) ||
            (a === 198 && b >= 18 && b <= 19) ||
            (a === 198 && b === 51 && c === 100) ||
            (a === 203 && b === 0 && c === 113) ||
            a >= 224
    }
    if (net.isIPv6(address)) {
        const normalized = new URL(`https://[${address}]/`).hostname.slice(1, -1)
        if (normalized.startsWith('::ffff:')) {
            const [high, low] = normalized.slice('::ffff:'.length).split(':').map(part => Number.parseInt(part, 16))
            return isPrivateAddress(`${high >> 8}.${high & 255}.${low >> 8}.${low & 255}`)
        }
        const firstHextet = Number.parseInt(normalized.split(':')[0] || '0', 16)
        const isLinkLocalOrSiteLocal = (firstHextet & 0xffc0) === 0xfe80 ||
            (firstHextet & 0xffc0) === 0xfec0
        return normalized === '::1' || normalized === '::' || normalized.startsWith('fc') ||
            normalized.startsWith('fd') || isLinkLocalOrSiteLocal || normalized.startsWith('ff') ||
            normalized.startsWith('2001:db8:')
    }
    return true
}

async function assertSafeUrl(rawUrl) {
    const parsed = new URL(rawUrl)
    if (parsed.protocol !== 'https:') throw new Error('Only HTTPS media URLs are supported')
    if (parsed.username || parsed.password) throw new Error('Media URL credentials are not allowed')
    const hostname = parsed.hostname.replace(/^\[|\]$/g, '').toLowerCase()
    if (!hostname || hostname === 'localhost' || hostname.endsWith('.local')) {
        throw new Error('Media host is not allowed')
    }
    const addresses = net.isIP(hostname)
        ? [{ address: hostname, family: net.isIPv6(hostname) ? 6 : 4 }]
        : await dns.lookup(hostname, { all: true, verbatim: true })
    if (!addresses.length || addresses.some(({ address }) => isPrivateAddress(address))) {
        throw new Error('Media host resolves to a private address')
    }
    return { parsed, address: addresses[0].address }
}

function requestBuffer(target, redirectsLeft) {
    return new Promise((resolve, reject) => {
        const { parsed, address } = target
        const transport = parsed.protocol === 'https:' ? https : http
        const request = transport.get(parsed, {
            timeout: TIMEOUT_MS,
            headers: { Accept: 'image/*' },
            lookup: (_hostname, _options, callback) =>
                callback(null, address, net.isIPv6(address) ? 6 : 4)
        }, response => {
            if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
                response.resume()
                if (redirectsLeft <= 0) return reject(new Error('Too many media redirects'))
                return resolve(fetchImageBuffer(new URL(response.headers.location, parsed), redirectsLeft - 1))
            }
            if (response.statusCode !== 200) {
                response.resume()
                return reject(new Error(`Media server returned ${response.statusCode}`))
            }
            const contentType = String(response.headers['content-type'] || '').split(';')[0].toLowerCase()
            if (!contentType.startsWith('image/')) {
                response.resume()
                return reject(new Error('Media response is not an image'))
            }
            const declaredLength = Number(response.headers['content-length'] || 0)
            if (declaredLength > MAX_BYTES) {
                response.resume()
                return reject(new Error('Media response is too large'))
            }
            const chunks = []
            let total = 0
            response.on('data', chunk => {
                total += chunk.length
                if (total > MAX_BYTES) {
                    request.destroy(new Error('Media response is too large'))
                    return
                }
                chunks.push(chunk)
            })
            response.on('end', () => resolve({ buffer: Buffer.concat(chunks), contentType }))
            response.on('error', reject)
        })
        request.on('timeout', () => request.destroy(new Error('Media request timed out')))
        request.on('error', reject)
    })
}

async function fetchImageBuffer(rawUrl, redirectsLeft = 3) {
    const target = await assertSafeUrl(rawUrl.toString())
    return requestBuffer(target, redirectsLeft)
}

async function fetchImageMedia(rawUrl) {
    const { MessageMedia } = require('whatsapp-web.js')
    const { buffer, contentType } = await fetchImageBuffer(rawUrl)
    return new MessageMedia(contentType, buffer.toString('base64'), 'listing-image')
}

module.exports = { fetchImageBuffer, fetchImageMedia, isPrivateAddress }
