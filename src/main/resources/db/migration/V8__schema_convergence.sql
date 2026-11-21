-- V2 was restored to its originally released content so databases that already
-- recorded it keep a valid Flyway checksum. This migration adds the remaining
-- schema pieces that a later, unreleased edit of V2 had introduced, so every
-- installation converges on the same shape.

ALTER TABLE users ALTER COLUMN role SET DEFAULT 'USER';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_whatsapp_status'
          AND conrelid = 'whatsapp_login_sessions'::regclass
    ) THEN
        ALTER TABLE whatsapp_login_sessions
            ADD CONSTRAINT chk_whatsapp_status
            CHECK (status IN ('PENDING', 'COMPLETED', 'PHONE_NOT_LINKED', 'EXPIRED', 'CLAIMED'));
    END IF;
END $$;

-- Media is always read in display order; the composite index replaces the
-- single-column one it makes redundant.
CREATE INDEX IF NOT EXISTS idx_post_media_post_order ON post_media (post_id, display_order, id);
DROP INDEX IF EXISTS idx_post_media_post_id;

CREATE INDEX IF NOT EXISTS idx_reviews_reviewer_user_id ON reviews (reviewer_user_id);
CREATE INDEX IF NOT EXISTS idx_whatsapp_sessions_user_id ON whatsapp_login_sessions (user_id);
