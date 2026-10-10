CREATE TABLE public_vacancies (
    id uuid PRIMARY KEY,
    need_id uuid REFERENCES employer_needs(id) ON DELETE CASCADE,
    company_id uuid NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    title varchar(160) NOT NULL,
    description text NOT NULL,
    specialization_id varchar(60) NOT NULL DEFAULT 'backend-python-v1',
    target_grade varchar(20) NOT NULL CHECK (target_grade IN ('junior', 'middle', 'senior')),
    work_mode varchar(20) NOT NULL DEFAULT 'remote',
    city varchar(120),
    workload_hours_per_week integer NOT NULL DEFAULT 40,
    salary_min_kopecks bigint NOT NULL,
    salary_max_kopecks bigint NOT NULL,
    salary_basis varchar(20) NOT NULL DEFAULT 'gross',
    status varchar(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX public_vacancies_company_idx ON public_vacancies(company_id);
CREATE INDEX public_vacancies_status_idx ON public_vacancies(status);

CREATE TABLE candidate_applications (
    id uuid PRIMARY KEY,
    vacancy_id uuid NOT NULL REFERENCES public_vacancies(id) ON DELETE CASCADE,
    candidate_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    cover_letter text,
    status varchar(30) NOT NULL DEFAULT 'submitted' CHECK (status IN ('submitted', 'reviewed', 'accepted', 'declined')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (vacancy_id, candidate_id)
);
CREATE INDEX candidate_applications_vacancy_idx ON candidate_applications(vacancy_id);
CREATE INDEX candidate_applications_candidate_idx ON candidate_applications(candidate_id);
