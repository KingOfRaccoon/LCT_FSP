import type { Schemas } from '../../api.ts';

export type ServerProctoringConfig = Schemas['ProctoringConfiguration'];

export function canStartServerCapture({
  available,
  secureContext,
  mediaApisAvailable,
}: {
  available: boolean;
  secureContext: boolean;
  mediaApisAvailable: boolean;
}): boolean {
  return available && secureContext && mediaApisAvailable;
}

export function canStartServerSession(config: ServerProctoringConfig | null | undefined): boolean {
  return Boolean(config?.available && config.consent_version && config.consent_document_sha256 && config.consent_text);
}

export function assertServerCaptureReceiptsComplete(lastSequences: Record<'camera' | 'screen', number>): void {
  if (lastSequences.camera < 0 || lastSequences.screen < 0) {
    throw new Error('Не получены подтверждения загрузки обоих каналов записи. Повторите запись или отзовите согласие.');
  }
}

export function preparedCaptureAfterSessionAdoption(
  previousSessionId: string | null,
  nextSessionId: string | null,
  currentPreparedCapture: string,
  newlyPreparedCaptureId?: string,
): string {
  if (nextSessionId && newlyPreparedCaptureId) return `${nextSessionId}:${newlyPreparedCaptureId}`;
  return previousSessionId === nextSessionId ? currentPreparedCapture : '';
}
