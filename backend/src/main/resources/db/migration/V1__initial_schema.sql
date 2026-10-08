CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL UNIQUE,
    password_hash text NOT NULL,
    role varchar(20) NOT NULL CHECK (role IN ('candidate', 'employer')),
    email_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    deleted_at timestamptz
);

CREATE TABLE sessions (
    id uuid PRIMARY KEY,
    token_hash char(64) NOT NULL UNIQUE,
    csrf_token_hash char(64) NOT NULL,
    user_id uuid REFERENCES users(id) ON DELETE CASCADE,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);
CREATE INDEX sessions_user_idx ON sessions(user_id);

CREATE TABLE email_verification (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email varchar(254) NOT NULL,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz
);
CREATE INDEX email_verification_user_idx ON email_verification(user_id);

CREATE TABLE companies (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL UNIQUE REFERENCES users(id),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    role varchar(20) NOT NULL DEFAULT 'owner',
    active boolean NOT NULL DEFAULT true,
    UNIQUE (user_id, company_id)
);
CREATE INDEX memberships_user_idx ON memberships(user_id);
CREATE INDEX memberships_company_idx ON memberships(company_id);

CREATE TABLE candidate_profiles (
    user_id uuid PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    visibility varchar(20) NOT NULL DEFAULT 'hidden',
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    contacts jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE qualifications (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    specialization_id varchar(60) NOT NULL DEFAULT 'backend-python-v1',
    grade varchar(20) NOT NULL CHECK (grade IN ('junior', 'middle')),
    evidence_attempt_id uuid,
    last_changed_at timestamptz,
    assessed_at timestamptz NOT NULL DEFAULT now(),
    policy_version varchar(40) NOT NULL DEFAULT '1.0.0',
    bank_version varchar(60) NOT NULL DEFAULT 'backend-python-v1.0',
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (user_id, specialization_id)
);
CREATE INDEX qualifications_user_idx ON qualifications(user_id);

CREATE TABLE fsp_links (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    external_id varchar(120) NOT NULL,
    binding_status varchar(40) NOT NULL DEFAULT 'demo_linked',
    source varchar(30) NOT NULL DEFAULT 'fsp_mock',
    retrieved_at timestamptz NOT NULL DEFAULT now(),
    data jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE achievements (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    source_record_id varchar(120) NOT NULL,
    validity varchar(20) NOT NULL DEFAULT 'valid',
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    UNIQUE (user_id, source_record_id)
);
CREATE INDEX achievements_user_idx ON achievements(user_id);

CREATE TABLE attempts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    specialization_id varchar(60) NOT NULL DEFAULT 'backend-python-v1',
    requested_grade varchar(20) NOT NULL CHECK (requested_grade IN ('junior', 'middle')),
    status varchar(30) NOT NULL DEFAULT 'in_progress'
        CHECK (status IN ('in_progress', 'submitted', 'evaluated', 'invalidated')),
    form_version varchar(40) NOT NULL DEFAULT 'form-v1',
    policy_version varchar(40) NOT NULL DEFAULT '1.0.0',
    bank_version varchar(60) NOT NULL DEFAULT 'backend-python-v1.0',
    rubric_version varchar(40) NOT NULL DEFAULT 'rubric-v1',
    started_at timestamptz NOT NULL DEFAULT now(),
    deadline_at timestamptz NOT NULL,
    submitted_at timestamptz,
    evaluated_at timestamptz,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    tasks jsonb NOT NULL DEFAULT '{"items":[]}'::jsonb,
    answers jsonb NOT NULL DEFAULT '{"items":[]}'::jsonb,
    result jsonb NOT NULL DEFAULT '{}'::jsonb,
    invalidity_reason text
);
CREATE INDEX attempts_user_idx ON attempts(user_id);
CREATE UNIQUE INDEX one_active_attempt ON attempts(user_id, specialization_id)
    WHERE status IN ('in_progress', 'submitted');

ALTER TABLE qualifications ADD CONSTRAINT qualification_attempt_fk
    FOREIGN KEY (evidence_attempt_id) REFERENCES attempts(id);

CREATE TABLE task_exposures (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL REFERENCES attempts(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    family_id varchar(100) NOT NULL,
    variant_id varchar(100) NOT NULL,
    issued_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (attempt_id, family_id)
);
CREATE INDEX task_exposures_user_idx ON task_exposures(user_id);
CREATE INDEX task_exposures_family_idx ON task_exposures(family_id);
CREATE INDEX task_exposures_variant_idx ON task_exposures(variant_id);

CREATE TABLE employer_needs (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    status varchar(20) NOT NULL DEFAULT 'draft',
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    confirmed_at timestamptz,
    confirmed_by uuid REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX employer_needs_company_idx ON employer_needs(company_id);

CREATE TABLE shortlists (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX shortlists_company_idx ON shortlists(company_id);

CREATE TABLE invitations (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    candidate_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    need_id uuid REFERENCES employer_needs(id),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    state varchar(30) NOT NULL DEFAULT 'pending',
    snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    contact_grant_id uuid
);
CREATE INDEX invitations_company_idx ON invitations(company_id);
CREATE INDEX invitations_candidate_idx ON invitations(candidate_id);
CREATE UNIQUE INDEX one_pending_invitation ON invitations(company_id, candidate_id)
    WHERE state = 'pending';

CREATE TABLE invitation_versions (
    id uuid PRIMARY KEY,
    invitation_id uuid NOT NULL REFERENCES invitations(id) ON DELETE CASCADE,
    version integer NOT NULL CHECK (version > 0),
    snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    state varchar(30) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (invitation_id, version)
);
CREATE INDEX invitation_versions_invitation_idx ON invitation_versions(invitation_id);

CREATE TABLE contact_grants (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    candidate_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    basis_invitation_id uuid NOT NULL REFERENCES invitations(id),
    basis_version integer NOT NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);
CREATE INDEX contact_grants_company_idx ON contact_grants(company_id);
CREATE INDEX contact_grants_candidate_idx ON contact_grants(candidate_id);
CREATE UNIQUE INDEX one_active_company_grant ON contact_grants(company_id, candidate_id)
    WHERE revoked_at IS NULL;
ALTER TABLE invitations ADD CONSTRAINT invitation_grant_fk
    FOREIGN KEY (contact_grant_id) REFERENCES contact_grants(id);

CREATE TABLE idempotency_records (
    id uuid PRIMARY KEY,
    scope varchar(100) NOT NULL,
    operation varchar(200) NOT NULL,
    key varchar(128) NOT NULL,
    request_hash char(64) NOT NULL,
    response jsonb NOT NULL,
    status_code integer NOT NULL DEFAULT 200,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (scope, operation, key)
);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    type varchar(60) NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    state varchar(30) NOT NULL DEFAULT 'pending',
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error text,
    delivered_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    actor_id uuid REFERENCES users(id) ON DELETE SET NULL,
    event_type varchar(60) NOT NULL,
    entity_id uuid NOT NULL,
    data jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
