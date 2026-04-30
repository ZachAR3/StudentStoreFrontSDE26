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
        this.langfuse = options.langfuse
        this.appBaseUrl = options.appBaseUrl
    }

    async processListing(contact, listingDraft) {
        const rawListingText = listingDraft.messages.join('\n').trim()
        const trace = this.langfuse.trace({
            name: 'ProcessListing',
            sessionId: contact.number,
            userId: contact.number
        })

        try {
            const llmImages = await downscaleImagesForLlm(listingDraft.imageUrls)
            const parsedListing = await this.messageParser(rawListingText, llmImages, {
                sessionId: contact.number,
                userId: contact.number,
                parent: trace
            })

            const validListing = this.normalizeParsedListing(parsedListing, rawListingText)
            if (!validListing.ok) {
                console.error('Failed to parse a valid listing from draft:', validListing.reason)
                safeTraceUpdate(trace, {
                    level: 'WARNING',
                    statusMessage: `Invalid listing draft: ${validListing.reason}`,
                    output: validListing.parsedListing
                })
                return validListing.reason
            }

            const seller = await this.getSellerByPhone(contact.number)
            if (!seller) {
                safeTraceUpdate(trace, { level: 'WARNING', statusMessage: 'Seller not found' })
                return null
            }

            const cloudinarySpan = trace.span({ name: 'CloudinaryUpload' })
            const uploadResults = await Promise.all(
                listingDraft.imageUrls.map(image => {
                    const base64 = `data:${image.mimetype};base64,${image.data}`
                    return this.uploadImage(base64)
                })
            )
            const cloudinaryUrls = uploadResults.filter(url => url !== null)
            cloudinarySpan.end({ output: { count: cloudinaryUrls.length } })

            const springSpan = trace.span({ name: 'CreatePost' })
            const result = await this.createPost(validListing.listing, cloudinaryUrls, seller.sellerId)
            springSpan.end({ output: result ? 'SUCCESS' : 'FAILED' })

            safeTraceUpdate(trace, { output: result ? 'SUCCESS' : 'FAILED' })
            return result ? true : false
        } catch (error) {
            console.error('Listing processing failed:', error)
            safeTraceUpdate(trace, { level: 'ERROR', statusMessage: error.message })
            return false
        } finally {
            await this.langfuse.flush()
        }
    }

    normalizeParsedListing(parsedListing, rawListingText) {
        if (!parsedListing || typeof parsedListing !== 'object') return invalidResult('invalid-listing', parsedListing)

        const title = truncate(String(parsedListing.title || '').trim(), 100)
        const price = normalizePrice(parsedListing.price)
        let description = truncate(String(parsedListing.description || '').trim(), 1000)
        const fallbackDescription = truncate(rawListingText, 1000)
        const category = normalizeCategory(parsedListing.category)

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
                category
            }
        }
    }

    async submitListingDraft({
        contact,
        phoneNumber,
        replyChatId,
        successMessage = 'Your listing has been uploaded successfully!',
        failureMessage = 'Something went wrong uploading your listing. Please try again later.',
        missingPriceMessage = 'I can tell what you are selling, but I still need a price. Send the listing again with a price, for example: "controller 15".',
        registrationMessage = `You need to register first! Visit ${this.appBaseUrl} and click Sign Up. Reply "registered" when done and I will upload your listing automatically.`,
        markConsentedOnSuccess = false,
        clearStateOnSuccess = true,
        clearStateOnFailure = true
    }) {
        const state = this.stateStore.get(phoneNumber)
        if (!state) return 'missing-state'

        const result = await this.processListing(contact, state.listing)

        if (result === true) {
            if (markConsentedOnSuccess) {
                this.consentedUsers.add(phoneNumber)
                this.persistConsentedUsers()
            }
            await this.client.sendMessage(replyChatId, successMessage)
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
            if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
            await this.client.sendMessage(replyChatId, missingPriceMessage)
            return 'missing-price'
        }

        if (clearStateOnFailure) this.stateStore.clear(phoneNumber)
        await this.client.sendMessage(replyChatId, failureMessage)
        return 'failed'
    }
}

module.exports = ListingSubmissionService
