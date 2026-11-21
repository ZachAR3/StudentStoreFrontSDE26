CREATE TABLE IF NOT EXISTS notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    dedupe_key VARCHAR(320) NOT NULL UNIQUE,
    type VARCHAR(40) NOT NULL CHECK (type IN (
        'EMAIL_VERIFICATION',
        'EMAIL_PASSWORD_RESET',
        'BOT_USER_REGISTERED',
        'BOT_REVIEW_REQUEST',
        'BOT_RENEWAL_REMINDER'
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

CREATE INDEX IF NOT EXISTS idx_notification_outbox_due
    ON notification_outbox(sent_at, next_attempt_at);
CREATE INDEX IF NOT EXISTS idx_notification_outbox_created_at
    ON notification_outbox(created_at);
