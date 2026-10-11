import { api, RequestError, generateUUID, type Schemas } from '../../api.ts';
import type { ProctoringChannel, ProctoringChunkReceipt } from './proctoring-upload-queue.ts';
import type { ServerProctoringConfig } from './server-proctoring-policy.ts';

export type ServerProctoringStatus = Schemas['ProctoringSessionStatus'];
export type SessionDTO = Schemas['ProctoringSession'];
export type ProctoringReviewListItem = Schemas['ProctoringReviewQueueItem'];
export type ProctoringReviewDTO = Schemas['ProctoringReview'];
export type ServerObservationType = Schemas['ProctoringEventType'];
const serverObservationTypes = new Set<ServerObservationType>(['tab_hidden', 'window_blur', 'fullscreen_exit', 'camera_disabled', 'screen_share_stopped']);

export function toServerProctoringEvents(events: ReadonlyArray<{ id: string; event_type: string; timestamp: string }>): Array<{ event_id: string; type: ServerObservationType; occurred_at: string }> {
  return events.flatMap((event) => {
    if (!serverObservationTypes.has(event.event_type as ServerObservationType) || !event.id || !Number.isFinite(Date.parse(event.timestamp))) return [];
    return [{ event_id: event.id, type: event.event_type as ServerObservationType, occurred_at: event.timestamp }];
  });
}

export async function sha256Hex(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

export const getServerProctoringConfig = () => api.get<ServerProctoringConfig>('/proctoring/config');
export const getProctoringSession = (attemptId: string) => api.get<SessionDTO>(`/attempts/${encodeURIComponent(attemptId)}/proctoring/session`);

export function startProctoringSession(attemptId: string, config: ServerProctoringConfig, idempotencyKey = generateUUID()): Promise<SessionDTO> {
  return api.post<SessionDTO>(`/attempts/${encodeURIComponent(attemptId)}/proctoring/session`, {
    consent_version: config.consent_version,
    consent_document_sha256: config.consent_document_sha256,
  }, idempotencyKey);
}

export function createProctoringCapture(session: SessionDTO, idempotencyKey = generateUUID()): Promise<SessionDTO> {
  return api.post<SessionDTO>(`/proctoring/sessions/${encodeURIComponent(session.session_id)}/captures`, {
    expected_version: session.version,
  }, idempotencyKey);
}

export async function uploadProctoringChunk(sessionId: string, chunk: { captureId: string; channel: ProctoringChannel; sequence: number; blob: Blob }, idempotencyKey: string, signal: AbortSignal): Promise<ProctoringChunkReceipt> {
  const digest = await sha256Hex(chunk.blob);
  const captureQuery = new URLSearchParams({ capture_id: chunk.captureId });
  return api.uploadBinary<ProctoringChunkReceipt>(
    `/proctoring/sessions/${encodeURIComponent(sessionId)}/media/${chunk.channel}/chunks/${chunk.sequence}?${captureQuery.toString()}`,
    chunk.blob,
    { sha256: digest, idempotencyKey, signal },
  );
}

export function appendProctoringEvents(session: Pick<SessionDTO, 'session_id' | 'version'>, events: Array<{ event_id: string; type: string; occurred_at: string }>, idempotencyKey = generateUUID()): Promise<SessionDTO> {
  return api.post<SessionDTO>(`/proctoring/sessions/${encodeURIComponent(session.session_id)}/events`, {
    expected_version: session.version,
    events,
  }, idempotencyKey);
}

export async function appendAllProctoringEvents(
  initialSession: SessionDTO,
  events: Array<{ event_id: string; type: string; occurred_at: string }>,
  createIdempotencyKey: () => string = generateUUID,
  onBatchAccepted?: (session: SessionDTO, acceptedCount: number) => void,
): Promise<SessionDTO> {
  let session = initialSession;
  let acceptedCount = 0;
  for (let offset = 0; offset < events.length; offset += 100) {
    const batch = events.slice(offset, offset + 100);
    if (batch.length) {
      session = await appendProctoringEvents(session, batch, createIdempotencyKey());
      acceptedCount += batch.length;
      onBatchAccepted?.(session, acceptedCount);
    }
  }
  return session;
}

export function sealProctoringSession(session: SessionDTO, captureId: string, lastSequences: Record<ProctoringChannel, number>, idempotencyKey = generateUUID()): Promise<SessionDTO> {
  return api.post<SessionDTO>(`/proctoring/sessions/${encodeURIComponent(session.session_id)}/seal`, {
    expected_version: session.version,
    capture_id: captureId,
    last_sequences: lastSequences,
  }, idempotencyKey);
}

export function revokeProctoringSession(session: SessionDTO, idempotencyKey = generateUUID()): Promise<SessionDTO> {
  return api.post<SessionDTO>(`/proctoring/sessions/${encodeURIComponent(session.session_id)}/revoke`, {
    expected_version: session.version,
  }, idempotencyKey);
}

export async function refreshOwnedProctoringSession(attemptId: string, expectedSessionId: string): Promise<SessionDTO> {
  const latest = await getProctoringSession(attemptId);
  if (latest.attempt_id !== attemptId || latest.session_id !== expectedSessionId) {
    throw new Error('Серверная сессия этой попытки изменилась. Обновите состояние перед продолжением.');
  }
  return latest;
}

export async function revokeProctoringSessionFresh(
  attemptId: string,
  session: Pick<SessionDTO, 'session_id' | 'attempt_id' | 'version'>,
  createIdempotencyKey: (latest: SessionDTO) => string = () => generateUUID(),
): Promise<SessionDTO> {
  for (let conflictRetry = 0; conflictRetry < 2; conflictRetry++) {
    const latest = await refreshOwnedProctoringSession(attemptId, session.session_id);
    try {
      return await revokeProctoringSession(latest, createIdempotencyKey(latest));
    } catch (error) {
      if (!(error instanceof RequestError) || error.status !== 409 || conflictRetry === 1) throw error;
    }
  }
  throw new Error('Не удалось отозвать серверную сессию.');
}

export function getProctoringReviewQueue(limit = 50, cursor?: string | null): Promise<{ items: ProctoringReviewListItem[]; next_cursor: string | null }> {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor) query.set('cursor', cursor);
  return api.get(`/operations/proctoring/reviews?${query.toString()}`);
}

export const getProctoringReview = (sessionId: string) => api.get<ProctoringReviewDTO>(`/operations/proctoring/reviews/${encodeURIComponent(sessionId)}`);

export function decideProctoringReview(sessionId: string, body: {
  expected_version: number;
  expected_attempt_version: number;
  outcome: 'uphold' | 'invalidate' | 'technical';
  reason_code: 'approved' | 'unauthorized_assistance' | 'identity_mismatch' | 'recording_incomplete' | 'technical_failure';
  note: string;
}, idempotencyKey = generateUUID()): Promise<SessionDTO> {
  return api.post<SessionDTO>(`/operations/proctoring/reviews/${encodeURIComponent(sessionId)}/decision`, body, idempotencyKey);
}

export async function fetchProctoringMedia(sessionId: string, captureId: string, channel: ProctoringChannel, signal?: AbortSignal): Promise<Blob> {
  const query = new URLSearchParams({ capture_id: captureId });
  const response = await fetch(`/api/v1/proctoring/sessions/${encodeURIComponent(sessionId)}/media/${channel}?${query.toString()}`, {
    credentials: 'include',
    cache: 'no-store',
    signal,
  });
  if (!response.ok) {
    let body: { error?: { message?: string } } = {};
    try { body = await response.json(); } catch { /* preserve a generic denial message */ }
    throw new RequestError(response.status, body);
  }
  return response.blob();
}
