import assert from 'node:assert/strict';
import test from 'node:test';
import {
  createPendingMessage,
  createPendingRequest,
  preserveDraftOnConflict,
  taskValidationErrors,
  clearConversationAfterAccessDenied,
  canRevokeTaskSharing,
  canShowTaskResults,
  flattenMatchCandidates,
  isConversationResponseCurrent,
  canReviewShortTask,
} from './extensions/task-helpers.ts';

test('task validation requires complete exact-answer and rubric terms before offer', () => {
  const common = { title: '  ', purpose: '   ', instructions: '   ', estimated_minutes: 0, due_at: '', evaluation_mode: 'exact' as const, expected_answer: '  ', rubric: [] };
  assert.deepEqual(taskValidationErrors(common), ['title', 'purpose', 'instructions', 'estimated_minutes', 'due_at', 'expected_answer']);
  assert.deepEqual(taskValidationErrors({ ...common, title: 'API design', purpose: 'Check reasoning', instructions: 'Describe a safe retry strategy.', estimated_minutes: 30, due_at: '2026-11-01T12:00:00.000Z', expected_answer: 'idempotent', rubric: [] }), []);
});

test('task validation rejects a deadline that has already passed', () => {
  assert.deepEqual(taskValidationErrors({ title: 'API design', purpose: 'Check reasoning', instructions: 'Describe a safe retry strategy.', estimated_minutes: 30, due_at: '2020-01-01T12:00:00.000Z', evaluation_mode: 'exact', expected_answer: 'idempotent', rubric: [] }), ['due_at']);
});

test('rubric task validation rejects missing criteria and invalid ranges', () => {
  assert.deepEqual(taskValidationErrors({ title: 'API design', purpose: 'Check reasoning', instructions: 'Describe a safe retry strategy.', estimated_minutes: 30, due_at: '2026-11-01T12:00:00.000Z', evaluation_mode: 'rubric', expected_answer: '', rubric: [{ id: 'clarity', label: 'Clarity', max_points: 0 }] }), ['rubric']);
});

test('409 reconciliation adopts server state separately and preserves the unsaved candidate draft', () => {
  const draft = { answer: 'my unsaved answer' };
  const server = { version: 4, answer: 'new server answer' };
  assert.deepEqual(preserveDraftOnConflict(draft, server), { draft, server, conflict: true });
});

test('a pending message keeps the same idempotency key for an explicit retry', () => {
  const first = createPendingMessage('same body', () => 'key-1');
  assert.equal(createPendingMessage(first.body, () => 'key-2', first).idempotencyKey, 'key-1');
  assert.equal(createPendingMessage('edited body', () => 'key-2', first).idempotencyKey, 'key-2');
});

test('a failed task action reuses its idempotency key only while its payload is unchanged', () => {
  const first = createPendingRequest({ solution: 'draft solution' }, () => 'task-key-1');
  assert.equal(createPendingRequest({ solution: 'draft solution' }, () => 'task-key-2', first).idempotencyKey, 'task-key-1');
  assert.equal(createPendingRequest({ solution: 'edited solution' }, () => 'task-key-2', first).idempotencyKey, 'task-key-2');
});

test('sharing is revocable only after recorded acceptance and while sharing is active', () => {
  const acceptedAt = '2026-10-08T12:00:00Z';
  assert.equal(canRevokeTaskSharing('reviewed', acceptedAt, null), true);
  assert.equal(canRevokeTaskSharing('withdrawn', acceptedAt, null), true);
  assert.equal(canRevokeTaskSharing('accepted', null, null), false);
  assert.equal(canRevokeTaskSharing('reviewed', acceptedAt, '2026-10-08T12:30:00Z'), false);
  assert.equal(canRevokeTaskSharing('declined', null, null), false);
});

test('automatic and employer results are hidden when sharing has been revoked', () => {
  assert.equal(canShowTaskResults('candidate', '2026-10-08T12:00:00Z'), true);
  assert.equal(canShowTaskResults('employer', null), true);
  assert.equal(canShowTaskResults('employer', '2026-10-08T12:00:00Z'), false);
});

test('candidate options follow server group order and do not repeat a public profile', () => {
  const match = (id: string, name: string) => ({ candidate: { id, display_name: name } });
  const flattened = flattenMatchCandidates(['needs_confirmation', 'confirmed'], {
    confirmed: [match('candidate-1', 'Ада')],
    needs_clarification: [],
    needs_confirmation: [match('candidate-2', 'Борис'), match('candidate-1', 'Ада')],
  });
  assert.deepEqual(flattened.map((item) => item.candidate.id), ['candidate-2', 'candidate-1']);
});

test('a conversation response is discarded after access revocation or a newer request', () => {
  const current = { requestGeneration: 4, currentRequestGeneration: 4, accessGeneration: 7, currentAccessGeneration: 7 };
  assert.equal(isConversationResponseCurrent(current), true);
  assert.equal(isConversationResponseCurrent({ ...current, currentAccessGeneration: 8 }), false);
  assert.equal(isConversationResponseCurrent({ ...current, currentRequestGeneration: 5 }), false);
});

test('only a submitted rubric task with an active shared solution can be reviewed', () => {
  assert.equal(canReviewShortTask({ status: 'submitted', evaluation_mode: 'rubric', solution: 'answer', sharing_revoked_at: null, has_review: false }), true);
  assert.equal(canReviewShortTask({ status: 'submitted', evaluation_mode: 'exact', solution: 'answer', sharing_revoked_at: null, has_review: false }), false);
  assert.equal(canReviewShortTask({ status: 'submitted', evaluation_mode: 'rubric', solution: null, sharing_revoked_at: null, has_review: false }), false);
  assert.equal(canReviewShortTask({ status: 'submitted', evaluation_mode: 'rubric', solution: 'answer', sharing_revoked_at: '2026-10-08T12:00:00Z', has_review: false }), false);
  assert.equal(canReviewShortTask({ status: 'reviewed', evaluation_mode: 'rubric', solution: 'answer', sharing_revoked_at: null, has_review: true }), false);
});

test('403 and 404 responses discard private conversation data', () => {
  const messages = [{ id: 'secret', body: 'private text' }];
  assert.deepEqual(clearConversationAfterAccessDenied({ status: 401, messages }), { messages: [], closed: true });
  assert.deepEqual(clearConversationAfterAccessDenied({ status: 403, messages }), { messages: [], closed: true });
  assert.deepEqual(clearConversationAfterAccessDenied({ status: 404, messages }), { messages: [], closed: true });
  assert.deepEqual(clearConversationAfterAccessDenied({ status: 500, messages }), { messages, closed: false });
});
