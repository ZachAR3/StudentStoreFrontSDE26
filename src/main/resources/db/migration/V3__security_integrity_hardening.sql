-- Hardening for installations that already recorded V2 before the runtime
-- invariants were added. Every change is additive and is guarded so fresh
-- installs and upgrades converge on the same shape.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS registration_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS pending_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMP;

UPDATE users SET registration_pending = NOT is_enabled WHERE registration_pending = FALSE AND is_enabled = FALSE;

DO $$
DECLARE constraint_name TEXT;
BEGIN
    FOR constraint_name IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = 'public' AND rel.relname = 'users' AND con.contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE public.users DROP CONSTRAINT %I', constraint_name);
    END LOOP;

    UPDATE users SET role = 'USER' WHERE role = 'SELLER';
    IF EXISTS (SELECT 1 FROM users WHERE role NOT IN ('USER', 'ADMIN')) THEN
        RAISE EXCEPTION 'Unsupported users.role value found; reconcile it before V3';
    END IF;
    ALTER TABLE users
        ADD CONSTRAINT chk_users_role CHECK (role IN ('USER', 'ADMIN')),
        ADD CONSTRAINT chk_users_version CHECK (version >= 0 AND token_version >= 0);
END $$;

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);

UPDATE posts SET status = CASE WHEN is_sold THEN 'SOLD' ELSE COALESCE(status, 'ACTIVE') END;

UPDATE whatsapp_login_sessions
SET user_id = NULL, phone_number = NULL, claim_token = NULL, completed_at = NULL, claimed_at = NULL
WHERE status = 'PENDING';

UPDATE whatsapp_login_sessions
SET status = 'EXPIRED', claim_token = NULL
WHERE status = 'COMPLETED'
  AND (user_id IS NULL OR phone_number IS NULL OR completed_at IS NULL OR claim_token IS NULL);

UPDATE whatsapp_login_sessions
SET status = 'EXPIRED', claim_token = NULL
WHERE status = 'CLAIMED'
  AND (user_id IS NULL OR phone_number IS NULL OR completed_at IS NULL OR claimed_at IS NULL);

DO $$
BEGIN
    ALTER TABLE whatsapp_login_sessions
        DROP CONSTRAINT IF EXISTS chk_whatsapp_pending_fields,
        DROP CONSTRAINT IF EXISTS chk_whatsapp_completed_fields,
        DROP CONSTRAINT IF EXISTS chk_whatsapp_claimed_fields;
    ALTER TABLE whatsapp_login_sessions ADD CONSTRAINT chk_whatsapp_pending_fields
        CHECK (status <> 'PENDING' OR (user_id IS NULL AND phone_number IS NULL AND claim_token IS NULL AND completed_at IS NULL AND claimed_at IS NULL));
    ALTER TABLE whatsapp_login_sessions ADD CONSTRAINT chk_whatsapp_completed_fields
        CHECK (status NOT IN ('COMPLETED', 'CLAIMED') OR (user_id IS NOT NULL AND phone_number IS NOT NULL AND completed_at IS NOT NULL AND claim_token IS NOT NULL));
    ALTER TABLE whatsapp_login_sessions ADD CONSTRAINT chk_whatsapp_claimed_fields
        CHECK (status <> 'CLAIMED' OR claimed_at IS NOT NULL);
END $$;

ALTER TABLE email_verification_tokens
    ADD COLUMN IF NOT EXISTS target_email VARCHAR(255),
    ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS api_rate_limit_windows (
    rate_key VARCHAR(320) PRIMARY KEY,
    window_start TIMESTAMP NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0)
);

ALTER TABLE sales
    ADD COLUMN IF NOT EXISTS title_snapshot VARCHAR(100),
    ADD COLUMN IF NOT EXISTS price_snapshot NUMERIC(10, 2);
UPDATE sales s SET title_snapshot = p.title, price_snapshot = p.price
FROM posts p WHERE p.post_id = s.post_id AND (s.title_snapshot IS NULL OR s.price_snapshot IS NULL);
ALTER TABLE sales ALTER COLUMN title_snapshot SET NOT NULL;
ALTER TABLE sales ALTER COLUMN price_snapshot SET NOT NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM posts WHERE btrim(title) = '' OR price < 0.01 OR price > 999999.99 OR char_length(description) NOT BETWEEN 10 AND 1000) THEN
        RAISE EXCEPTION 'Existing posts violate runtime integrity checks; reconcile them before V3';
    END IF;
    IF EXISTS (SELECT 1 FROM reviews WHERE rating NOT BETWEEN 1 AND 5 OR direction NOT IN ('BUYER_TO_SELLER','SELLER_TO_BUYER') OR reviewer_user_id = reviewee_user_id) THEN
        RAISE EXCEPTION 'Existing reviews violate runtime integrity checks; reconcile them before V3';
    END IF;
    IF EXISTS (SELECT 1 FROM posts WHERE category NOT IN ('ELECTRONICS','BOOKS','CLOTHING','FURNITURE','SPORTS','FOOD','SERVICES','OTHER')) THEN
        RAISE EXCEPTION 'Existing posts contain an unsupported category; reconcile them before V3';
    END IF;
    IF EXISTS (SELECT 1 FROM posts WHERE status NOT IN ('ACTIVE','ARCHIVED','SOLD')) THEN
        RAISE EXCEPTION 'Existing posts contain an unsupported status; reconcile them before V3';
    END IF;
    IF EXISTS (SELECT 1 FROM posts WHERE buyer_user_id IS NOT NULL AND buyer_user_id = seller_user_id) THEN
        RAISE EXCEPTION 'Existing posts contain a self-sale; reconcile them before V3';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM reviews r
        JOIN sales s ON s.id = r.sale_id
        WHERE (r.direction = 'BUYER_TO_SELLER' AND
               (r.reviewer_user_id IS DISTINCT FROM s.buyer_user_id OR r.reviewee_user_id IS DISTINCT FROM s.seller_user_id))
           OR (r.direction = 'SELLER_TO_BUYER' AND
               (r.reviewer_user_id IS DISTINCT FROM s.seller_user_id OR r.reviewee_user_id IS DISTINCT FROM s.buyer_user_id))
    ) THEN
        RAISE EXCEPTION 'Existing reviews do not match their sale participants; reconcile them before V3';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_price' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_price CHECK (price >= 0.01 AND price <= 999999.99);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_version' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_version CHECK (version >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_title_nonblank' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_title_nonblank CHECK (btrim(title) <> '');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_description' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_description CHECK (char_length(description) BETWEEN 10 AND 1000);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_rating' AND conrelid = 'public.reviews'::regclass) THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_direction' AND conrelid = 'public.reviews'::regclass) THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_review_direction CHECK (direction IN ('BUYER_TO_SELLER','SELLER_TO_BUYER'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_review_distinct_users' AND conrelid = 'public.reviews'::regclass) THEN
        ALTER TABLE reviews ADD CONSTRAINT chk_review_distinct_users CHECK (reviewer_user_id <> reviewee_user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_post_media_order' AND conrelid = 'public.post_media'::regclass) THEN
        ALTER TABLE post_media ADD CONSTRAINT chk_post_media_order CHECK (display_order >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_email_verification_attempts' AND conrelid = 'public.email_verification_tokens'::regclass) THEN
        ALTER TABLE email_verification_tokens ADD CONSTRAINT chk_email_verification_attempts CHECK (attempts >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_category' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_category CHECK (category IN ('ELECTRONICS','BOOKS','CLOTHING','FURNITURE','SPORTS','FOOD','SERVICES','OTHER'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_status' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_status CHECK (status IN ('ACTIVE','ARCHIVED','SOLD'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_sale_state' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_sale_state CHECK ((status = 'SOLD') = is_sold);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_posts_buyer_distinct' AND conrelid = 'public.posts'::regclass) THEN
        ALTER TABLE posts ADD CONSTRAINT chk_posts_buyer_distinct CHECK (buyer_user_id IS NULL OR buyer_user_id <> seller_user_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_sale_distinct_users' AND conrelid = 'public.sales'::regclass) THEN
        ALTER TABLE sales ADD CONSTRAINT chk_sale_distinct_users CHECK (seller_user_id <> buyer_user_id);
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM sales s
        JOIN posts p ON p.post_id = s.post_id
        WHERE s.seller_user_id IS DISTINCT FROM p.seller_user_id
           OR s.buyer_user_id IS DISTINCT FROM p.buyer_user_id
           OR p.is_sold = FALSE
    ) THEN
        RAISE EXCEPTION 'Existing sales do not match their sold posts';
    END IF;
END $$;

CREATE OR REPLACE FUNCTION public.enforce_sale_participants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    post_seller BIGINT;
    post_buyer BIGINT;
    post_is_sold BOOLEAN;
BEGIN
    SELECT seller_user_id, buyer_user_id, is_sold
    INTO post_seller, post_buyer, post_is_sold
    FROM posts
    WHERE post_id = NEW.post_id;
    IF post_seller IS NULL
       OR post_seller IS DISTINCT FROM NEW.seller_user_id
       OR post_buyer IS DISTINCT FROM NEW.buyer_user_id
       OR post_is_sold IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Sale participants must match the sold post';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS enforce_sale_participants ON sales;
CREATE TRIGGER enforce_sale_participants
    BEFORE INSERT OR UPDATE ON sales
    FOR EACH ROW EXECUTE FUNCTION public.enforce_sale_participants();

CREATE OR REPLACE FUNCTION public.enforce_post_sale_consistency()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sale_seller BIGINT;
    sale_buyer BIGINT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (SELECT 1 FROM sales WHERE post_id = OLD.post_id) THEN
            RAISE EXCEPTION 'Sold posts cannot be deleted while transaction history exists';
        END IF;
        RETURN OLD;
    END IF;

    SELECT seller_user_id, buyer_user_id
    INTO sale_seller, sale_buyer
    FROM sales
    WHERE post_id = NEW.post_id;

    IF sale_seller IS NOT NULL AND (
        NEW.seller_user_id IS DISTINCT FROM sale_seller
        OR NEW.buyer_user_id IS DISTINCT FROM sale_buyer
        OR NEW.is_sold IS DISTINCT FROM TRUE
        OR NEW.status IS DISTINCT FROM 'SOLD'
    ) THEN
        RAISE EXCEPTION 'Post state must match its sale history';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS enforce_post_sale_consistency ON posts;
CREATE TRIGGER enforce_post_sale_consistency
    BEFORE UPDATE OR DELETE ON posts
    FOR EACH ROW EXECUTE FUNCTION public.enforce_post_sale_consistency();

CREATE OR REPLACE FUNCTION public.enforce_review_participants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sale_seller BIGINT;
    sale_buyer BIGINT;
BEGIN
    SELECT seller_user_id, buyer_user_id
    INTO sale_seller, sale_buyer
    FROM sales
    WHERE id = NEW.sale_id;
    IF NEW.direction = 'BUYER_TO_SELLER' AND
       (NEW.reviewer_user_id IS DISTINCT FROM sale_buyer OR NEW.reviewee_user_id IS DISTINCT FROM sale_seller) THEN
        RAISE EXCEPTION 'Buyer-to-seller review participants do not match the sale';
    END IF;
    IF NEW.direction = 'SELLER_TO_BUYER' AND
       (NEW.reviewer_user_id IS DISTINCT FROM sale_seller OR NEW.reviewee_user_id IS DISTINCT FROM sale_buyer) THEN
        RAISE EXCEPTION 'Seller-to-buyer review participants do not match the sale';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS enforce_review_participants ON reviews;
CREATE TRIGGER enforce_review_participants
    BEFORE INSERT OR UPDATE ON reviews
    FOR EACH ROW EXECUTE FUNCTION public.enforce_review_participants();

WITH ranked_media AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY post_id ORDER BY display_order, id) - 1 AS new_order
    FROM post_media
)
UPDATE post_media media
SET display_order = ranked_media.new_order
FROM ranked_media
WHERE media.id = ranked_media.id;

UPDATE post_media SET is_cover = FALSE;
UPDATE post_media media
SET is_cover = TRUE
FROM (
    SELECT DISTINCT ON (post_id) id
    FROM post_media
    ORDER BY post_id, display_order, id
) first_media
WHERE media.id = first_media.id;

DELETE FROM favourites left_row USING favourites duplicate_row
WHERE left_row.ctid < duplicate_row.ctid
  AND left_row.user_id = duplicate_row.user_id
  AND left_row.post_id = duplicate_row.post_id;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_whatsapp_session_user' AND conrelid = 'public.whatsapp_login_sessions'::regclass
    ) THEN
        ALTER TABLE whatsapp_login_sessions ADD CONSTRAINT fk_whatsapp_session_user
            FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_posts_seller_idempotency
    ON posts(seller_user_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_favourites_user_post
    ON favourites(user_id, post_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_post_media_post_order
    ON post_media(post_id, display_order);
CREATE UNIQUE INDEX IF NOT EXISTS uk_post_media_one_cover
    ON post_media(post_id) WHERE is_cover;
CREATE INDEX IF NOT EXISTS idx_posts_public_listing
    ON posts(created_at DESC, post_id DESC)
    WHERE status = 'ACTIVE' AND is_sold = FALSE;
CREATE INDEX IF NOT EXISTS idx_posts_public_category
    ON posts(category, created_at DESC, post_id DESC)
    WHERE status = 'ACTIVE' AND is_sold = FALSE;
CREATE INDEX IF NOT EXISTS idx_posts_expiration_window
    ON posts(expires_at, post_id)
    WHERE status = 'ACTIVE' AND is_sold = FALSE AND reminder_sent_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_favourites_post_id ON favourites(post_id);
CREATE INDEX IF NOT EXISTS idx_reviews_reviewee_direction_created_at
    ON reviews(reviewee_user_id, direction, created_at DESC, review_id DESC);
CREATE INDEX IF NOT EXISTS idx_password_reset_user_expiry ON password_reset_tokens(user_id, expires_at);
CREATE INDEX IF NOT EXISTS idx_email_verification_user_state ON email_verification_tokens(user_id, used, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_whatsapp_sessions_status_expiry ON whatsapp_login_sessions(status, expires_at);

SELECT setval(
    pg_get_serial_sequence('reviews', 'review_id'),
    COALESCE((SELECT MAX(review_id) FROM reviews), 1),
    (SELECT COUNT(*) > 0 FROM reviews)
);
