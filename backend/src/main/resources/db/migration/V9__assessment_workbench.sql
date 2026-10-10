CREATE TABLE assessment_methodists (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    active boolean NOT NULL DEFAULT true,
    granted_at timestamptz NOT NULL DEFAULT now(),
    granted_by uuid REFERENCES users(id)
);

CREATE TABLE methodist_drafts (
    id uuid PRIMARY KEY,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    status varchar(30) NOT NULL DEFAULT 'draft'
        CHECK (status IN ('draft', 'approved', 'changes_requested', 'rejected')),
    payload jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES users(id),
    updated_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX methodist_drafts_status_idx ON methodist_drafts(status, updated_at DESC);

CREATE TABLE methodist_draft_revisions (
    draft_id uuid NOT NULL REFERENCES methodist_drafts(id) ON DELETE CASCADE,
    version integer NOT NULL CHECK (version > 0),
    payload jsonb NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (draft_id, version)
);

CREATE TABLE methodist_draft_reviews (
    id uuid PRIMARY KEY,
    draft_id uuid NOT NULL REFERENCES methodist_drafts(id) ON DELETE CASCADE,
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES users(id),
    outcome varchar(30) NOT NULL CHECK (outcome IN ('approved', 'changes_requested', 'rejected')),
    marks jsonb NOT NULL,
    comment text NOT NULL DEFAULT '',
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (draft_id, version) REFERENCES methodist_draft_revisions(draft_id, version)
);
CREATE INDEX methodist_draft_reviews_draft_idx ON methodist_draft_reviews(draft_id, version DESC);

CREATE TABLE methodist_pilot_observations (
    id uuid PRIMARY KEY,
    draft_id uuid NOT NULL REFERENCES methodist_drafts(id) ON DELETE CASCADE,
    version integer NOT NULL CHECK (version > 0),
    source varchar(30) NOT NULL CHECK (source IN ('synthetic', 'pilot_record')),
    correct boolean NOT NULL,
    actor_id uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (draft_id, version) REFERENCES methodist_draft_revisions(draft_id, version)
);
CREATE INDEX methodist_pilot_observations_version_idx ON methodist_pilot_observations(draft_id, version, source);
