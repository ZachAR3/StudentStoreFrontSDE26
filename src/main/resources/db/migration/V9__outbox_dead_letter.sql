-- Outbox rows that exhaust their retries or outlive their usefulness are
-- dead-lettered instead of being retried forever.
ALTER TABLE notification_outbox
    ADD COLUMN IF NOT EXISTS failed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_notification_outbox_pending
    ON notification_outbox (next_attempt_at)
    WHERE sent_at IS NULL AND failed_at IS NULL;
