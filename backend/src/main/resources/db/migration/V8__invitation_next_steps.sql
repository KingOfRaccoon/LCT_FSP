CREATE TABLE invitation_next_steps (
    invitation_id uuid PRIMARY KEY REFERENCES invitations(id) ON DELETE CASCADE,
    version integer NOT NULL CHECK (version > 0),
    action varchar(500) NOT NULL CHECK (char_length(action) BETWEEN 1 AND 500 AND length(btrim(action)) > 0),
    responsible_party varchar(20) NOT NULL CHECK (responsible_party IN ('company', 'candidate')),
    due_at timestamptz,
    completed boolean NOT NULL DEFAULT false,
    updated_at timestamptz NOT NULL DEFAULT now()
);
