CREATE TABLE consent_events (
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    scope varchar(32) NOT NULL CHECK (scope IN ('processing', 'publication')),
    version varchar(32) NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('accept', 'revoke')),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX consent_events_actor_page_idx
    ON consent_events(actor_user_id, sequence DESC);
