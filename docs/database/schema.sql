-- Canonical PostgreSQL schema used by the Spring/Flyway runtime.
-- Flyway remains the upgrade path; this file is a clean-install reference.

BEGIN;

CREATE TABLE users (
    user_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone_number VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER'
        CHECK (role IN ('USER', 'ADMIN')),
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    registration_pending BOOLEAN NOT NULL DEFAULT FALSE,
    token_version BIGINT NOT NULL DEFAULT 0,
    pending_email VARCHAR(255),
    phone_verified_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_users_version CHECK (version >= 0 AND token_version >= 0)
);

CREATE TABLE posts (
    post_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    price NUMERIC(10, 2) NOT NULL
        CHECK (price >= 0.01 AND price <= 999999.99),
    description VARCHAR(1000) NOT NULL
        CHECK (char_length(description) BETWEEN 10 AND 1000),
    category VARCHAR(32) NOT NULL
        CHECK (category IN ('ELECTRONICS','BOOKS','CLOTHING','FURNITURE','SPORTS','FOOD','SERVICES','OTHER')),
    is_sold BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ARCHIVED', 'SOLD')),
    reminder_sent_at TIMESTAMP,
    sold_at TIMESTAMP,
    seller_user_id BIGINT NOT NULL REFERENCES users(user_id),
    buyer_user_id BIGINT REFERENCES users(user_id),
    CONSTRAINT chk_posts_title_nonblank CHECK (btrim(title) <> ''),
    CONSTRAINT chk_posts_sale_state CHECK ((status = 'SOLD') = is_sold),
    CONSTRAINT chk_posts_buyer_distinct CHECK (buyer_user_id IS NULL OR buyer_user_id <> seller_user_id)
);

CREATE TABLE post_media (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES posts(post_id) ON DELETE CASCADE,
    media_url TEXT NOT NULL,
    image_hash VARCHAR(255),
    cloudinary_public_id VARCHAR(255),
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    is_cover BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE favourites (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(post_id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favourites_user_post UNIQUE (user_id, post_id)
);

CREATE TABLE whatsapp_login_sessions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    login_token UUID NOT NULL UNIQUE,
    claim_token UUID UNIQUE,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PENDING','COMPLETED','PHONE_NOT_LINKED','EXPIRED','CLAIMED')),
    user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
    phone_number VARCHAR(32),
    creator_ip VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    claimed_at TIMESTAMP,
    CONSTRAINT chk_whatsapp_pending_fields CHECK (
        status <> 'PENDING' OR
        (user_id IS NULL AND phone_number IS NULL AND claim_token IS NULL AND completed_at IS NULL AND claimed_at IS NULL)
    ),
    CONSTRAINT chk_whatsapp_completed_fields CHECK (
        status NOT IN ('COMPLETED', 'CLAIMED') OR
        (user_id IS NOT NULL AND phone_number IS NOT NULL AND completed_at IS NOT NULL AND claim_token IS NOT NULL)
    ),
    CONSTRAINT chk_whatsapp_claimed_fields CHECK (status <> 'CLAIMED' OR claimed_at IS NOT NULL)
);

-- Only hashes are stored. Existing raw tokens are intentionally not portable
-- across migrations; users receive a fresh reset link or verification code.
CREATE TABLE password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_users_pending_email
    ON users (lower(pending_email))
    WHERE pending_email IS NOT NULL;

CREATE TABLE email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    code_hash VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    target_email VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE sales (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL UNIQUE REFERENCES posts(post_id) ON DELETE CASCADE,
    seller_user_id BIGINT NOT NULL REFERENCES users(user_id),
    buyer_user_id BIGINT NOT NULL REFERENCES users(user_id),
    sold_at TIMESTAMP NOT NULL,
    buyer_confirmed_at TIMESTAMP,
    title_snapshot VARCHAR(100) NOT NULL,
    price_snapshot NUMERIC(10, 2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sale_distinct_users CHECK (seller_user_id <> buyer_user_id)
);

CREATE TABLE reviews (
    review_id BIGSERIAL PRIMARY KEY,
    sale_id BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    reviewer_user_id BIGINT NOT NULL REFERENCES users(user_id),
    reviewee_user_id BIGINT NOT NULL REFERENCES users(user_id),
    direction VARCHAR(32) NOT NULL
        CHECK (direction IN ('BUYER_TO_SELLER', 'SELLER_TO_BUYER')),
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_review_sale_direction UNIQUE (sale_id, direction),
    CONSTRAINT chk_review_distinct_users CHECK (reviewer_user_id <> reviewee_user_id)
);

CREATE OR REPLACE FUNCTION enforce_sale_participants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    post_seller bigint;
    post_buyer bigint;
    post_is_sold boolean;
BEGIN
    SELECT seller_user_id, buyer_user_id, is_sold
    INTO post_seller, post_buyer, post_is_sold
    FROM posts WHERE post_id = NEW.post_id;
    IF post_seller IS NULL
       OR post_seller IS DISTINCT FROM NEW.seller_user_id
       OR post_buyer IS DISTINCT FROM NEW.buyer_user_id
       OR post_is_sold IS DISTINCT FROM TRUE THEN
        RAISE EXCEPTION 'Sale participants must match the sold post';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER enforce_sale_participants
    BEFORE INSERT OR UPDATE ON sales
    FOR EACH ROW EXECUTE FUNCTION enforce_sale_participants();

CREATE OR REPLACE FUNCTION enforce_post_sale_consistency()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sale_seller bigint;
    sale_buyer bigint;
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

CREATE TRIGGER enforce_post_sale_consistency
    BEFORE UPDATE OR DELETE ON posts
    FOR EACH ROW EXECUTE FUNCTION enforce_post_sale_consistency();

CREATE OR REPLACE FUNCTION enforce_review_participants()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    sale_seller bigint;
    sale_buyer bigint;
BEGIN
    SELECT seller_user_id, buyer_user_id
    INTO sale_seller, sale_buyer
    FROM sales WHERE id = NEW.sale_id;
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

CREATE TRIGGER enforce_review_participants
    BEFORE INSERT OR UPDATE ON reviews
    FOR EACH ROW EXECUTE FUNCTION enforce_review_participants();

CREATE TABLE api_rate_limit_windows (
    rate_key VARCHAR(320) PRIMARY KEY,
    window_start TIMESTAMP NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0)
);

CREATE TABLE notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    dedupe_key VARCHAR(320) NOT NULL UNIQUE,
    type VARCHAR(40) NOT NULL CHECK (type IN (
        'EMAIL_VERIFICATION',
        'EMAIL_PASSWORD_RESET',
        'BOT_USER_REGISTERED',
        'BOT_REVIEW_REQUEST',
        'BOT_RENEWAL_REMINDER',
        'BOT_ACCOUNT_DELETED'
    )),
    recipient VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    aggregate_id BIGINT,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_at TIMESTAMP,
    sent_at TIMESTAMP,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_outbox_due
    ON notification_outbox(sent_at, next_attempt_at);
CREATE INDEX idx_notification_outbox_created_at
    ON notification_outbox(created_at);

-- Migration-only records retained for explicit reconciliation of unsupported
-- legacy sales/reviews. They are not part of the application API.
CREATE TABLE migration_sale_quarantine (
    post_id BIGINT PRIMARY KEY,
    seller_user_id BIGINT,
    buyer_user_id BIGINT,
    title VARCHAR(100),
    price NUMERIC(10, 2),
    sold_at TIMESTAMP,
    reason TEXT NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE migration_review_quarantine (
    review_id BIGINT PRIMARY KEY,
    post_id BIGINT,
    reviewer_user_id BIGINT,
    reviewee_user_id BIGINT,
    direction VARCHAR(32),
    rating INTEGER,
    comment VARCHAR(500),
    created_at TIMESTAMP,
    reason TEXT NOT NULL,
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_posts_seller_idempotency
    ON posts(seller_user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX uk_post_media_post_order
    ON post_media(post_id, display_order);
CREATE UNIQUE INDEX uk_post_media_one_cover
    ON post_media(post_id)
    WHERE is_cover;

CREATE INDEX idx_posts_public_listing
    ON posts(created_at DESC, post_id DESC)
    WHERE status = 'ACTIVE' AND is_sold = FALSE;
CREATE INDEX idx_posts_public_category
    ON posts(category, created_at DESC, post_id DESC)
    WHERE status = 'ACTIVE' AND is_sold = FALSE;
CREATE INDEX idx_posts_expiration_window
    ON posts(expires_at, post_id)
    WHERE status = 'ACTIVE' AND is_sold = FALSE AND reminder_sent_at IS NULL;
CREATE INDEX idx_posts_seller_user_id ON posts(seller_user_id);
CREATE INDEX idx_posts_buyer_user_id ON posts(buyer_user_id);
CREATE INDEX idx_post_media_post_order ON post_media(post_id, display_order, id);
CREATE INDEX idx_post_media_image_hash ON post_media(image_hash) WHERE image_hash IS NOT NULL;
CREATE INDEX idx_post_media_cloudinary_public_id
    ON post_media(cloudinary_public_id)
    WHERE cloudinary_public_id IS NOT NULL;
CREATE INDEX idx_favourites_post_id ON favourites(post_id);
CREATE INDEX idx_reviews_reviewee_direction_created_at
    ON reviews(reviewee_user_id, direction, created_at DESC, review_id DESC);
CREATE INDEX idx_reviews_reviewer_user_id ON reviews(reviewer_user_id);
CREATE INDEX idx_password_reset_user_expiry ON password_reset_tokens(user_id, expires_at);
CREATE INDEX idx_email_verification_user_state ON email_verification_tokens(user_id, used, created_at DESC);
CREATE INDEX idx_whatsapp_sessions_status_expiry ON whatsapp_login_sessions(status, expires_at);
CREATE INDEX idx_whatsapp_sessions_user_id ON whatsapp_login_sessions(user_id);
CREATE INDEX idx_sales_seller_user_id ON sales(seller_user_id);
CREATE INDEX idx_sales_buyer_user_id ON sales(buyer_user_id);
CREATE INDEX idx_sales_buyer_confirmation ON sales(buyer_user_id, buyer_confirmed_at, sold_at DESC);

COMMIT;
