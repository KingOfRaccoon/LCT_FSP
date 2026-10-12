export type WorkMode = 'remote' | 'hybrid' | 'office';
export type SkillId = 'python-basics' | 'http-api' | 'sql-transactions' | 'reliability' | 'postgres' | 'docker' | 'fastapi';

export type ExperienceInput = {
  id: string;
  company?: string | null;
  role: string;
  description: string;
  start_month: string;
  end_month: string | null;
  skill_ids: SkillId[];
};

export type ProjectInput = { id: string; title: string; description: string; url: string | null; skill_ids: SkillId[] };

export type CandidateProfilePatch = {
  expected_version: number;
  display_name?: string;
  headline?: string | null;
  summary?: string | null;
  allowed_work_modes?: WorkMode[];
  cities?: string[];
  desired_hours_per_week?: number | null;
  max_weekly_hours?: number;
  skills?: SkillId[];
  experience_entries?: ExperienceInput[];
  project_entries?: ProjectInput[];
  industry?: string | null;
  role_preferences?: string[];
  soft_skills?: string[];
};

export type ResumeImportPatch = Omit<CandidateProfilePatch, 'expected_version'>;
export type ResumeImportDraft = {
  candidate_patch: ResumeImportPatch;
  suggestions: { contacts: { type: 'email' | 'phone'; value: string }[] };
  warnings: string[];
  needs_review: true;
};

export type SalaryPreference = {
  min_kopecks: number;
  max_kopecks: number;
  currency: 'RUB';
  period: 'month';
  basis: 'gross' | 'net';
  workload_type: 'full_time' | 'part_time';
  hours_per_week: number;
};

export type SalaryPreferenceForm = {
  minRubles: string;
  maxRubles: string;
  basis: SalaryPreference['basis'];
  workloadType: SalaryPreference['workload_type'];
  hoursPerWeek: string;
};

export function salaryPreferenceFromForm(form: SalaryPreferenceForm): SalaryPreference {
  const min = Number(form.minRubles), max = Number(form.maxRubles), hours = Number(form.hoursPerWeek);
  if (!Number.isSafeInteger(min) || !Number.isSafeInteger(max) || min < 1 || max < min) {
    throw new Error('Укажите целые суммы в рублях: минимум должен быть не меньше 1 и не больше максимума.');
  }
  if (!Number.isSafeInteger(hours) || (form.workloadType === 'full_time' ? hours !== 40 : hours < 1 || hours > 39)) {
    throw new Error(form.workloadType === 'full_time' ? 'Для полной занятости укажите ровно 40 часов в неделю.' : 'Для частичной занятости укажите от 1 до 39 часов в неделю.');
  }
  const min_kopecks = min * 100, max_kopecks = max * 100;
  if (!Number.isSafeInteger(min_kopecks) || !Number.isSafeInteger(max_kopecks)) throw new Error('Сумма слишком велика.');
  return { min_kopecks, max_kopecks, currency: 'RUB', period: 'month', basis: form.basis, workload_type: form.workloadType, hours_per_week: hours };
}

export type NeedInsights = {
  need_id: string;
  need_version: number;
  generated_at: string;
  policy_version: string;
  scope: 'current_visible_platform_profiles';
  counts: {
    profile_pool: number;
    confirmed: number;
    needs_clarification: number;
    needs_confirmation: number;
    excluded: number;
    demo: number;
  };
  mandatory_requirements: { unknown_test_evidence_pairs: number; blocked_by_matching_policy_count: number };
  requirements: {
    skill_id: SkillId;
    importance: 'required' | 'preferred';
    test_confirmed_count: number;
    declared_only_count: number;
    no_skill_signal_count: number;
    additional_confirmed_matches_if_relaxed: number;
  }[];
  salary: {
    scope: 'fixed_visible_specialization_grade_cohort';
    summary_available: boolean;
    known_count: number | null;
    compatible_count: number | null;
    unknown_count: number | null;
    incompatible_count: number | null;
    within_range_count: null;
    range_count_available: false;
    range_count_suppression_reason: 'privacy_protected';
    median_min_kopecks: number | null;
    median_max_kopecks: number | null;
    low_sample: boolean;
  };
};
