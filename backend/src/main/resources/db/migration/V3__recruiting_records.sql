CREATE TABLE recruiting_records (
    id uuid PRIMARY KEY,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    candidate_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    need_id uuid REFERENCES employer_needs(id) ON DELETE SET NULL,
    version integer NOT NULL CHECK (version > 0),
    stage varchar(24) NOT NULL CHECK (stage IN ('reviewing', 'shortlisted', 'invited', 'discussion', 'closed')),
    note varchar(2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (company_id, candidate_id)
);

CREATE INDEX recruiting_records_company_updated_idx
    ON recruiting_records(company_id, updated_at DESC, id DESC);
CREATE INDEX recruiting_records_company_need_idx
    ON recruiting_records(company_id, need_id, updated_at DESC, id DESC);
