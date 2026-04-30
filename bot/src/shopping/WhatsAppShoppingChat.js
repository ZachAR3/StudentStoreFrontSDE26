const DEFAULT_PAGE_SIZE = 5
const DEFAULT_SESSION_TTL_MS = 30 * 60 * 1000
const CATEGORY_NAMES = [
    'ELECTRONICS',
    'BOOKS',
    'CLOTHING',
    'FURNITURE',
    'SPORTS',
    'FOOD',
    'SERVICES',
    'OTHER'
]

class WhatsAppShoppingChat {
    constructor(client, options = {}) {
        this.client = client
        this.postService = options.postService
        this.pageSize = Number(options.pageSize || process.env.SHOPPING_PAGE_SIZE || DEFAULT_PAGE_SIZE)
        this.appBaseUrl = options.appBaseUrl || process.env.APP_BASE_URL || 'http://localhost:8080'
        this.sessionTtlMs = Number(options.sessionTtlMs || DEFAULT_SESSION_TTL_MS)
        this.sessions = new Map()
        this.messageToSession = new Map()

        setInterval(() => this.cleanupExpiredSessions(), Math.min(this.sessionTtlMs, 5 * 60 * 1000))
    }

    async handleMessage(msg, contact) {
        if (msg.fromMe || !this.isDirectMessage(msg)) return false

        const chatId = msg.from
        const command = this.parseCommand(msg.body)
        if (!command) return false

        switch (command.type) {
            case 'help':
                await this.showHelp(chatId)
                return true
            case 'categories':
                await this.showCategories(chatId)
                return true
            case 'recent':
                await this.showRecent(chatId)
                return true
            case 'search':
                await this.showSearch(chatId, command.query)
                return true
            case 'category':
                await this.showCategory(chatId, command.category)
                return true
            case 'next':
                await this.movePage(chatId, 1)
                return true
            case 'prev':
                await this.movePage(chatId, -1)
                return true
            case 'details':
                await this.sendItemDetails(chatId, command.itemRef)
                return true
            case 'select':
                await this.sendSellerLink(chatId, command.itemRef)
                return true
            case 'stop':
                this.clearSession(chatId)
                await this.client.sendMessage(chatId, 'Shopping session closed. Send "shop" whenever you want to browse again.')
                return true
            default:
                return false
        }
    }

    async handleReaction(reaction) {
        const emoji = reaction.reaction
        if (emoji !== '➡️' && emoji !== '⬅️') return false

        const messageId = this.getSerializedMessageId(reaction.msgId)
        const chatId = this.messageToSession.get(messageId)
        if (!chatId) return false

        await this.movePage(chatId, emoji === '➡️' ? 1 : -1)
        return true
    }

    parseCommand(body = '') {
        const text = body.trim()
        const normalized = text.replace(/\s+/g, ' ')
        const lower = normalized.toLowerCase()

        if (!lower) return null
        if (['shop', '/shop', 'browse', '/browse', 'recent', '/recent'].includes(lower)) return { type: 'recent' }
        if (['help', '/help', 'menu', '/menu'].includes(lower)) return { type: 'help' }
        if (['categories', '/categories'].includes(lower)) return { type: 'categories' }
        if (['next', '/next'].includes(lower)) return { type: 'next' }
        if (['prev', 'previous', '/prev', '/previous'].includes(lower)) return { type: 'prev' }
        if (['stop', '/stop', 'cancel', '/cancel'].includes(lower)) return { type: 'stop' }

        const searchMatch = normalized.match(/^\/?search\s+(.+)$/i)
        if (searchMatch) return { type: 'search', query: searchMatch[1].trim() }

        const categoryMatch = normalized.match(/^\/?category\s+(.+)$/i)
        if (categoryMatch) return { type: 'category', category: categoryMatch[1].trim() }

        const detailsMatch = normalized.match(/^\/?(details|detail|item)\s+(\d+)$/i)
        if (detailsMatch) return { type: 'details', itemRef: detailsMatch[2] }

        const contactMatch = normalized.match(/^\/?(contact|seller|buy)\s+(\d+)$/i)
        if (contactMatch) return { type: 'select', itemRef: contactMatch[2] }

        if (/^\d+$/.test(normalized)) return { type: 'select', itemRef: normalized }

        return null
    }

    isKnownCommandText(body = '') {
        return Boolean(this.parseCommand(body))
    }

    async showHelp(chatId) {
        await this.client.sendMessage(chatId, [
            'StudentStoreFront shopping',
            '',
            'Commands:',
            'shop - view recent listings',
            'search desk - search listings',
            'category electronics - browse a category',
            'categories - list categories',
            'details 1 - view more about an item',
            '1 - get a seller WhatsApp link',
            'next / prev - change page',
            'stop - close this shopping session'
        ].join('\n'))
    }

    async showCategories(chatId) {
        await this.client.sendMessage(chatId, `Categories:\n${CATEGORY_NAMES.map(category => `- ${this.formatCategory(category)}`).join('\n')}`)
    }

    async showRecent(chatId, page = 0) {
        const session = this.createSession({ kind: 'recent', page })
        await this.fetchAndSendPage(chatId, session)
    }

    async showSearch(chatId, query, page = 0) {
        if (!query) {
            await this.client.sendMessage(chatId, 'Send "search" followed by what you want, for example: search desk')
            return
        }

        const session = this.createSession({ kind: 'search', query, page })
        await this.fetchAndSendPage(chatId, session)
    }

    async showCategory(chatId, category, page = 0) {
        const normalizedCategory = this.normalizeCategory(category)
        if (!normalizedCategory) {
            await this.client.sendMessage(chatId, `I do not know that category yet. Try one of:\n${CATEGORY_NAMES.map(this.formatCategory).join(', ')}`)
            return
        }

        const session = this.createSession({ kind: 'category', category: normalizedCategory, page })
        await this.fetchAndSendPage(chatId, session)
    }

    async movePage(chatId, delta) {
        const session = this.sessions.get(chatId)
        if (!session) {
            await this.client.sendMessage(chatId, 'Send "shop" to start browsing listings.')
            return
        }

        const nextPage = session.page + delta
        if (nextPage < 0) {
            await this.client.sendMessage(chatId, 'You are already on the first page.')
            return
        }

        if (session.totalPages !== null && nextPage >= session.totalPages) {
            await this.client.sendMessage(chatId, 'That is the last page.')
            return
        }

        await this.fetchAndSendPage(chatId, { ...session, page: nextPage })
    }

    async fetchAndSendPage(chatId, session) {
        const pageData = await this.fetchPage(session)
        if (!pageData) {
            await this.client.sendMessage(chatId, 'I could not load listings right now. Please try again in a moment.')
            return
        }

        const items = pageData.content || []
        const updatedSession = {
            ...session,
            page: pageData.number ?? session.page,
            pageSize: pageData.size || this.pageSize,
            totalPages: pageData.totalPages || 0,
            totalElements: pageData.totalElements || 0,
            items,
            updatedAt: Date.now()
        }

        if (items.length === 0) {
            this.sessions.set(chatId, updatedSession)
            await this.client.sendMessage(chatId, this.formatEmptyState(updatedSession))
            return
        }

        await this.sendResultsPage(chatId, updatedSession)
    }

    async fetchPage(session) {
        const request = {
            query: session.query,
            category: session.category,
            page: session.page,
            size: session.pageSize || this.pageSize
        }

        if (session.kind === 'recent') {
            return this.postService.getRecentPosts(request)
        }

        return this.postService.searchPosts(request)
    }

    async sendResultsPage(chatId, session) {
        const sentMessage = await this.client.sendMessage(chatId, this.formatResultsPage(session))
        const messageId = this.getSerializedMessageId(sentMessage?.id)
        const oldMessageId = session.lastMessageId

        if (oldMessageId) this.messageToSession.delete(oldMessageId)
        if (messageId) this.messageToSession.set(messageId, chatId)

        this.sessions.set(chatId, {
            ...session,
            lastMessageId: messageId
        })
    }

    formatResultsPage(session) {
        const title = this.formatSessionTitle(session)
        const pageLabel = `Page ${session.page + 1}${session.totalPages ? `/${session.totalPages}` : ''}`
        const lines = [
            `${title} (${pageLabel})`,
            ''
        ]

        session.items.forEach((item, index) => lines.push(this.formatItemSummary(item, index + 1)))

        lines.push('')
        lines.push('Reply 1-5 for a seller link, or "details 1" for more info.')

        const controls = []
        if (session.page > 0) controls.push('⬅️')
        if (session.totalPages && session.page + 1 < session.totalPages) controls.push('➡️')
        if (controls.length > 0) lines.push(`React ${controls.join(' / ')} or type next / prev to browse.`)

        return lines.join('\n')
    }

    formatItemSummary(item, index) {
        const description = this.truncate(item.description || '', 90)
        return [
            `${index}. ${item.title} - ${this.formatPrice(item.price)}`,
            `   ${this.formatCategory(item.category)} | ${item.seller?.name || 'Unknown seller'}`,
            description ? `   ${description}` : null
        ].filter(Boolean).join('\n')
    }

    async sendItemDetails(chatId, itemRef) {
        const item = await this.resolveItem(chatId, itemRef)
        if (!item) return

        const imageUrl = item.mediaUrls?.length ? item.mediaUrls[0] : null
        const lines = [
            `${item.title}`,
            `Price: ${this.formatPrice(item.price)}`,
            `Category: ${this.formatCategory(item.category)}`,
            `Seller: ${item.seller?.name || 'Unknown seller'}`,
            '',
            item.description || 'No description provided.',
            '',
            imageUrl ? `Image: ${imageUrl}` : null,
            `Reply ${itemRef} for the seller WhatsApp link.`
        ].filter(Boolean)

        await this.client.sendMessage(chatId, lines.join('\n'))
    }

    async sendSellerLink(chatId, itemRef) {
        const item = await this.resolveItem(chatId, itemRef)
        if (!item) return

        const sellerLink = this.buildSellerWhatsAppLink(item)
        if (!sellerLink) {
            await this.client.sendMessage(chatId, 'This seller does not have a usable WhatsApp number.')
            return
        }

        await this.client.sendMessage(chatId, [
            `Message ${item.seller?.name || 'the seller'} about "${item.title}":`,
            sellerLink
        ].join('\n'))
    }

    async resolveItem(chatId, itemRef) {
        const session = this.sessions.get(chatId)
        if (!session) {
            await this.client.sendMessage(chatId, 'Send "shop" to start browsing listings.')
            return null
        }

        const numericRef = Number(itemRef)
        if (!Number.isInteger(numericRef) || numericRef < 1) {
            await this.client.sendMessage(chatId, 'Please choose an item number from the current page.')
            return null
        }

        const pageItem = session.items[numericRef - 1]
        if (pageItem) return pageItem

        const item = await this.postService.getPostById(numericRef)
        if (item) return item

        await this.client.sendMessage(chatId, 'I could not find that item. Reply with a number from the current page.')
        return null
    }

    buildSellerWhatsAppLink(item) {
        const phoneNumber = item?.seller?.phoneNumber || ''
        const digits = phoneNumber.replace(/\D/g, '')
        if (digits.length < 10) return null

        const message = `Hi, I am interested in your StudentStoreFront listing: ${item.title} (#${item.postId})`
        return `https://wa.me/${digits}?text=${encodeURIComponent(message)}`
    }

    createSession(values) {
        return {
            kind: values.kind,
            query: values.query || '',
            category: values.category || '',
            page: values.page || 0,
            pageSize: this.pageSize,
            totalPages: null,
            totalElements: null,
            items: [],
            lastMessageId: null,
            updatedAt: Date.now()
        }
    }

    clearSession(chatId) {
        const session = this.sessions.get(chatId)
        if (session?.lastMessageId) this.messageToSession.delete(session.lastMessageId)
        this.sessions.delete(chatId)
    }

    cleanupExpiredSessions() {
        const cutoff = Date.now() - this.sessionTtlMs
        this.sessions.forEach((session, chatId) => {
            if (session.updatedAt < cutoff) this.clearSession(chatId)
        })
    }

    formatEmptyState(session) {
        if (session.kind === 'search') return `No available listings matched "${session.query}". Try a shorter search.`
        if (session.kind === 'category') return `No available listings in ${this.formatCategory(session.category)} yet.`
        return 'No available listings right now. Try again later.'
    }

    formatSessionTitle(session) {
        if (session.kind === 'search') return `Search results for "${session.query}"`
        if (session.kind === 'category') return `${this.formatCategory(session.category)} listings`
        return 'Recent listings'
    }

    normalizeCategory(category = '') {
        const normalized = category.trim().replace(/[\s-]+/g, '_').toUpperCase()
        return CATEGORY_NAMES.includes(normalized) ? normalized : null
    }

    formatCategory(category = '') {
        return category
            .toString()
            .toLowerCase()
            .replace(/_/g, ' ')
            .replace(/\b\w/g, letter => letter.toUpperCase())
    }

    formatPrice(price) {
        if (price === null || price === undefined || price === '') return 'Price not listed'
        return `${price} EUR`
    }

    truncate(text, maxLength) {
        if (text.length <= maxLength) return text
        return `${text.slice(0, maxLength - 3).trim()}...`
    }

    getSerializedMessageId(id) {
        if (!id) return null
        if (typeof id === 'string') return id
        return id._serialized || id.id || null
    }

    isDirectMessage(msg) {
        return msg.from.endsWith('@c.us') || msg.from.endsWith('@lid')
    }
}

module.exports = WhatsAppShoppingChat
