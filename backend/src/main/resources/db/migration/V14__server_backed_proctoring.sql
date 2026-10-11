ALTER TABLE operations_grants DROP CONSTRAINT operations_grants_permission_check;
ALTER TABLE operations_grants ADD CONSTRAINT operations_grants_permission_check
    CHECK (permission IN ('company_review', 'monitor_read', 'proctoring_review'));

CREATE TABLE proctoring_sessions (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL UNIQUE REFERENCES attempts(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    status varchar(24) NOT NULL CHECK (status IN (
        'recording', 'sealed', 'review_pending', 'approved', 'invalidated',
        'technical_failure', 'revoked', 'expired'
    )),
    consent_version varchar(40) NOT NULL,
    consent_document_sha256 char(64) NOT NULL CHECK (consent_document_sha256 ~ '^[0-9a-f]{64}$'),
    consent_accepted_at timestamptz NOT NULL DEFAULT now(),
    retention_until timestamptz NOT NULL,
    events_count integer NOT NULL DEFAULT 0 CHECK (events_count BETWEEN 0 AND 2000),
    provisional_result jsonb,
    decision_outcome varchar(16) CHECK (decision_outcome IN ('uphold', 'invalidate', 'technical')),
    decision_reason_code varchar(40),
    decision_note varchar(2000),
    decided_by uuid REFERENCES users(id),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX proctoring_sessions_review_idx ON proctoring_sessions(created_at, id)
    WHERE status = 'review_pending';
CREATE INDEX proctoring_sessions_expiry_idx ON proctoring_sessions(retention_until)
    WHERE status NOT IN ('revoked', 'expired');

CREATE TABLE proctoring_captures (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES proctoring_sessions(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL CHECK (status IN ('recording', 'sealed', 'interrupted')),
    started_at timestamptz NOT NULL DEFAULT now(),
    ended_at timestamptz,
    UNIQUE (session_id, id)
);
CREATE INDEX proctoring_captures_session_idx ON proctoring_captures(session_id, started_at, id);

CREATE TABLE proctoring_media_receipts (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES proctoring_sessions(id) ON DELETE CASCADE,
    capture_id uuid NOT NULL REFERENCES proctoring_captures(id) ON DELETE CASCADE,
    channel varchar(12) NOT NULL CHECK (channel IN ('camera', 'screen')),
    sequence integer NOT NULL CHECK (sequence >= 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    byte_count integer NOT NULL CHECK (byte_count BETWEEN 1 AND 4194304),
    idempotency_key_hash char(64) NOT NULL CHECK (idempotency_key_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (capture_id, channel, sequence),
    UNIQUE (session_id, idempotency_key_hash)
);
CREATE INDEX proctoring_media_session_idx ON proctoring_media_receipts(session_id, capture_id, channel, sequence);

CREATE TABLE proctoring_client_events (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES proctoring_sessions(id) ON DELETE CASCADE,
    event_id uuid NOT NULL,
    event_type varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, event_id)
);

CREATE TABLE proctoring_audit_events (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES proctoring_sessions(id) ON DELETE CASCADE,
    actor_user_id uuid REFERENCES users(id),
    action varchar(40) NOT NULL,
    outcome varchar(40),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX proctoring_audit_session_idx ON proctoring_audit_events(session_id, created_at, id);
