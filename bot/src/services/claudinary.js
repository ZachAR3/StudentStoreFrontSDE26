const { v2: cloudinary } = require('cloudinary');
require('dotenv').config()

cloudinary.config({
    secure: true,
    api_key: process.env.CLOUDINARY_API_KEY,
    api_secret: process.env.CLOUDINARY_API_SECRET,
    cloud_name: process.env.CLOUDINARY_CLOUD_NAME
});

// Log the configuration
console.log(cloudinary.config());

const uploadImage = async (imagePath) => {
        const options = {
            use_filename: true,
            unique_filename: false,
            overwrite: true,
        };

        try {
            // Upload the image
            const result = await cloudinary.uploader.upload(imagePath, options);
            console.log(result);
            return result.secure_url;
        } catch (error) {
            console.error(error);
        }
    };

module.exports = {uploadImage}

