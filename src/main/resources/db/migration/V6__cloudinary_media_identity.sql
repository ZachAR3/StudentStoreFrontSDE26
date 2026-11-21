ALTER TABLE post_media
    ADD COLUMN IF NOT EXISTS cloudinary_public_id VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_post_media_cloudinary_public_id
    ON post_media(cloudinary_public_id)
    WHERE cloudinary_public_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_post_media_image_hash
    ON post_media(image_hash)
    WHERE image_hash IS NOT NULL;
