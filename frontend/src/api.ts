import type { components } from './api/schema';

export type Schemas = components['schemas'];
export type User = Schemas['UserMe'];
export type CandidateProfile = Schemas['CandidatePrivate'];
export type Company = Schemas['Company'];
export type Taxonomy = Schemas['Taxonomy'];
export type Need = Schemas['Need'];
export type Attempt = Schemas['Attempt'];
export type Candidate = Schemas['CandidatePublic'];
export type MatchCandidate = Schemas['MatchCandidate'];
export type Invitation = Schemas['Invitation'];
export type Shortlist = Schemas['Shortlist'];
export type OwnContacts = Schemas['OwnContacts'];
export type ContactBundle = Schemas['ContactBundle'];
export type Grade = 'junior' | 'middle';
export type SkillId = 'python-basics' | 'http-api' | 'sql-transactions' | 'reliability' | 'postgres' | 'docker' | 'fastapi';
export type ApiPayload = Schemas['RegisterRequest'] | Schemas['ConfirmEmailRequest'] | Schemas['ResendConfirmationRequest'] | Schemas['LoginRequest'] | Schemas['CandidateProfilePatch'] | Schemas['VisibilityRequest'] | Schemas['OwnContactsPatch'] | Schemas['CompanyPatch'] | Schemas['FspLinkRequest'] | Schemas['CreateAttemptRequest'] | Schemas['AnswerRequest'] | Schemas['NeedCreateRequest'] | Schemas['NeedPatchRequest'] | Schemas['ConfirmCompetenciesRequest'] | Schemas['SearchRequest'] | Schemas['ShortlistCreateRequest'] | Schemas['ShortlistPatchRequest'] | Schemas['InvitationCreateRequest'] | Schemas['InvitationDecisionRequest'] | Schemas['InvitationSupersedeRequest'] | Schemas['CandidateComparisonRequest'] | Schemas['RecruitingRecordPutRequest'] | Schemas["CandidateBankSearchRequest"] | Schemas["ConsentMutationRequest"] | Schemas['InvitationMessageCreateRequest'] | Schemas['ShortTaskCreateRequest'] | Schemas['ShortTaskTransitionRequest'] | Schemas['ShortTaskAcceptRequest'] | Schemas['ShortTaskSubmissionRequest'] | Schemas['ShortTaskReviewRequest'] | Schemas['InvitationNextStepPutRequest'] | { expected_version: number } | Record<string, unknown>;

export type ParsedResumeData = {
  display_name: string | null;
  email: string | null;
  phone: string | null;
  headline: string | null;
  summary: string | null;
  industry: string | null;
  declared_grade: 'junior' | 'middle' | null;
  skills: SkillId[];
  soft_skills: string[];
  role_preferences: string[];
  cities: string[];
  raw_preview: string;
};

export type PublicVacancy = {
  id: string;
  need_id: string | null;
  company_id: string;
  company_name: string;
  company_industry?: string | null;
  title: string;
  description: string;
  specialization_id: string;
  target_grade: Grade;
  work_mode: string;
  city?: string | null;
  workload_hours_per_week: number;
  salary: { min_kopecks: number; max_kopecks: number; currency: string; basis: string };
  status: string;
  created_at: string;
};

export type CandidateApplication = {
  id: string;
  vacancy_id: string;
  vacancy_title: string;
  company_name: string;
  specialization_id: string;
  target_grade: string;
  work_mode: string;
  salary: { min_kopecks: number; max_kopecks: number };
  status: 'submitted' | 'reviewed' | 'accepted' | 'declined';
  cover_letter?: string | null;
  created_at: string;
};

export type CompetencyRadarData = {
  candidate_id: string;
  display_name: string;
  verified_grade: string | null;
  declared_grade: string;
  irt_ability: {
    latent_theta: number;
    percentile_rank: number;
    sem: number;
    discrimination: string;
    confidence_interval: [number, number];
  };
  radar_axes: Array<{
    key: string;
    label: string;
    verified: number;
    declared: number;
    target: number;
  }>;
  gaps: Array<{
    competency: string;
    label: string;
    target_score: number;
    candidate_score: number;
    gap_delta: number;
    severity: 'critical' | 'moderate';
    interview_questions: Array<{ question: string; rubric: string }>;
  }>;
  passport_seal: {
    verification_id: string;
    issued_at: string;
    sha256_seal: string;
    status: string;
  };
};

export type ValidationMetrics = {
  model: string;
  framework_version: string;
  reliability_metrics: {
    test_retest_pearson_r: number;
    test_retest_description: string;
    cronbach_alpha: number;
    cronbach_description: string;
    cohens_kappa: number;
    cohens_description: string;
    standard_error_of_measurement: number;
    leakage_resistance_index: number;
    leakage_resistance_mechanism: string;
  };
  discrimination_parameters: Array<{
    family_id: string;
    competency: string;
    discrimination_alpha: number;
    difficulty_b: number;
    grade: string;
  }>;
  anti_cheating_telemetry: {
    supported_signals: string[];
    integrity_index_enabled: boolean;
  };
};

type Envelope<T> = { data: T };
type ApiError = { error?: { code?: string; message?: string; field?: string; retryable?: boolean; eligible_at?: string | null; active_attempt_id?: string | null } };


export class RequestError extends Error {
  code: string;
  status: number;
  retryable: boolean;
  field?: string;
  eligibleAt?: string | null;
  activeAttemptId?: string | null;

  constructor(status: number, body: ApiError) {
    super(body.error?.message ?? 'Не удалось выполнить запрос. Попробуйте ещё раз.');
    this.name = 'RequestError';
    this.status = status;
    this.code = body.error?.code ?? 'REQUEST_FAILED';
    this.retryable = body.error?.retryable ?? false;
    this.field = body.error?.field;
    this.eligibleAt = body.error?.eligible_at;
    this.activeAttemptId = body.error?.active_attempt_id;
  }
}

export function generateUUID(): string {
  try {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
  } catch { /* ignore */ }
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c: any) =>
      (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16)
    );
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

if (typeof crypto !== 'undefined' && typeof crypto.randomUUID !== 'function') {
  try {
    Object.defineProperty(crypto, 'randomUUID', {
      value: generateUUID,
      configurable: true,
      writable: true,
    });
  } catch {
    (crypto as any).randomUUID = generateUUID;
  }
}

let csrfToken: string | undefined;
let csrfPromise: Promise<string> | undefined;
const sleep = (ms: number) => new Promise((resolve) => window.setTimeout(resolve, ms));

async function getCsrf(): Promise<string> {
  if (csrfToken) return csrfToken;
  if (!csrfPromise) {
    csrfPromise = fetch('/api/v1/auth/csrf', { credentials: 'include' })
      .then(async (response) => {
        const body = (await response.json()) as Envelope<{ csrf_token: string }> | ApiError;
        if (!response.ok || !('data' in body)) throw new RequestError(response.status, body as ApiError);
        csrfToken = body.data.csrf_token;
        return csrfToken;
      })
      .finally(() => { csrfPromise = undefined; });
  }
  return csrfPromise;
}

export function rotateCsrf(): void {
  csrfToken = undefined;
  csrfPromise = undefined;
}

async function send<T>(path: string, method: string, payload?: ApiPayload, idempotencyKey?: string): Promise<T> {
  const mutating = !['GET', 'HEAD'].includes(method);
  const key = mutating ? (idempotencyKey ?? generateUUID()) : undefined;
  let attempts = 0;
  while (true) {
    try {
      const token = mutating ? await getCsrf() : undefined;
      const response = await fetch(`/api/v1${path}`, {
        method,
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          ...(payload === undefined ? {} : { 'Content-Type': 'application/json' }),
          ...(token ? { 'X-CSRF-Token': token } : {}),
          ...(key ? { 'Idempotency-Key': key } : {}),
        },
        ...(payload === undefined ? {} : { body: JSON.stringify(payload) }),
      });
      if (response.status === 204) return undefined as T;
      const body = (await response.json()) as Envelope<T> | ApiError;
      if (!response.ok) {
        const error = new RequestError(response.status, body as ApiError);
        if (response.status === 403 && attempts === 0) {
          rotateCsrf();
          attempts += 1;
          continue;
        }
        if (error.retryable && attempts < 2 && key) {
          await sleep(350 * (attempts + 1));
          attempts += 1;
          continue;
        }
        throw error;
      }
      if (!('data' in body)) throw new RequestError(response.status, body as ApiError);
      return body.data;
    } catch (error) {
      if (error instanceof RequestError) throw error;
      if (attempts < 2 && key) {
        await sleep(350 * (attempts + 1));
        attempts += 1;
        continue;
      }
      throw new Error('Нет связи с сервером. Проверьте подключение и повторите действие.');
    }
  }
}

export const api = {
  get: <T>(path: string) => send<T>(path, 'GET'),
  post: <T>(path: string, body?: ApiPayload, idempotencyKey?: string) => send<T>(path, 'POST', body, idempotencyKey),
  put: <T>(path: string, body: ApiPayload, idempotencyKey?: string) => send<T>(path, 'PUT', body, idempotencyKey),
  patch: <T>(path: string, body: ApiPayload, idempotencyKey?: string) => send<T>(path, 'PATCH', body, idempotencyKey),
  delete: <T>(path: string) => send<T>(path, 'DELETE'),
  upload: async <T>(path: string, formData: FormData): Promise<T> => {
    const token = await getCsrf();
    const key = generateUUID();
    const response = await fetch(`/api/v1${path}`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...(token ? { 'X-CSRF-Token': token } : {}),
        'Idempotency-Key': key,
      },
      body: formData,
    });
    const body = (await response.json()) as Envelope<T> | ApiError;
    if (!response.ok || !('data' in body)) throw new RequestError(response.status, body as ApiError);
    return body.data;
  },
  download: async (path: string, filename?: string) => {
    const response = await fetch(`/api/v1${path}`, { credentials: 'include' });
    if (!response.ok) {
      let body: ApiError = {};
      try { body = (await response.json()) as ApiError; } catch { /* download error has no JSON body */ }
      throw new RequestError(response.status, body);
    }
    const blob = await response.blob();
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = href;
    const disposition = response.headers.get('content-disposition');
    let name = filename;
    if (!name && disposition && disposition.includes('filename=')) {
      name = disposition.split('filename=')[1]?.replace(/["';]/g, '').trim();
    }
    anchor.download = name || 'download';
    anchor.click();
    URL.revokeObjectURL(href);
  },
};

export function errorMessage(error: unknown): string {
  if (error instanceof RequestError) return error.message;
  if (error instanceof Error) return error.message;
  if (typeof error === 'object' && error !== null && 'message' in error) return String((error as any).message);
  return 'Что-то пошло не так. Попробуйте ещё раз.';
}

export const skillLabels: Record<SkillId, string> = {
  'python-basics': 'Основы Python', 'http-api': 'HTTP и API', 'sql-transactions': 'SQL и транзакции',
  reliability: 'Надёжность', postgres: 'PostgreSQL', docker: 'Docker', fastapi: 'FastAPI',
};
