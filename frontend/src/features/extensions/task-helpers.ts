type TaskDraft = {
  title: string;
  purpose: string;
  instructions: string;
  estimated_minutes: number;
  due_at: string;
  evaluation_mode: 'exact' | 'rubric';
  expected_answer: string | null;
  rubric: Array<{ id: string; label: string; max_points: number }>;
};

export function taskValidationErrors(input: TaskDraft): string[] {
  const errors: string[] = [];
  if (!input.title.trim() || input.title.length > 160) errors.push('title');
  if (!input.purpose.trim() || input.purpose.length > 500) errors.push('purpose');
  if (!input.instructions.trim() || input.instructions.length > 8000) errors.push('instructions');
  if (!Number.isInteger(input.estimated_minutes) || input.estimated_minutes < 1 || input.estimated_minutes > 480) errors.push('estimated_minutes');
  if (!input.due_at || Number.isNaN(Date.parse(input.due_at)) || Date.parse(input.due_at) <= Date.now()) errors.push('due_at');
  if (input.evaluation_mode === 'exact' && (!input.expected_answer?.trim() || input.expected_answer.length > 1000)) errors.push('expected_answer');
  if (input.evaluation_mode === 'rubric' && (!input.rubric.length || input.rubric.length > 20 || input.rubric.some((criterion) => !/^[a-zA-Z0-9_-]{1,64}$/.test(criterion.id) || !criterion.label.trim() || criterion.label.length > 200 || !Number.isInteger(criterion.max_points) || criterion.max_points < 1 || criterion.max_points > 100))) errors.push('rubric');
  return errors;
}

export function preserveDraftOnConflict<TDraft, TServer>(draft: TDraft, server: TServer) {
  return { draft, server, conflict: true } as const;
}

export function createPendingMessage(body: string, makeKey: () => string, pending?: { body: string; idempotencyKey: string }) {
  return { body, idempotencyKey: pending?.body === body ? pending.idempotencyKey : makeKey() };
}

export function clearConversationAfterAccessDenied<TMessage>(input: { status: number; messages: TMessage[] }) {
  return input.status === 401 || input.status === 403 || input.status === 404
    ? { messages: [] as TMessage[], closed: true }
    : { messages: input.messages, closed: false };
}

export function createPendingRequest<TPayload>(payload: TPayload, makeKey: () => string, pending?: { payload: TPayload; idempotencyKey: string }) {
  const unchanged = pending && JSON.stringify(pending.payload) === JSON.stringify(payload);
  return { payload, idempotencyKey: unchanged ? pending.idempotencyKey : makeKey() };
}

export function canRevokeTaskSharing(status: string, acceptedAt: string | null, revokedAt: string | null): boolean {
  return acceptedAt !== null && revokedAt === null && ['accepted', 'submitted', 'reviewed', 'withdrawn'].includes(status);
}

export function canShowTaskResults(role: 'candidate' | 'employer', revokedAt: string | null): boolean {
  return role === 'candidate' || revokedAt === null;
}

export function flattenMatchCandidates<T extends { candidate: { id: string } }>(displayOrder: readonly string[], groups: { confirmed: T[]; needs_clarification: T[]; needs_confirmation: T[] }): T[] {
  const seen = new Set<string>();
  return displayOrder.flatMap((group) => groups[group as keyof typeof groups] ?? []).filter((item) => {
    if (seen.has(item.candidate.id)) return false;
    seen.add(item.candidate.id);
    return true;
  });
}

export function isConversationResponseCurrent(request: { requestGeneration: number; currentRequestGeneration: number; accessGeneration: number; currentAccessGeneration: number }): boolean {
  return request.requestGeneration === request.currentRequestGeneration && request.accessGeneration === request.currentAccessGeneration;
}

export function canReviewShortTask(task: { status: string; evaluation_mode: string; solution: string | null; sharing_revoked_at: string | null; has_review: boolean }): boolean {
  return task.status === 'submitted' && task.evaluation_mode === 'rubric' && Boolean(task.solution) && task.sharing_revoked_at === null && !task.has_review;
}
