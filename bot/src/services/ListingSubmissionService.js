const crypto = require('crypto')
const { traceUserId } = require('./privacy')
const KeyedMutex = require('./keyedMutex')
const { downscaleImagesForLlm } = require('./imagePreprocessor')

const VALID_CATEGORIES = new Set([
    'ELECTRONICS',
    'BOOKS',
    'CLOTHING',
    'FURNITURE',
    'SPORTS',
    'FOOD',
    'SERVICES',
    'OTHER'
])

function truncate(text, maxLength) {
    if (!text) return ''
    return text.length > maxLength ? text.slice(0, maxLength).trim() : text
}

function normalizeCategory(category) {
    const normalized = String(category || '').trim().replace(/[\s-]+/g, '_').toUpperCase()
    return VALID_CATEGORIES.has(normalized) ? normalized : 'OTHER'
}

// Reads "15", "12,50", "1,200", "1.200,50" and "1,200.50" (BOT-08). A single
// separator followed by exactly three digits is a thousands separator.
function normalizePrice(price) {
    if (typeof price === 'number') return price
    const match = String(price || '').match(/\d[\d.,' ]*/)
    if (!match) return NaN
    let text = match[0].replace(/[' ]/g, '').replace(/[.,]+$/, '')
    const lastComma = text.lastIndexOf(',')
    const lastDot = text.lastIndexOf('.')
    if (lastComma >= 0 && lastDot >= 0) {
        const decimal = lastComma > lastDot ? ',' : '.'
        const thousands = decimal === ',' ? '.' : ','
        text = text.split(thousands).join('').replace(decimal, '.')
    } else if (lastComma >= 0 || lastDot >= 0) {
        const separator = lastComma >= 0 ? ',' : '.'
        const parts = text.split(separator)
        const looksLikeThousands = parts.length > 2 || parts[parts.length - 1].length === 3
        text = looksLikeThousands ? parts.join('') : parts.join('.')
    }
    const value = Number(text)
    return Number.isFinite(value) ? value : NaN
}

function draftKey(listingDraft) {
    if (listingDraft?.draftId) return listingDraft.draftId
    const images = (listingDraft?.imageUrls || []).map(image => image?.hash || String(image?.data || '').slice(0, 64))
    return crypto.createHash('sha256')
        .update(JSON.stringify([listingDraft?.messages || [], images]))
        .digest('hex')
}

function normalizeImageIndexes(imageIndexes, imageCount) {
    if (!Array.isArray(imageIndexes) || imageCount <= 0) return []
    const seen = new Set()
    const normalized = []

    for (const rawIndex of imageIndexes) {
        const index = Number(rawIndex)
        if (!Number.isInteger(index) || index < 0 || index >= imageCount || seen.has(index)) continue
        seen.add(index)
        normalized.push(index)
    }

    return normalized
}

function safeTraceUpdate(trace, body) {
    if (!trace || typeof trace.update !== 'function') return
    trace.update(body)
}

function invalidResult(reason, parsedListing = null) {
    return {
        ok: false,
        reason,
        parsedListing
    }
}

function imageHash(image) {
    if (!image?.data) return null
    return crypto.createHash('sha256').update(Buffer.from(image.data, 'base64')).digest('hex')
}

class ListingSubmissionService {
    constructor(options) {
        this.client = options.client
        this.stateStore = options.stateStore
        this.consentedUsers = options.consentedUsers
        this.persistConsentedUsers = options.persistConsentedUsers
        this.uploadImage = options.uploadImage
        this.createPost = options.createPost
        this.getSellerByPhone = options.getSellerByPhone
        this.resolveMediaUrlsByHash = options.resolveMediaUrlsByHash
        this.messageParser = options.messageParser
        this.contextClassifier = options.contextClassifier
        this.langfuse = options.langfuse
        this.appBaseUrl = options.appBaseUrl
        this.submissionLock = new KeyedMutex()
    }

    async classifyListingDraft(contact, listingDraft, existingTrace = null) {
        if (typeof this.contextClassifier !== 'function') return 'YES'

        const messages = Array.isArray(listingDraft?.messages)
            ? listingDraft.messages.filter(message => typeof message === 'string' && message.trim() !== '')
            : []

        if (messages.length === 0) return 'YES'

        const decision = await this.contextClassifier(messages, {
            sessionId: traceUserId(contact.number),
            userId: traceUserId(contact.number),
            parent: existingTrace || undefined
        })

        if (decision === 'ERROR') return 'ERROR'
        return decision === 'YES' ? 'YES' : 'NO'
    }

    async processListing(contact, listingDraft) {
        const rawListingText = listingDraft.messages.join('\n').trim()
        const trace = this.langfuse.trace({
            name: 'ProcessListing',
            sessionId: traceUserId(contact.number),
            userId: traceUserId(contact.number)
        })

        try {
            if (!listingDraft?.isListing) {
                const classification = await this.classifyListingDraft(contact, listingDraft, trace)
                safeTraceUpdate(trace, {
                    output: `CLASSIFICATION: ${classification}`
                })

                if (classification === 'ERROR') {
                    safeTraceUpdate(trace, { level: 'ERROR', statusMessage: 'Classifier unavailable' })
                    return { ok: false, reason: 'retryable-listing-failure', postedCount: 0 }
                }

                if (classification !== 'YES') {
                    safeTraceUpdate(trace, {
                        level: 'WARNING',
                        statusMessage: 'Draft rejected by classifier before parsing'
                    })
                    return invalidResult('not-a-listing')
                }
            }

            const llmImages = await downscaleImagesForLlm(listingDraft.imageUrls)
            const parsedListing = await this.messageParser(rawListingText, llmImages, {
                sessionId: traceUserId(contact.number),
                userId: traceUserId(contact.number),
                parent: trace
            })

            if (parsedListing === null || parsedListing === undefined) {
                // Every LLM provider failed. Keep the draft instead of
                // discarding the user's text and photos as "invalid".
                safeTraceUpdate(trace, { level: 'ERROR', statusMessage: 'Listing parser unavailable' })
                return { ok: false, reason: 'retryable-listing-failure', postedCount: 0 }
            }

            const validListings = this.normalizeParsedListings(parsedListing, rawListingText, listingDraft.imageUrls.length)
            if (!validListings.ok) {
                console.error('Failed to parse valid listing(s) from draft:', validListings.reason)
                safeTraceUpdate(trace, {
                    level: 'WARNING',
                    statusMessage: `Invalid listing draft: ${validListings.reason}`,
                    output: validListings.parsedListing
                })
                return validListings
            }

            console.log(`Parsed ${validListings.listings.length} listing candidate(s):`, validListings.listings)

            const user = await this.getSellerByPhone(contact.number)
            if (!user) {
                safeTraceUpdate(trace, { level: 'WARNING', statusMessage: 'User not found' })
                return null
            }

            const mediaSpan = trace.span({ name: 'ResolveListingMedia' })
            const media = await this.resolveDraftMedia(listingDraft.imageUrls)
            mediaSpan.end({
                output: {
                    imageCount: media.urls.length,
                    reusedCount: media.reusedCount,
                    uploadedCount: media.uploadedCount
                }
            })

            const springSpan = trace.span({ name: 'CreatePost' })
            const results = []
            for (const [listingIndex, listing] of validListings.listings.entries()) {
                const listingMedia = this.selectMediaForListing(listing, media)
                const listingPayload = {
                    title: listing.title,
                    price: listing.price,
                    description: listing.description,
                    category: listing.category,
                    // Keyed on the draft, not on the LLM's wording, so a retry
                    // of the same draft can't post an item twice (BOT-04).
                    idempotencyKey: crypto.createHash('sha256')
                        .update(`${contact.number}:${draftKey(listingDraft)}:${listingIndex}`)
                        .digest('hex')
                }
                results.push(await this.createPost(listingPayload, listingMedia.urls, user.userId, listingMedia.hashes))
            }

            const successfulCount = results.filter(result => result?.ok).length
            const conflictCount = results.filter(result => result?.status === 409).length
            const failedCount = results.length - successfulCount - conflictCount
            springSpan.end({ output: { successfulCount, conflictCount, failedCount } })

            if (successfulCount === results.length) {
                safeTraceUpdate(trace, { output: `SUCCESS: ${successfulCount} listing(s)` })
                return {
                    ok: true,
                    postedCount: successfulCount
                }
            }

            if (conflictCount > 0 && failedCount === 0) {
                safeTraceUpdate(trace, { level: 'WARNING', statusMessage: `${conflictCount} listing conflict(s) rejected by backend` })
                return successfulCount > 0
                    ? { ok: false, reason: 'partial-listing-conflict', postedCount: successfulCount, conflictCount }
                    : 'listing-conflict'
            }

            if (failedCount > 0) {
                return {
                    ok: false,
                    reason: 'retryable-listing-failure',
                    postedCount: successfulCount,
                    conflictCount,
                    failedCount
                }
            }

            safeTraceUpdate(trace, { output: 'FAILED' })
            return false
        } catch (error) {
            console.error('Listing processing failed:', error)
            safeTraceUpdate(trace, { level: 'ERROR', statusMessage: error.message })
            return { ok: false, reason: 'retryable-listing-failure', postedCount: 0 }
        } finally {
            await this.langfuse.flush()
        }
    }

    normalizeParsedListing(parsedListing, rawListingText) {
        if (!parsedListing || typeof parsedListing !== 'object' || Array.isArray(parsedListing)) {
            return invalidResult('invalid-listing', parsedListing)
        }

        const title = truncate(String(parsedListing.title || '').trim(), 100)
        const price = normalizePrice(parsedListing.price)
        let description = truncate(String(parsedListing.description || '').trim(), 1000)
        const fallbackDescription = truncate(rawListingText, 1000)
        const category = normalizeCategory(parsedListing.category)
        const imageIndexes = normalizeImageIndexes(parsedListing.imageIndexes, Number(parsedListing.__imageCount || 0))

        if (description.length < 10 && fallbackDescription.length >= 10) {
            description = fallbackDescription
        }

        if (!title) return invalidResult('missing-title', parsedListing)
        if (!Number.isFinite(price) || price <= 0 || price > 999999.99) return invalidResult('missing-price', parsedListing)
        if (description.length < 10) return invalidResult('missing-description', parsedListing)

        return {
            ok: true,
            listing: {
                title,
                price,
                description,
                category,
                imageIndexes
            }
        }
    }

    selectMediaForListing(listing, media) {
        const byIndex = Array.isArray(listing.imageIndexes) ? listing.imageIndexes : []
        if (byIndex.length === 0) {
            return {
                urls: media.urls,
                hashes: validHashList(media.urls, media.hashes)
            }
        }

        const urls = []
        const hashes = []
        for (const index of byIndex) {
            const url = media.urls[index]
            if (!url) continue
            urls.push(url)
            hashes.push(media.hashes[index] || '')
        }

        return {
            urls: urls.length > 0 ? urls : media.urls,
            hashes: validHashList(urls.length > 0 ? urls : media.urls, hashes.length > 0 ? hashes : media.hashes)
        }
    }

    async resolveDraftMedia(images = []) {
        const hashes = images.map(imageHash)
        const existingByHash = typeof this.resolveMediaUrlsByHash === 'function'
            ? await this.resolveMediaUrlsByHash(hashes)
            : {}
        const uploadedByHash = {}
        const urls = []
        const resolvedHashes = []
        let reusedCount = 0
        let uploadedCount = 0

        for (let index = 0; index < images.length; index += 1) {
            const image = images[index]
            const hash = hashes[index]
            const existingUrl = hash ? existingByHash[hash] || uploadedByHash[hash] : null

            if (existingUrl) {
                urls.push(existingUrl)
                resolvedHashes.push(hash || '')
                reusedCount += 1
                continue
            }

            const base64 = `data:${image.mimetype};base64,${image.data}`
            const uploadedUrl = await this.uploadImage(base64)
            if (!uploadedUrl) throw new Error('Image upload failed')

            if (hash) uploadedByHash[hash] = uploadedUrl
            urls.push(uploadedUrl)
            resolvedHashes.push(hash || '')
            uploadedCount += 1
        }

        return {
            urls,
            hashes: validHashList(urls, resolvedHashes),
            reusedCount,
            uploadedCount
        }
    }

    normalizeParsedListings(parsedListing, rawListingText, imageCount = 0) {
        const candidates = Array.isArray(parsedListing)
            ? parsedListing
            : Array.isArray(parsedListing?.listings)
                ? parsedListing.listings
                : [parsedListing]

        if (candidates.length === 0) return invalidResult('invalid-listing', parsedListing)

        const listings = []
        for (const candidate of candidates) {
            const normalized = this.normalizeParsedListing(
                { ...candidate, __imageCount: imageCount },
                rawListingText
            )
            if (!normalized.ok) {
                if (normalized.reason === 'missing-price') {
                    const missingItems = candidates
                        .map((item, index) => ({
                            index: index + 1,
                            title: truncate(String(item?.title || '').trim(), 100) || `Item ${index + 1}`,
                            hasPrice: Number.isFinite(normalizePrice(item?.price)) && normalizePrice(item?.price) > 0
                        }))
                        .filter(item => !item.hasPrice)
                        .map(({ index, title }) => ({ index, title }))

                    return invalidResult('missing-price', {
                        parsedListing,
                        missingItems
                    })
                }
                return invalidResult(normalized.reason, parsedListing)
            }
            listings.push(normalized.listing)
        }

        return {
            ok: true,
            listings
        }
    }

    // One submission at a time per phone (BOT-09); a second call that arrives
    // while the first runs sees the cleared state and stops.
    submitListingDraft(options) {
        return this.submissionLock.run(options.phoneNumber, () => this.submitListingDraftNow(options))
    }

    async submitListingDraftNow({
        contact,
        phoneNumber,
        replyChatId,
        successMessage = 'Your listing has been uploaded successfully!',
        failureMessage = 'Something went wrong uploading your listing. Please try again later.',
        missingPriceMessage = 'I can tell what you are selling, but I still need a price before I can upload it. Reply with just the price (for example: "15"), and I will keep this draft for 24 hours.',
        listingConflictMessage = 'I could not upload this listing because the server reported a conflicting existing record. It may be an old or hidden listing, so please check your profile or edit the details and try again.',
        invalidListingMessage = 'This draft does not look like a real marketplace listing, so I did not post it. Send the item name, price, category, and a short description, then try again.',
        registrationMessage = `You need to finish account setup first! Visit ${this.appBaseUrl} and click Sign Up. Verify your email and link WhatsApp login, then your listing will be uploaded automatically.`,
        markConsentedOnSuccess = false,
        clearStateOnSuccess = true,
        clearStateOnFailure = true,
        stageOnMissingPrice = true
    }) {
        const state = this.stateStore.get(phoneNumber)
        if (!state) return 'missing-state'

        const result = await this.processListing(contact, state.listing)

        if (result === true || result?.ok === true) {
            if (markConsentedOnSuccess) {
                this.consentedUsers.add(phoneNumber)
                this.persistConsentedUsers()
            }
            const uploadedMessage = result?.postedCount && result.postedCount > 1
                ? `${result.postedCount} listings have been uploaded successfully!`
                : successMessage
            await this.client.sendMessage(replyChatId, uploadedMessage)
            if (clearStateOnSuccess) this.stateStore.clear(phoneNumber)
            return 'success'
        }

        if (result === null) {
            this.stateStore.setRegistrationPending(phoneNumber, true)
            this.stateStore.save()
            await this.client.sendMessage(replyChatId, registrationMessage)
            return 'needs-registration'
        }

        if (result === 'missing-price') {
            if (stageOnMissingPrice) {
                this.stateStore.setMode(phoneNumber, 'awaiting-price')
                this.stateStore.save()
            } else if (clearStateOnFailure) {
                this.stateStore.clear(phoneNumber)
            }
            await this.client.sendMessage(replyChatId, missingPriceMessage)
            return 'missing-price'
        }

        if (result?.reason === 'missing-price') {
            if (stageOnMissingPrice) {
                this.stateStore.setMode(phoneNumber, 'awaiting-price')
                this.stateStore.save()
            } else if (clearStateOnFailure) {
                this.stateStore.clear(phoneNumber)
            }

            const missingItems = Array.isArray(result.parsedListing?.missingItems)
                ? result.parsedListing.missingItems
                : []
            const dynamicMessage = missingItems.length > 0
                ? [
                    'I found multiple items, but some are missing prices.',
                    ...missingItems.map(item => `${item.index}. ${item.title}`),
                    'Reply with the missing prices (you can send one message like "1=20, 2=15" or separate messages).'
                ].join('\n')
                : missingPriceMessage

            await this.client.sendMessage(replyChatId, dynamicMessage)
            return 'missing-price'
        }

        if (result?.reason === 'not-a-listing') {
            if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
            await this.client.sendMessage(replyChatId, invalidListingMessage)
            return 'not-a-listing'
        }

        if (result === 'listing-conflict') {
            if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
            await this.client.sendMessage(replyChatId, listingConflictMessage)
            return 'listing-conflict'
        }

        if (result?.reason === 'partial-listing-conflict') {
            await this.client.sendMessage(replyChatId, `${result.postedCount} listing(s) uploaded, but ${result.conflictCount} could not be saved because the server reported a conflicting existing record. The draft was kept so you can retry or cancel it.`)
            return 'partial-listing-conflict'
        }

        if (result?.reason === 'retryable-listing-failure') {
            const savedCount = result.postedCount > 0 ? `${result.postedCount} listing(s) were saved. ` : ''
            await this.client.sendMessage(replyChatId, `${savedCount}${failureMessage} The draft was kept so a retry will not lose the remaining items.`)
            return 'retryable-listing-failure'
        }

        if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
        await this.client.sendMessage(replyChatId, failureMessage)
        return 'failed'
    }
}

function validHashList(urls, hashes) {
    return Array.isArray(hashes) && hashes.length === urls.length &&
        hashes.every(hash => typeof hash === 'string' && /^[a-f0-9]{64}$/i.test(hash))
        ? hashes
        : []
}

module.exports = ListingSubmissionService
module.exports.normalizePrice = normalizePrice
