const crypto = require('crypto')
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

function normalizePrice(price) {
    if (typeof price === 'number') return price
    const numericText = String(price || '').replace(',', '.').match(/\d+(\.\d+)?/)?.[0]
    return numericText ? Number(numericText) : NaN
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
        this.messageParser = options.messageParser
        this.contextClassifier = options.contextClassifier
        this.langfuse = options.langfuse
        this.appBaseUrl = options.appBaseUrl
    }

    async classifyListingDraft(contact, listingDraft, existingTrace = null) {
        if (typeof this.contextClassifier !== 'function') return 'YES'

        const messages = Array.isArray(listingDraft?.messages)
            ? listingDraft.messages.filter(message => typeof message === 'string' && message.trim() !== '')
            : []

        if (messages.length === 0) return 'YES'

        const decision = await this.contextClassifier(messages, {
            sessionId: contact.number,
            userId: contact.number,
            parent: existingTrace || undefined
        })

        return decision === 'YES' ? 'YES' : 'NO'
    }

    async processListing(contact, listingDraft) {
        const rawListingText = listingDraft.messages.join('\n').trim()
        const trace = this.langfuse.trace({
            name: 'ProcessListing',
            sessionId: contact.number,
            userId: contact.number
        })

        try {
            if (!listingDraft?.isListing) {
                const classification = await this.classifyListingDraft(contact, listingDraft, trace)
                safeTraceUpdate(trace, {
                    output: `CLASSIFICATION: ${classification}`
                })

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
                sessionId: contact.number,
                userId: contact.number,
                parent: trace
            })

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
            for (const listing of validListings.listings) {
                const listingMedia = this.selectMediaForListing(listing, media)
                const listingPayload = {
                    title: listing.title,
                    price: listing.price,
                    description: listing.description,
                    category: listing.category
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

            safeTraceUpdate(trace, { output: 'FAILED' })
            return false
        } catch (error) {
            console.error('Listing processing failed:', error)
            safeTraceUpdate(trace, { level: 'ERROR', statusMessage: error.message })
            return false
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
                hashes: media.hashes
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
            hashes: hashes.length > 0 ? hashes : media.hashes
        }
    }

    async resolveDraftMedia(images = []) {
        const hashes = images.map(imageHash)
        const existingByHash = {}
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
            if (!uploadedUrl) continue

            if (hash) uploadedByHash[hash] = uploadedUrl
            urls.push(uploadedUrl)
            resolvedHashes.push(hash || '')
            uploadedCount += 1
        }

        return {
            urls,
            hashes: resolvedHashes,
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

    async submitListingDraft({
        contact,
        phoneNumber,
        replyChatId,
        successMessage = 'Your listing has been uploaded successfully!',
        failureMessage = 'Something went wrong uploading your listing. Please try again later.',
        missingPriceMessage = 'I can tell what you are selling, but I still need a price before I can upload it. Reply with just the price (for example: "15"), and I will keep this draft for 24 hours.',
        listingConflictMessage = 'I could not upload this listing because the server reported a conflicting existing record. It may be an old or hidden listing, so please check your profile or edit the details and try again.',
        invalidListingMessage = 'This draft does not look like a real marketplace listing, so I did not post it. Send the item name, price, category, and a short description, then try again.',
        registrationMessage = `You need to register first! Visit ${this.appBaseUrl} and click Sign Up. Once you verify your email, your listing will be uploaded automatically.`,
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
            if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
            await this.client.sendMessage(replyChatId, `${result.postedCount} listing(s) uploaded, but ${result.conflictCount} could not be saved because the server reported a conflicting existing record.`)
            return 'partial-listing-conflict'
        }

        if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
        await this.client.sendMessage(replyChatId, failureMessage)
        return 'failed'
    }
}

module.exports = ListingSubmissionService
