const axios = require('axios');
require('dotenv').config()

const POST_EXPIRY_HOURS = 48

async function createPost(geminiListing, cloudinaryUrls, sellerId) {
    try {
        const response = await axios.post(`${process.env.SPRING_BASE_URL}/api/posts`, {
            title: geminiListing.title,
            price: geminiListing.price,
            description: geminiListing.description,
            category: geminiListing.category,
            imageUrlList: cloudinaryUrls,
            sellerId: sellerId,
            isSold: false,
            expiresAt: new Date(Date.now() + POST_EXPIRY_HOURS * 60 * 60 * 1000).toISOString().slice(0, 19)

        }, { headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY } })
        return response.data
    }
    catch(error) {
        
     console.error('Post creation failed:', error); 
     return false;

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
        let response = await axios.get(`${process.env.SPRING_BASE_URL}/api/sellers/by-phone`, {
            params: { phone: `+${digitsOnly}` }
        })

        // If not found with '+', try the raw digits (in case backend is updated)
        if (!response.data && digitsOnly !== phoneNumber) {
            response = await axios.get(`${process.env.SPRING_BASE_URL}/api/sellers/by-phone`, {
                params: { phone: digitsOnly }
            })
        }

        return response.data
    } catch (error) {
        // Only log if it's not a 404 (404 is expected if seller isn't registered)
        if (error.response?.status !== 404) {
            console.error('Seller lookup error:', error.message)
        }
        return null
    }
}

async function confirmWhatsAppLogin(loginToken, phoneNumber) {
    try {
        const response = await axios.post(
            `${process.env.SPRING_BASE_URL}/api/auth/whatsapp/confirm`,
            { loginToken, phoneNumber },
            { headers: { 'X-Bot-Api-Key': process.env.BOT_API_KEY } }
        )
        return response.data.result
    } catch (error) {
        console.error('WhatsApp login confirm failed:', error.message)
        return null
    }
}

module.exports = { createPost, getSellerByPhone, confirmWhatsAppLogin }