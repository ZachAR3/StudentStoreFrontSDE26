BEGIN;

CREATE TABLE IF NOT EXISTS sellers (
    seller_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone_number VARCHAR(16) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('SELLER', 'ADMIN')),
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS posts (
    post_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    price NUMERIC(8, 2) NOT NULL CHECK (price >= 0.01 AND price <= 999999.99),
    description VARCHAR(1000) NOT NULL CHECK (char_length(description) BETWEEN 10 AND 1000),
    category VARCHAR(32) NOT NULL CHECK (
        category IN (
            'ELECTRONICS',
            'BOOKS',
            'CLOTHING',
            'FURNITURE',
            'SPORTS',
            'FOOD',
            'SERVICES',
            'OTHER'
        )
    ),
    is_sold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    reminder_sent_at TIMESTAMP NULL,
    sold_at TIMESTAMP NULL,
    seller_id BIGINT NOT NULL REFERENCES sellers (seller_id),
    buyer_id BIGINT NULL REFERENCES sellers (seller_id),
    CHECK (buyer_id IS NULL OR buyer_id <> seller_id)
);

CREATE TABLE IF NOT EXISTS post_media (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES posts (post_id),
    media_url TEXT NOT NULL,
    image_hash VARCHAR(128) NULL,
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    is_cover BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_post_media_post_order UNIQUE (post_id, display_order)
);

CREATE TABLE IF NOT EXISTS favourites (
    id BIGSERIAL PRIMARY KEY,
    seller_id BIGINT NOT NULL REFERENCES sellers (seller_id),
    post_id BIGINT NOT NULL REFERENCES posts (post_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_favourites_seller_post UNIQUE (seller_id, post_id)
);

CREATE TABLE IF NOT EXISTS reviews (
    review_id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES posts (post_id),
    reviewer_id BIGINT NOT NULL REFERENCES sellers (seller_id),
    reviewee_id BIGINT NOT NULL REFERENCES sellers (seller_id),
    direction VARCHAR(32) NOT NULL CHECK (direction IN ('BUYER_TO_SELLER', 'SELLER_TO_BUYER')),
    rating INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment VARCHAR(500) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_review_post_direction UNIQUE (post_id, direction)
);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    token VARCHAR(255) NOT NULL UNIQUE,
    seller_id BIGINT NOT NULL REFERENCES sellers (seller_id),
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    code CHAR(6) NOT NULL,
    seller_id BIGINT NOT NULL REFERENCES sellers (seller_id),
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS whatsapp_login_sessions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    login_token UUID NOT NULL UNIQUE,
    claim_token UUID NULL UNIQUE,
    status VARCHAR(32) NOT NULL CHECK (
        status IN ('PENDING', 'COMPLETED', 'PHONE_NOT_LINKED', 'EXPIRED', 'CLAIMED')
    ),
    seller_id BIGINT NULL REFERENCES sellers (seller_id),
    phone_number VARCHAR(16) NULL,
    creator_ip VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NULL,
    claimed_at TIMESTAMP NULL
);

-- Core listing read paths
CREATE INDEX IF NOT EXISTS idx_posts_public_listing
    ON posts (created_at DESC)
    WHERE status = 'ACTIVE' AND is_sold = FALSE;

CREATE INDEX IF NOT EXISTS idx_posts_public_category
    ON posts (category, created_at DESC)
    WHERE status = 'ACTIVE' AND is_sold = FALSE;

-- Scheduler window scans
CREATE INDEX IF NOT EXISTS idx_posts_expiration_window
    ON posts (expires_at)
    WHERE status = 'ACTIVE' AND reminder_sent_at IS NULL;

-- Relationship lookups
CREATE INDEX IF NOT EXISTS idx_posts_seller_id ON posts (seller_id);
CREATE INDEX IF NOT EXISTS idx_posts_buyer_id ON posts (buyer_id);
CREATE INDEX IF NOT EXISTS idx_post_media_post_order ON post_media (post_id, display_order);
CREATE INDEX IF NOT EXISTS idx_favourites_seller_id ON favourites (seller_id);
CREATE INDEX IF NOT EXISTS idx_favourites_post_id ON favourites (post_id);
CREATE INDEX IF NOT EXISTS idx_reviews_reviewee_direction_created_at
    ON reviews (reviewee_id, direction, created_at DESC);

-- Token and session lookups
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_seller_used_expires
    ON password_reset_tokens (seller_id, used, expires_at);

CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_seller_used_expires
    ON email_verification_tokens (seller_id, used, expires_at);

CREATE INDEX IF NOT EXISTS idx_whatsapp_login_sessions_status_expires
    ON whatsapp_login_sessions (status, expires_at);

CREATE INDEX IF NOT EXISTS idx_whatsapp_login_sessions_seller_id
    ON whatsapp_login_sessions (seller_id);

-- Optional scale-up optimization for LIKE-based search.
-- Enable when working with larger datasets:
-- CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- CREATE INDEX idx_posts_search_trgm
--     ON posts USING gin (LOWER(title || ' ' || description) gin_trgm_ops);
-- CREATE INDEX idx_sellers_search_trgm
--     ON sellers USING gin (LOWER(name || ' ' || email) gin_trgm_ops);

COMMIT;
