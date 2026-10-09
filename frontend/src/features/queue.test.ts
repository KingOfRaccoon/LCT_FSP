import assert from 'node:assert/strict';
import test from 'node:test';
import { applyQueuePage, beginQueueRequest, clearQueue, createQueueState, denyQueueRequest, failQueueRequest, handleQueueFailure, isQueueEmpty, type QueueRequest } from './hiring/queue-state.ts';
import type { Schemas } from '../api';

type Item = Schemas['NextStepQueueItem'];
const item = (invitation_id: string, company_name = 'Компания'): Item => ({
  invitation_id, invitation_version: 1, company_name, candidate_display_name: null,
  candidate_visibility: 'hidden', is_my_action: true,
  step: { action: `Действие ${invitation_id}`, responsible_party: 'candidate', due_at: null, completed: false, version: 1, updated_at: '2026-10-09T10:00:00Z' },
});

function started(state = createQueueState('candidate:user-1'), kind: 'refresh' | 'more' = 'refresh'): { state: ReturnType<typeof createQueueState>; request: QueueRequest } {
  return beginQueueRequest(state, kind);
}

test('a queue is empty only after an exhausted successful page', () => {
  let { state, request } = started();
  assert.equal(isQueueEmpty(state), false);
  state = applyQueuePage(state, request, { items: [], next_cursor: 'more', limit: 5 });
  assert.equal(isQueueEmpty(state), false);
  ({ state, request } = started(state, 'more'));
  state = applyQueuePage(state, request, { items: [], next_cursor: null, limit: 5 });
  assert.equal(isQueueEmpty(state), true);
});

test('a newer refresh payload replaces the same invitation while retaining other rows', () => {
  let { state, request } = started();
  state = applyQueuePage(state, request, { items: [item('a'), item('b')], next_cursor: 'next', limit: 5 });
  ({ state, request } = started(state, 'more'));
  state = applyQueuePage(state, request, { items: [item('a', 'Новое имя'), item('c')], next_cursor: null, limit: 5 });
  assert.deepEqual(state.items.map((row) => row.invitation_id), ['a', 'b', 'c']);
  assert.equal(state.items[0].company_name, 'Новое имя');
});

test('a refresh clears rows immediately and rejects a stale load-more response', () => {
  let { state, request } = started();
  state = applyQueuePage(state, request, { items: [item('a')], next_cursor: 'next', limit: 5 });
  const previous = beginQueueRequest(state, 'more');
  const refresh = beginQueueRequest(previous.state, 'refresh');
  assert.deepEqual(refresh.state.items, []);
  const late = applyQueuePage(refresh.state, previous.request, { items: [item('stale')], next_cursor: null, limit: 5 });
  assert.deepEqual(late.items, []);
});

test('clearing access invalidates pending requests so rows cannot reappear', () => {
  let { state, request } = started();
  state = applyQueuePage(state, request, { items: [item('a')], next_cursor: 'next', limit: 5 });
  const pending = beginQueueRequest(state, 'refresh');
  const denied = clearQueue(pending.state, 'candidate:user-1');
  const late = applyQueuePage(denied, pending.request, { items: [item('private')], next_cursor: null, limit: 5 });
  assert.deepEqual(late.items, []);
  assert.equal(isQueueEmpty(late), false);
});

test('a newer refresh rejects an older refresh response', () => {
  const first = started();
  const second = beginQueueRequest(first.state, 'refresh');
  const late = applyQueuePage(second.state, first.request, { items: [item('stale-refresh')], next_cursor: null, limit: 5 });
  assert.deepEqual(late.items, []);
  const fresh = applyQueuePage(late, second.request, { items: [item('fresh')], next_cursor: null, limit: 5 });
  assert.deepEqual(fresh.items.map((row) => row.invitation_id), ['fresh']);
});

test('a failed next page keeps partial rows and never reports an empty queue', () => {
  let { state, request } = started();
  state = applyQueuePage(state, request, { items: [item('a')], next_cursor: 'next', limit: 5 });
  ({ state, request } = started(state, 'more'));
  state = failQueueRequest(state, request, 'Временная ошибка');
  assert.deepEqual(state.items.map((row) => row.invitation_id), ['a']);
  assert.equal(state.moreStatus, 'error');
  assert.equal(isQueueEmpty(state), false);
});

test('a stale denial cannot overwrite a newer identity or generation', () => {
  const loaded = { ...createQueueState('employer:user-2', 8), items: [item('current')], initialStatus: 'ready' as const, nextCursor: null };
  const oldIdentityRequest = { identity: 'candidate:user-1', generation: 3, kind: 'refresh' as const, cursor: null };
  assert.equal(denyQueueRequest(loaded, oldIdentityRequest), loaded);
  const oldGenerationRequest = { identity: 'employer:user-2', generation: 7, kind: 'refresh' as const, cursor: null };
  assert.equal(denyQueueRequest(loaded, oldGenerationRequest), loaded);
});

test('an active denial clears rows and invalidates that response generation', () => {
  let { state, request } = started();
  state = applyQueuePage(state, request, { items: [item('private')], next_cursor: 'next', limit: 5 });
  ({ state, request } = started(state, 'more'));
  const denied = denyQueueRequest(state, request);
  assert.deepEqual(denied.items, []);
  assert.equal(denied.initialStatus, 'error');
  assert.equal(denied.error, 'Очередь действий сейчас недоступна.');
  const late = applyQueuePage(denied, request, { items: [item('private-late')], next_cursor: null, limit: 5 });
  assert.deepEqual(late.items, []);
});

test('email verification loss during pagination clears private rows, while other 422 errors preserve partial data', () => {
  let { state, request } = started();
  state = applyQueuePage(state, request, { items: [item('private')], next_cursor: 'next', limit: 5 });
  ({ state, request } = started(state, 'more'));
  const denied = handleQueueFailure(state, request, 422, 'EMAIL_UNCONFIRMED', 'Email is not verified');
  assert.deepEqual(denied.items, []);
  assert.equal(denied.initialStatus, 'error');
  assert.equal(denied.error, 'Очередь действий сейчас недоступна.');
  assert.deepEqual(applyQueuePage(denied, request, { items: [item('late')], next_cursor: null, limit: 5 }).items, []);

  ({ state, request } = started(denied, 'refresh'));
  state = applyQueuePage(state, request, { items: [item('partial')], next_cursor: 'next', limit: 5 });
  ({ state, request } = started(state, 'more'));
  const ordinaryError = handleQueueFailure(state, request, 422, 'INVALID_CURSOR', 'Invalid cursor');
  assert.deepEqual(ordinaryError.items.map((row) => row.invitation_id), ['partial']);
  assert.equal(ordinaryError.moreStatus, 'error');
});
