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
export type ApiPayload = Schemas['RegisterRequest'] | Schemas['ConfirmEmailRequest'] | Schemas['ResendConfirmationRequest'] | Schemas['LoginRequest'] | Schemas['CandidateProfilePatch'] | Schemas['VisibilityRequest'] | Schemas['OwnContactsPatch'] | Schemas['CompanyPatch'] | Schemas['FspLinkRequest'] | Schemas['CreateAttemptRequest'] | Schemas['AnswerRequest'] | Schemas['NeedCreateRequest'] | Schemas['NeedPatchRequest'] | Schemas['ConfirmCompetenciesRequest'] | Schemas['SearchRequest'] | Schemas['ShortlistCreateRequest'] | Schemas['ShortlistPatchRequest'] | Schemas['InvitationCreateRequest'] | Schemas['InvitationDecisionRequest'] | Schemas['InvitationSupersedeRequest'] | Schemas['CandidateComparisonRequest'] | Schemas['RecruitingRecordPutRequest'] | Schemas["CandidateBankSearchRequest"] | Schemas["ConsentMutationRequest"] | Schemas['InvitationMessageCreateRequest'] | Schemas['ShortTaskCreateRequest'] | Schemas['ShortTaskTransitionRequest'] | Schemas['ShortTaskAcceptRequest'] | Schemas['ShortTaskSubmissionRequest'] | Schemas['ShortTaskReviewRequest'] | Schemas['InvitationNextStepPutRequest'] | { expected_version: number };

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
  download: async (path: string) => {
    const response = await fetch(`/api/v1${path}`, { credentials: 'include' });
    if (!response.ok) {
      let body: ApiError = {};
      try { body = (await response.json()) as ApiError; } catch { /* PDF error has no JSON body. */ }
      throw new RequestError(response.status, body);
    }
    const blob = await response.blob();
    const href = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = href;
    anchor.download = 'profile.pdf';
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
