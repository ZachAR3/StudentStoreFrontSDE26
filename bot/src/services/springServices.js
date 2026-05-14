const axios = require('axios');
require('dotenv').config({ path: require('path').resolve(__dirname, '../../.env') })

if (!process.env.SPRING_BASE_URL) {
    console.error('CRITICAL ERROR: SPRING_BASE_URL is not set in .env file');
}
if (!process.env.BOT_API_KEY) {
    console.error('CRITICAL ERROR: BOT_API_KEY is not set in .env file');
}

const POST_EXPIRY_HOURS = 48
const DEFAULT_POST_SORT = 'createdAt,desc'
const AXIOS_TIMEOUT = 10000

async function createPost(geminiListing, cloudinaryUrls, userId) {
    try {
        const response = await axios.post(`${process.env.SPRING_BASE_URL}/api/posts/bot`, {
            title: geminiListing.title,
            price: geminiListing.price,
            description: geminiListing.description,
            category: geminiListing.category?.toUpperCase(),
            imageUrlList: cloudinaryUrls,
                        userId: userId,
            isSold: false,
            expiresAt: new Date(Date.now() + POST_EXPIRY_HOURS * 60 * 60 * 1000).toISOString().slice(0, 19)

        }, { headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY } })
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
        return null
    }
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
            params: { page, size, sort }
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
    getSellerByPhone,
    confirmWhatsAppLogin,
    searchPosts,
    getRecentPosts,
    getPostById,
    getPostsBySeller
}
