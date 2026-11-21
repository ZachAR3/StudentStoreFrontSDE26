const assert = require('assert')

process.env.SPRING_BASE_URL = process.env.SPRING_BASE_URL || 'http://127.0.0.1:9'
process.env.BOT_API_KEY = process.env.BOT_API_KEY || 'test-only-bot-key'

const springServices = require('../services/springServices')
const { isPrivateAddress } = require('../services/safeRemoteMedia')
const ListingSubmissionService = require('../services/ListingSubmissionService')

assert.strictEqual(typeof springServices.createPost, 'function')
assert.strictEqual(typeof springServices.resolveMediaUrlsByHash, 'function')
assert.strictEqual(typeof springServices.renewPost, 'function')
assert.strictEqual(isPrivateAddress('127.0.0.1'), true)
assert.strictEqual(isPrivateAddress('192.0.2.1'), true)
assert.strictEqual(isPrivateAddress('198.51.100.1'), true)
assert.strictEqual(isPrivateAddress('fe81::1'), true)
assert.strictEqual(isPrivateAddress('fec1::1'), true)
assert.strictEqual(isPrivateAddress('0:0:0:0:0:0:0:1'), true)
assert.strictEqual(isPrivateAddress('0:0:0:0:0:ffff:7f00:1'), true)
assert.strictEqual(isPrivateAddress('0:0:0:0:0:ffff:5db8:d822'), false)
assert.strictEqual(isPrivateAddress('93.184.216.34'), false)

const submissionService = new ListingSubmissionService({
    client: {},
    stateStore: {},
    consentedUsers: new Set(),
    persistConsentedUsers() {},
    uploadImage: async () => 'https://cdn.example.test/uploaded.jpg',
    createPost: async () => ({ ok: true }),
    getSellerByPhone: async () => ({ userId: 1 }),
    resolveMediaUrlsByHash: async () => ({}),
    messageParser: async () => ({ title: 'Test listing', price: 1, description: 'A valid test listing', category: 'OTHER' }),
    contextClassifier: async () => 'YES',
    langfuse: { trace: () => ({ span: () => ({ end() {} }), update() {} }) },
    appBaseUrl: 'http://localhost:8080'
})
assert.strictEqual(typeof submissionService.resolveDraftMedia, 'function')

let clearedRetryDraft = false
let retryMessage = null
const retryState = {
    listing: { messages: ['Retry this listing'], imageUrls: [] }
}
const retryService = new ListingSubmissionService({
    client: { sendMessage: async (_chatId, message) => { retryMessage = message } },
    stateStore: {
        get: () => retryState,
        clear: () => { clearedRetryDraft = true }
    },
    consentedUsers: new Set(),
    persistConsentedUsers() {},
    uploadImage: async () => 'https://cdn.example.test/uploaded.jpg',
    createPost: async () => ({ ok: false, status: 503 }),
    getSellerByPhone: async () => ({ userId: 1 }),
    resolveMediaUrlsByHash: async () => ({}),
    messageParser: async () => ({ title: 'Retry listing', price: 1, description: 'A valid retry listing', category: 'OTHER' }),
    contextClassifier: async () => 'YES',
    langfuse: {
        trace: () => ({ span: () => ({ end() {} }), update() {} }),
        flush: async () => {}
    },
    appBaseUrl: 'http://localhost:8080'
})
retryService.submitListingDraft({
    phoneNumber: '15551234567',
    contact: { number: '15551234567' },
    replyChatId: '15551234567@c.us'
}).then(async retryResult => {
    assert.strictEqual(retryResult, 'retryable-listing-failure')
    assert.strictEqual(clearedRetryDraft, false)
    assert.match(retryMessage, /draft was kept/)
    retryService.getSellerByPhone = async () => { throw new Error('Synthetic backend outage') }
    const lookupResult = await retryService.submitListingDraft({
        phoneNumber: '15551234567',
        contact: { number: '15551234567' },
        replyChatId: '15551234567@c.us'
    })
    assert.strictEqual(lookupResult, 'retryable-listing-failure')
    assert.strictEqual(clearedRetryDraft, false)
    assert.match(retryMessage, /draft was kept/)

    // BOT-01: price replies must not be mistaken for shopping commands.
    const { looksLikePriceReply } = require('../services/priceReply')
    for (const reply of ['15', '15€', '€ 12.50', '12,5 eur', '1=20, 2=15', '1: 20\n2: 15']) {
        assert.strictEqual(looksLikePriceReply(reply), true, reply)
    }
    for (const reply of ['shop', 'n', 'details 3', 'about 15', '', '15 for the chair']) {
        assert.strictEqual(looksLikePriceReply(reply), false, reply)
    }

    // BOT-03: a parser outage keeps the draft instead of clearing it.
    retryService.getSellerByPhone = async () => ({ userId: 1 })
    retryService.messageParser = async () => null
    clearedRetryDraft = false
    const outageResult = await retryService.submitListingDraft({
        phoneNumber: '15551234567',
        contact: { number: '15551234567' },
        replyChatId: '15551234567@c.us'
    })
    assert.strictEqual(outageResult, 'retryable-listing-failure')
    assert.strictEqual(clearedRetryDraft, false)

    // BOT-03: a classifier outage is retryable, not "not a listing".
    retryService.contextClassifier = async () => 'ERROR'
    const classifierOutage = await retryService.submitListingDraft({
        phoneNumber: '15551234567',
        contact: { number: '15551234567' },
        replyChatId: '15551234567@c.us'
    })
    assert.strictEqual(classifierOutage, 'retryable-listing-failure')
    assert.strictEqual(clearedRetryDraft, false)

    // BOT-02: seller links come from the contact endpoint payload.
    const WhatsAppShoppingChat = require('../shopping/WhatsAppShoppingChat')
    const sent = []
    const chat = Object.create(WhatsAppShoppingChat.prototype)
    chat.client = { sendMessage: async (_id, text) => sent.push(text) }
    chat.appBaseUrl = 'http://localhost:8080'
    chat.sessions = new Map([['buyer@c.us', { items: [{ postId: 7, title: 'Desk' }] }]])
    chat.postService = {
        getSellerContact: async (postId, phone) => {
            assert.strictEqual(postId, 7)
            assert.strictEqual(phone, '15550002222')
            return { postId: 7, title: 'Desk', sellerName: 'Sam', whatsappPhone: '+15550001111' }
        }
    }
    await chat.sendSellerLink('buyer@c.us', '1', { number: '15550002222' })
    assert.match(sent.pop(), /wa\.me\/15550001111/)
    chat.postService.getSellerContact = async () => { const e = new Error('forbidden'); e.response = { status: 403 }; throw e }
    await chat.sendSellerLink('buyer@c.us', '1', { number: '15550002222' })
    assert.match(sent.pop(), /Only registered/)

    // BOT-08: European and English thousands/decimal separators.
    const { normalizePrice } = ListingSubmissionService
    assert.strictEqual(normalizePrice('1,200'), 1200)
    assert.strictEqual(normalizePrice('12,50'), 12.5)
    assert.strictEqual(normalizePrice('1.200,50'), 1200.5)
    assert.strictEqual(normalizePrice('1,200.50'), 1200.5)
    assert.strictEqual(normalizePrice('€ 15'), 15)
    assert.ok(Number.isNaN(normalizePrice('free')))

    // WA-04: a LID is never used as a phone number.
    const { getContactNumber } = require('../services/chatIdentity')
    assert.strictEqual(getContactNumber({ id: { user: '987654321', server: 'lid', _serialized: '987654321@lid' } }, '987654321@lid'), null)
    assert.strictEqual(getContactNumber({ number: '4915112345', id: { user: '1', server: 'lid' } }), '4915112345')
    assert.strictEqual(getContactNumber({ id: { user: '4915112345', server: 'c.us' } }), '4915112345')

    // X-09: trace IDs don't contain the phone number.
    const { traceUserId } = require('../services/privacy')
    assert.strictEqual(traceUserId('+49 151 12345'), traceUserId('4915112345'))
    assert.ok(!traceUserId('4915112345').includes('4915112345'))

    // BOT-09: work for one key runs one at a time.
    const KeyedMutex = require('../services/keyedMutex')
    const mutex = new KeyedMutex()
    const order = []
    const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
    await Promise.all([
        mutex.run('a', async () => { order.push('a1-start'); await delay(20); order.push('a1-end') }),
        mutex.run('a', async () => { order.push('a2-start'); order.push('a2-end') })
    ])
    assert.deepStrictEqual(order, ['a1-start', 'a1-end', 'a2-start', 'a2-end'])

    // WA-02 / WA-05: declines and processed IDs survive a restart.
    const os = require('os')
    const fs = require('fs')
    const path = require('path')
    const PersistentSet = require('../services/persistentSet')
    const setFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'bot-smoke-')), 'set.json')
    const declined = new PersistentSet(setFile, { maxSize: 2 })
    declined.add('1'); declined.add('2'); declined.add('3')
    const restored = new PersistentSet(setFile)
    assert.deepStrictEqual([...restored.items], ['2', '3'])
    fs.rmSync(path.dirname(setFile), { recursive: true, force: true })

    console.log('Bot smoke checks passed.')
}).catch(error => {
    console.error(error)
    process.exitCode = 1
})
