CREATE TABLE short_tasks (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    candidate_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    need_id uuid NOT NULL REFERENCES employer_needs(id) ON DELETE RESTRICT,
    need_version integer NOT NULL CHECK (need_version > 0),
    company_snapshot jsonb NOT NULL,
    need_snapshot jsonb NOT NULL,
    title varchar(160) NOT NULL,
    purpose varchar(500) NOT NULL,
    instructions varchar(8000) NOT NULL,
    estimated_minutes integer NOT NULL CHECK (estimated_minutes BETWEEN 1 AND 480),
    due_at timestamptz NOT NULL,
    evaluation_mode varchar(16) NOT NULL CHECK (evaluation_mode IN ('rubric', 'exact')),
    expected_answer varchar(1000),
    rubric jsonb NOT NULL DEFAULT '[]'::jsonb,
    status varchar(16) NOT NULL CHECK (status IN ('offered', 'accepted', 'declined', 'submitted', 'reviewed', 'withdrawn', 'expired')),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    sharing_notice_version varchar(100) NOT NULL,
    accepted_at timestamptz,
    declined_at timestamptz,
    submitted_at timestamptz,
    reviewed_at timestamptz,
    withdrawn_at timestamptz,
    sharing_revoked_at timestamptz,
    solution text,
    automatic_result jsonb,
    employer_review jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((evaluation_mode = 'exact' AND expected_answer IS NOT NULL AND rubric = '[]'::jsonb)
        OR (evaluation_mode = 'rubric' AND expected_answer IS NULL AND jsonb_array_length(rubric) BETWEEN 1 AND 20))
);

CREATE INDEX short_tasks_company_page_idx ON short_tasks(company_id, created_at DESC, id DESC);
CREATE INDEX short_tasks_candidate_page_idx ON short_tasks(candidate_id, created_at DESC, id DESC);
