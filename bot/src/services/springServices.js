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

module.exports = {createPost}