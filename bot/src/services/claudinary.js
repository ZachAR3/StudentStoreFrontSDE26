const { v2: cloudinary } = require('cloudinary');
require('dotenv').config()

cloudinary.config({
    secure: true,
    api_key: process.env.CLOUDINARY_API_KEY,
    api_secret: process.env.CLOUDINARY_API_SECRET,
    cloud_name: process.env.CLOUDINARY_CLOUD_NAME
});

const uploadImage = async (imagePath) => {
        const options = {
            use_filename: true,
            unique_filename: true,
            overwrite: false,
        };

        try {
            // Upload the image
            const result = await cloudinary.uploader.upload(imagePath, options);
            console.log(result);
            return result.secure_url;
        } catch (error) {
            console.error('Error uploading image:', error);
            return null
        }
    };

module.exports = {uploadImage}

