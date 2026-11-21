const axios = require('axios');
require('dotenv').config({ path: require('path').resolve(__dirname, '../../.env') })

if (!process.env.SPRING_BASE_URL) {
    console.error('CRITICAL ERROR: SPRING_BASE_URL is not set in .env file');
}
if (!process.env.BOT_API_KEY) {
    console.error('CRITICAL ERROR: BOT_API_KEY is not set in .env file');
}

const DEFAULT_POST_SORT = 'createdAt,desc'
const AXIOS_TIMEOUT = 10000

async function createPost(geminiListing, cloudinaryUrls, userId, imageHashes = []) {
    try {
        const response = await axios.post(`${process.env.SPRING_BASE_URL}/api/posts/bot`, {
            title: geminiListing.title,
            price: geminiListing.price,
            description: geminiListing.description,
            category: geminiListing.category?.toUpperCase(),
            imageUrlList: cloudinaryUrls,
            imageHashList: imageHashes,
            userId,
            idempotencyKey: geminiListing.idempotencyKey

        }, { headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY }, timeout: AXIOS_TIMEOUT })
        return {
            ok: true,
            status: response.status,
            data: response.data
        }
    }
    catch(error) {
        const status = error.response?.status
        const responseData = error.response?.data
        console.error('Post creation failed:', {
            status,
            message: responseData?.message || error.message,
            data: responseData
        })
        return {
            ok: false,
            status,
            message: responseData?.message || error.message,
            data: responseData
        };

    }
}

async function getSellerByPhone(phoneNumber) {
    if (!phoneNumber) return null

    // Normalize: Strip everything except digits
    const digitsOnly = phoneNumber.replace(/\D/g, '')

    if (digitsOnly.length < 10 || digitsOnly.length > 15) {
        console.error('Invalid phone number format:', phoneNumber)
        return null
    }

    try {
        // Try searching with '+' prefix first (matching your current DB/DataLoader format)
        let response = await axios.get(`${process.env.SPRING_BASE_URL}/api/users/by-phone`, {
            params: { phone: `+${digitsOnly}` },
            headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY },
            timeout: AXIOS_TIMEOUT
        })

        // If not found with '+', try the raw digits (in case backend is updated)
        if (!response.data && digitsOnly !== phoneNumber) {
            response = await axios.get(`${process.env.SPRING_BASE_URL}/api/users/by-phone`, {
                params: { phone: digitsOnly },
                headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY },
                timeout: AXIOS_TIMEOUT
            })
        }

        return response.data
    } catch (error) {
        // Only log if it's not a 404 (404 is expected if user isn't registered)
        if (error.response?.status !== 404) {
            console.error('User lookup error:', error.message)
        }
        if (error.response?.status === 404) return null
        throw error
    }
}

async function renewPost(postId, phoneNumber) {
    const response = await axios.post(
        `${process.env.SPRING_BASE_URL}/api/posts/bot/renew/${postId}`,
        {},
        {
            headers: {
                'X-Bot-Api-Key': process.env.BOT_API_KEY,
                'X-Bot-Phone': phoneNumber
            },
            timeout: AXIOS_TIMEOUT
        }
    )
    return response.data
}

// Seller contact for a WhatsApp requester. The backend only answers for
// registered, phone-verified members; callers map 403/404/400 to replies.
async function getSellerContact(postId, requesterPhone) {
    const response = await axios.get(
        `${process.env.SPRING_BASE_URL}/api/posts/bot/${encodeURIComponent(postId)}/contact`,
        {
            headers: {
                'X-Bot-Api-Key': process.env.BOT_API_KEY,
                'X-Bot-Phone': requesterPhone
            },
            timeout: AXIOS_TIMEOUT
        }
    )
    return response.data
}

async function resolveMediaUrlsByHash(imageHashes = []) {
    const hashes = [...new Set(imageHashes.filter(hash => /^[a-f0-9]{64}$/i.test(hash)))]
    if (hashes.length === 0) return {}
    const response = await axios.get(`${process.env.SPRING_BASE_URL}/api/posts/bot/media/resolve`, {
        params: { hashes },
        headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY },
        timeout: AXIOS_TIMEOUT
    })
    return response.data || {}
}

async function confirmWhatsAppLogin(loginToken, phoneNumber) {
    try {
        const response = await axios.post(
            `${process.env.SPRING_BASE_URL}/api/auth/whatsapp/confirm`,
            { loginToken, phoneNumber },
            { 
                headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY },
                timeout: AXIOS_TIMEOUT
            }
        )
        return response.data.result
    } catch (error) {
        console.error('WhatsApp login confirm failed:', error.message)
        return null
    }
}

async function searchPosts({ query = '', category = '', page = 0, size = 5, sort = DEFAULT_POST_SORT } = {}) {
    try {
        const response = await axios.get(`${process.env.SPRING_BASE_URL}/api/posts/search`, {
            params: {
                q: query || undefined,
                category: category || undefined,
                page,
                size,
                sort
            },
            timeout: AXIOS_TIMEOUT
        })
        return response.data
    } catch (error) {
        console.error('Post search failed:', error.message)
        return null
    }
}

async function getRecentPosts({ page = 0, size = 5, sort = DEFAULT_POST_SORT } = {}) {
    try {
        const response = await axios.get(`${process.env.SPRING_BASE_URL}/api/posts/available`, {
            params: { page, size, sort },
            timeout: AXIOS_TIMEOUT
        })
        return response.data
    } catch (error) {
        console.error('Recent posts lookup failed:', error.message)
        return null
    }
}

async function getPostById(postId) {
    try {
        const response = await axios.get(`${process.env.SPRING_BASE_URL}/api/posts/${postId}`, {
            timeout: AXIOS_TIMEOUT
        })
        return response.data
    } catch (error) {
        if (error.response?.status !== 404) {
            console.error('Post lookup failed:', error.message)
        }
        return null
    }
}

async function getPostsBySeller(userId, { page = 0, size = 50, sort = DEFAULT_POST_SORT } = {}) {
    try {
        const response = await axios.get(`${process.env.SPRING_BASE_URL}/api/posts/user/${userId}`, {
            params: { page, size, sort },
            timeout: AXIOS_TIMEOUT
        })
        return response.data
    } catch (error) {
        console.error('User posts lookup failed:', error.message)
        return null
    }
}

module.exports = {
    createPost,
    resolveMediaUrlsByHash,
    renewPost,
    getSellerContact,
    getSellerByPhone,
    confirmWhatsAppLogin,
    searchPosts,
    getRecentPosts,
    getPostById,
    getPostsBySeller
}
