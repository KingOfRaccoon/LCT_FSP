CREATE TABLE rate_limit_events (
    id bigserial PRIMARY KEY,
    bucket_key varchar(128) NOT NULL,
    occurred_at timestamptz NOT NULL
);
CREATE INDEX rate_limit_events_bucket_time ON rate_limit_events(bucket_key,occurred_at);
CREATE TABLE rate_limit_blocks (
    bucket_key varchar(128) PRIMARY KEY,
    blocked_until timestamptz NOT NULL
);
-- Unconsumed links are serialized per user, including a concurrent resend.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY expires_at DESC,id DESC) AS n
    FROM email_verification WHERE used_at IS NULL
)
UPDATE email_verification SET used_at=now() WHERE id IN (SELECT id FROM ranked WHERE n>1);
CREATE UNIQUE INDEX one_unconsumed_email_token ON email_verification(user_id) WHERE used_at IS NULL;
