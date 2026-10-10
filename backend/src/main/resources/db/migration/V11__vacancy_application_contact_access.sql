CREATE TABLE vacancy_application_contact_access (
    application_id uuid PRIMARY KEY REFERENCES candidate_applications(id) ON DELETE CASCADE,
    channel_types jsonb NOT NULL CHECK (jsonb_typeof(channel_types) = 'array'),
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);

CREATE INDEX vacancy_application_contact_access_active_idx
    ON vacancy_application_contact_access(application_id)
    WHERE revoked_at IS NULL;
