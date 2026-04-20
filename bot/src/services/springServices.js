const axios = require('axios');
require('dotenv').config()

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
            expiresAt: new Date(Date.now() + 48 * 60 * 60 * 1000).toISOString()

        })
        return response.data
    }
    catch(error) {
    console.log(error)
    }
}

async function getSellerByPhone(phoneNumber) {
    try {
        const response = await axios.get(`${process.env.SPRING_BASE_URL}/api/sellers`, {
            params: { phone: phoneNumber }
        })
        return response.data
    } catch (error) {
        console.error('Seller not found:', error)
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