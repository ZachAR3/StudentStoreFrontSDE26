const fs = require('fs')

class BotStateStore {
    constructor({ filePath, listingExpiryHours }) {
        this.filePath = filePath
        this.listingExpiryMs = listingExpiryHours * 60 * 60 * 1000
        this.userState = new Map()
        this.load()
    }

    createListingDraft() {
        return {
            imageUrls: [],
            messages: [],
            createdAt: Date.now(),
            isListing: false
        }
    }

    createState() {
        return {
            listing: this.createListingDraft(),
            mode: null,
            consentPending: false,
            registrationPending: false,
            timer: null
        }
    }

    load() {
        try {
            const saved = JSON.parse(fs.readFileSync(this.filePath, 'utf8'))
            let droppedStaleDrafts = 0
            Object.entries(saved).forEach(([phone, state]) => {
                if (state?.mode === 'dm-post' && !state.consentPending && !state.registrationPending) {
                    droppedStaleDrafts += 1
                    return
                }

                this.userState.set(phone, {
                    ...this.createState(),
                    ...state,
                    listing: { ...this.createListingDraft(), ...(state.listing || {}) },
                    timer: null
                })
            })
            if (this.userState.size > 0) {
                console.log(`Restored ${this.userState.size} user state(s) from disk`)
            }
            if (droppedStaleDrafts > 0) {
                console.log(`Dropped ${droppedStaleDrafts} stale DM post draft(s) from disk`)
                this.save()
            }
        } catch (_) {}
    }

    save() {
        const serializable = {}
        this.userState.forEach((state, phone) => {
            serializable[phone] = {
                listing: state.listing,
                mode: state.mode || null,
                consentPending: state.consentPending,
                registrationPending: state.registrationPending
            }
        })

        try {
            fs.writeFileSync(this.filePath, JSON.stringify(serializable, null, 2))
        } catch (error) {
            console.error('Error saving userState:', error)
        }
    }

    get(phoneNumber) {
        return this.userState.get(phoneNumber)
    }

    has(phoneNumber) {
        return this.userState.has(phoneNumber)
    }

    ensure(phoneNumber) {
        if (this.userState.has(phoneNumber)) return this.userState.get(phoneNumber)

        const state = this.createState()
        this.userState.set(phoneNumber, state)
        this.save()
        return state
    }

    clearTimer(phoneNumber) {
        const state = this.userState.get(phoneNumber)
        if (state?.timer) {
            clearTimeout(state.timer)
            state.timer = null
        }
    }

    setTimer(phoneNumber, timer) {
        const state = this.ensure(phoneNumber)
        this.clearTimer(phoneNumber)
        state.timer = timer
    }

    clear(phoneNumber) {
        this.clearTimer(phoneNumber)
        this.userState.delete(phoneNumber)
        this.save()
    }

    resetDraft(phoneNumber, mode = null) {
        const state = this.ensure(phoneNumber)
        state.listing = this.createListingDraft()
        state.mode = mode
        state.consentPending = false
        state.registrationPending = false
        this.clearTimer(phoneNumber)
        this.save()
        return state
    }

    appendListingText(phoneNumber, text) {
        if (!text) return
        const state = this.ensure(phoneNumber)
        state.listing.messages.push(text)
        state.listing.createdAt = Date.now()
    }

    appendListingImage(phoneNumber, image) {
        if (!image) return
        const state = this.ensure(phoneNumber)
        state.listing.imageUrls.push(image)
        state.listing.createdAt = Date.now()
    }

    markListing(phoneNumber, updates = {}) {
        const state = this.ensure(phoneNumber)
        state.listing = { ...state.listing, ...updates }
    }

    setConsentPending(phoneNumber, value) {
        this.ensure(phoneNumber).consentPending = value
    }

    setRegistrationPending(phoneNumber, value) {
        this.ensure(phoneNumber).registrationPending = value
    }

    setMode(phoneNumber, mode) {
        this.ensure(phoneNumber).mode = mode
    }

    cleanupExpiredStates() {
        let anyDeleted = false
        this.userState.forEach((state, phoneNumber) => {
            if (state.listing.createdAt + this.listingExpiryMs < Date.now()) {
                this.clearTimer(phoneNumber)
                this.userState.delete(phoneNumber)
                anyDeleted = true
                console.log('Expired listing removed for:', phoneNumber)
            }
        })
        if (anyDeleted) this.save()
        return anyDeleted
    }
}

module.exports = BotStateStore
