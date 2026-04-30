const fs = require('fs/promises')
const os = require('os')
const path = require('path')
const { randomUUID } = require('crypto')
const { execFile } = require('child_process')
const { promisify } = require('util')

const execFileAsync = promisify(execFile)
const DEFAULT_MAX_WIDTH = 960
const DEFAULT_MAX_HEIGHT = 720
const DEFAULT_QUALITY = 82

function extensionForMimeType(mimetype = '') {
    if (mimetype.includes('png')) return 'png'
    if (mimetype.includes('webp')) return 'webp'
    if (mimetype.includes('gif')) return 'gif'
    return 'jpg'
}

function isSupportedImage(image) {
    return image?.data && image?.mimetype?.startsWith('image/')
}

async function downscaleImageForLlm(image, options = {}) {
    if (!isSupportedImage(image)) return image

    const maxWidth = Number(options.maxWidth || process.env.LISTING_PARSE_IMAGE_MAX_WIDTH || DEFAULT_MAX_WIDTH)
    const maxHeight = Number(options.maxHeight || process.env.LISTING_PARSE_IMAGE_MAX_HEIGHT || DEFAULT_MAX_HEIGHT)
    const quality = Number(options.quality || process.env.LISTING_PARSE_IMAGE_QUALITY || DEFAULT_QUALITY)
    const id = randomUUID()
    const inputPath = path.join(os.tmpdir(), `ssf-listing-${id}.${extensionForMimeType(image.mimetype)}`)
    const outputPath = path.join(os.tmpdir(), `ssf-listing-${id}.jpg`)

    try {
        await fs.writeFile(inputPath, Buffer.from(image.data, 'base64'))
        await execFileAsync('magick', [
            inputPath,
            '-auto-orient',
            '-resize',
            `${maxWidth}x${maxHeight}>`,
            '-background',
            'white',
            '-alpha',
            'remove',
            '-alpha',
            'off',
            '-strip',
            '-quality',
            String(quality),
            outputPath
        ])

        return {
            data: (await fs.readFile(outputPath)).toString('base64'),
            mimetype: 'image/jpeg'
        }
    } catch (error) {
        console.error('Failed to downscale listing image for LLM:', error.message)
        return image
    } finally {
        await Promise.allSettled([
            fs.unlink(inputPath),
            fs.unlink(outputPath)
        ])
    }
}

async function downscaleImagesForLlm(images = [], options = {}) {
    return Promise.all(images.map(image => downscaleImageForLlm(image, options)))
}

module.exports = {
    downscaleImageForLlm,
    downscaleImagesForLlm
}
