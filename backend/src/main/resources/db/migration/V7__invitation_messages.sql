CREATE TABLE invitation_messages (
    id uuid PRIMARY KEY,
    invitation_id uuid NOT NULL REFERENCES invitations(id) ON DELETE CASCADE,
    sender_user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    sender_role varchar(20) NOT NULL CHECK (sender_role IN ('candidate', 'employer')),
    body text NOT NULL CHECK (char_length(body) BETWEEN 1 AND 2000 AND length(btrim(body)) > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX invitation_messages_page_idx
    ON invitation_messages(invitation_id, created_at DESC, id DESC);
