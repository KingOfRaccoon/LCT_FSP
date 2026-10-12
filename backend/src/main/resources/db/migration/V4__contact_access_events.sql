CREATE TABLE contact_access_events (
    id uuid PRIMARY KEY,
    candidate_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    actor_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grant_id uuid NOT NULL REFERENCES contact_grants(id) ON DELETE CASCADE,
    kind varchar(32) NOT NULL CHECK (kind IN ('contact_view', 'profile_export')),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX contact_access_events_candidate_page_idx
    ON contact_access_events(candidate_id, occurred_at DESC, id DESC);
