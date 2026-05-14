ALTER TABLE IF EXISTS sellers RENAME TO users;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'seller_id'
    ) THEN
        ALTER TABLE users RENAME COLUMN seller_id TO user_id;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS users (
    user_id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone_number VARCHAR(32) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(16) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS role VARCHAR(16) NOT NULL DEFAULT 'USER',
    ADD COLUMN IF NOT EXISTS is_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'posts' AND column_name = 'seller_id'
    ) THEN
        ALTER TABLE posts RENAME COLUMN seller_id TO seller_user_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'posts' AND column_name = 'buyer_id'
    ) THEN
        ALTER TABLE posts RENAME COLUMN buyer_id TO buyer_user_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'favourites' AND column_name = 'seller_id'
    ) THEN
        ALTER TABLE favourites RENAME COLUMN seller_id TO user_id;
    END IF;
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'whatsapp_login_sessions' AND column_name = 'seller_id'
    ) THEN
        ALTER TABLE whatsapp_login_sessions RENAME COLUMN seller_id TO user_id;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS posts (
    post_id BIGSERIAL PRIMARY KEY,
    title VARCHAR(100) NOT NULL,
    price NUMERIC(10, 2) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    category VARCHAR(32) NOT NULL,
    is_sold BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reminder_sent_at TIMESTAMP,
    sold_at TIMESTAMP,
    seller_user_id BIGINT NOT NULL REFERENCES users(user_id),
    buyer_user_id BIGINT REFERENCES users(user_id)
);

CREATE TABLE IF NOT EXISTS post_media (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL REFERENCES posts(post_id) ON DELETE CASCADE,
    media_url TEXT NOT NULL,
    image_hash VARCHAR(255),
    display_order INTEGER NOT NULL DEFAULT 0,
    is_cover BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS favourites (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    post_id BIGINT NOT NULL REFERENCES posts(post_id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS whatsapp_login_sessions (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL UNIQUE,
    login_token UUID NOT NULL UNIQUE,
    claim_token UUID UNIQUE,
    status VARCHAR(32) NOT NULL,
    user_id BIGINT,
    phone_number VARCHAR(32),
    creator_ip VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    claimed_at TIMESTAMP
);

DROP TABLE IF EXISTS password_reset_tokens;
CREATE TABLE password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS email_verification_tokens;
CREATE TABLE email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    code_hash VARCHAR(255) NOT NULL,
    user_id BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sales (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT NOT NULL UNIQUE REFERENCES posts(post_id) ON DELETE CASCADE,
    seller_user_id BIGINT NOT NULL REFERENCES users(user_id),
    buyer_user_id BIGINT NOT NULL REFERENCES users(user_id),
    sold_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_sale_distinct_users CHECK (seller_user_id <> buyer_user_id)
);

INSERT INTO sales (post_id, seller_user_id, buyer_user_id, sold_at, created_at)
SELECT p.post_id, p.seller_user_id, p.buyer_user_id, COALESCE(p.sold_at, p.created_at), CURRENT_TIMESTAMP
FROM posts p
WHERE p.is_sold = TRUE
  AND p.buyer_user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM sales s WHERE s.post_id = p.post_id);

UPDATE posts
SET status = 'SOLD'
WHERE is_sold = TRUE;

ALTER TABLE IF EXISTS reviews RENAME TO reviews_legacy;

CREATE TABLE IF NOT EXISTS reviews (
    review_id BIGSERIAL PRIMARY KEY,
    sale_id BIGINT NOT NULL REFERENCES sales(id) ON DELETE CASCADE,
    reviewer_user_id BIGINT NOT NULL REFERENCES users(user_id),
    reviewee_user_id BIGINT NOT NULL REFERENCES users(user_id),
    direction VARCHAR(32) NOT NULL,
    rating INTEGER NOT NULL,
    comment VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_review_sale_direction UNIQUE (sale_id, direction)
);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_name = 'reviews_legacy'
    ) THEN
        INSERT INTO reviews (review_id, sale_id, reviewer_user_id, reviewee_user_id, direction, rating, comment, created_at)
        SELECT rl.review_id, s.id, rl.reviewer_id, rl.reviewee_id, rl.direction, rl.rating, rl.comment, rl.created_at
        FROM reviews_legacy rl
        JOIN sales s ON s.post_id = rl.post_id
        ON CONFLICT DO NOTHING;
    END IF;
END $$;

DROP TABLE IF EXISTS reviews_legacy;

ALTER TABLE IF EXISTS post_media
    ADD COLUMN IF NOT EXISTS image_hash VARCHAR(255),
    ADD COLUMN IF NOT EXISTS is_cover BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS display_order INTEGER NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uk_favourites_user_post'
    ) THEN
        ALTER TABLE favourites
            ADD CONSTRAINT uk_favourites_user_post UNIQUE (user_id, post_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_posts_seller_user_id ON posts(seller_user_id);
CREATE INDEX IF NOT EXISTS idx_posts_buyer_user_id ON posts(buyer_user_id);
CREATE INDEX IF NOT EXISTS idx_posts_status_is_sold ON posts(status, is_sold);
CREATE INDEX IF NOT EXISTS idx_post_media_post_id ON post_media(post_id);
CREATE INDEX IF NOT EXISTS idx_sales_seller_user_id ON sales(seller_user_id);
CREATE INDEX IF NOT EXISTS idx_sales_buyer_user_id ON sales(buyer_user_id);
