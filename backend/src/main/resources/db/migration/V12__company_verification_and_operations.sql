CREATE TABLE operations_grants (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    permission varchar(32) NOT NULL CHECK (permission IN ('company_review', 'monitor_read')),
    active boolean NOT NULL DEFAULT true,
    granted_at timestamptz NOT NULL DEFAULT now(),
    granted_by uuid REFERENCES users(id),
    PRIMARY KEY (user_id, permission)
);

CREATE TABLE company_verification_requests (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    company_version integer NOT NULL CHECK (company_version > 0),
    criteria_hash char(64) NOT NULL,
    inn varchar(12) NOT NULL,
    legal_name varchar(300) NOT NULL,
    snapshot jsonb NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN (
        'pending', 'registry_confirmed', 'reviewed', 'needs_changes', 'rejected',
        'unavailable', 'superseded'
    )),
    demo boolean NOT NULL DEFAULT true,
    provider varchar(40),
    registry_inn varchar(12),
    registry_legal_name varchar(300),
    registry_status varchar(30),
    registry_updated_at varchar(100),
    source_check_status varchar(20) CHECK (source_check_status IN ('available', 'not_found', 'unavailable')),
    source_check_error_code varchar(40),
    source_type varchar(30),
    source_label varchar(300),
    source_reference varchar(1000),
    source_record_id varchar(200),
    checked_at timestamptz,
    expires_at timestamptz,
    review_notes varchar(2000),
    created_by uuid NOT NULL REFERENCES users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX company_verification_requests_company_idx
    ON company_verification_requests(company_id, created_at DESC);
CREATE UNIQUE INDEX company_verification_requests_pending_idx
    ON company_verification_requests(company_id, criteria_hash)
    WHERE status = 'pending';

CREATE TABLE company_verification_reviews (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES company_verification_requests(id) ON DELETE CASCADE,
    request_version integer NOT NULL CHECK (request_version > 0),
    reviewer_id uuid NOT NULL REFERENCES users(id),
    outcome varchar(20) NOT NULL CHECK (outcome IN ('approve', 'request_changes', 'reject')),
    evidence_reference varchar(1000) NOT NULL,
    registry_inn varchar(12),
    registry_legal_name varchar(300),
    registry_status varchar(30),
    representative_reviewed boolean NOT NULL,
    comment varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (request_id, request_version)
);
CREATE INDEX company_verification_reviews_reviewer_idx
    ON company_verification_reviews(reviewer_id, created_at DESC);

CREATE TABLE company_verification_source_checks (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES company_verification_requests(id) ON DELETE CASCADE,
    request_version integer NOT NULL CHECK (request_version > 0),
    provider varchar(40),
    outcome varchar(20) NOT NULL CHECK (outcome IN ('available', 'not_found', 'unavailable')),
    error_code varchar(40),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (request_id, request_version)
);
