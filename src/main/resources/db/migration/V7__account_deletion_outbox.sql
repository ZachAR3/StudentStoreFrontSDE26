-- Account deletion must clear bot-local drafts and consent state after the
-- database transaction commits. Keep this change separate from V5 because V5
-- may already be applied in deployed databases.

ALTER TABLE notification_outbox
    DROP CONSTRAINT IF EXISTS notification_outbox_type_check;

ALTER TABLE notification_outbox
    ADD CONSTRAINT notification_outbox_type_check CHECK (type IN (
        'EMAIL_VERIFICATION',
        'EMAIL_PASSWORD_RESET',
        'BOT_USER_REGISTERED',
        'BOT_REVIEW_REQUEST',
        'BOT_RENEWAL_REMINDER',
        'BOT_ACCOUNT_DELETED'
    ));

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_pending_email
    ON users (lower(pending_email))
    WHERE pending_email IS NOT NULL;
