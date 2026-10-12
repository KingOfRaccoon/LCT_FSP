import assert from 'node:assert/strict';
import test from 'node:test';
import {
  isContactResponseCurrent,
  isRetryableSaveError,
  readContactIfAuthorized,
  reconcileAnswerDraft,
  runBoundedSave,
  selectedAnswerCount,
  snapshotMatches,
} from './recovery.ts';

test('a version conflict stops bounded autosave retries', async () => {
  let saves = 0;
  let reconciles = 0;
  await assert.rejects(runBoundedSave({
    save: async () => { saves += 1; throw { status: 409 }; },
    reconcile: async () => { reconciles += 1; return {}; },
    matches: () => false,
    wait: async () => {},
  }));
  assert.equal(saves, 1);
  assert.equal(reconciles, 1);
  assert.equal(isRetryableSaveError({ status: 409 }), false);
  assert.equal(isRetryableSaveError({ status: 403 }), false);
  assert.equal(isRetryableSaveError({ status: 404 }), false);
});

test('a lost save response reconciles the server snapshot before retrying', async () => {
  let saves = 0;
  let reconciles = 0;
  const saved = { answers: { q1: 'a' } };
  const result = await runBoundedSave({
    save: async () => { saves += 1; throw new Error('Нет связи с сервером. Проверьте подключение и повторите действие.'); },
    reconcile: async () => { reconciles += 1; return saved; },
    matches: (state) => snapshotMatches(state.answers, { q1: 'a' }),
    wait: async () => {},
  });
  assert.deepEqual(result, saved);
  assert.equal(saves, 1);
  assert.equal(reconciles, 1);
});

test('transient server failures retry only within the fixed bound', async () => {
  let saves = 0;
  const delays: number[] = [];
  const result = await runBoundedSave({
    save: async () => { saves += 1; if (saves < 3) throw { status: 503 }; return 'ack'; },
    reconcile: async () => null,
    matches: () => false,
    wait: async (delayMs) => { delays.push(delayMs); },
  });
  assert.equal(result, 'ack');
  assert.equal(saves, 3);
  assert.deepEqual(delays, [500, 1000]);
});

test('contact responses apply only while reveal, visibility, auth, and request generation remain current', () => {
  const current = { requestId: 4, currentRequestId: 4, revealed: true, visible: true, authenticated: true };
  assert.equal(isContactResponseCurrent(current), true);
  assert.equal(isContactResponseCurrent({ ...current, currentRequestId: 5 }), false);
  assert.equal(isContactResponseCurrent({ ...current, visible: false }), false);
  assert.equal(isContactResponseCurrent({ ...current, authenticated: false }), false);
  assert.equal(isContactResponseCurrent({ ...current, revealed: false }), false);
});

test('contacts are not read before explicit reveal', async () => {
  let reads = 0;
  const result = await readContactIfAuthorized({
    clear: () => {},
    read: async () => { reads += 1; return ['private@example.test']; },
    revealed: false,
    visible: true,
    authenticated: true,
  });
  assert.deepEqual(result, { status: 'skipped' });
  assert.equal(reads, 0);
});

test('a failed contact revalidation returns an error after the old display is cleared', async () => {
  const displayedContact: string[] = ['old@example.test'];
  const order: string[] = [];
  const result = await readContactIfAuthorized({
    clear: () => { displayedContact.length = 0; order.push('cleared'); },
    read: async () => { order.push('read'); throw { status: 403 }; },
    revealed: true,
    visible: true,
    authenticated: true,
  });
  assert.deepEqual(displayedContact, []);
  assert.deepEqual(order, ['cleared', 'read']);
  assert.equal(result.status, 'error');
});

test('saved answer counts use the acknowledged server snapshot, not the edited draft', () => {
  const draft = { q1: 'a', q2: 'b', q3: 'c' };
  const serverAnswers = [{ selected_option_id: 'a' }, { selected_option_id: null }, { selected_option_id: 'c' }];
  assert.equal(selectedAnswerCount(serverAnswers), 2);
  assert.equal(snapshotMatches({ q1: 'a', q3: 'c' }, draft), false);
});

test('an attempt refetch preserves a locally edited draft while keeping the server acknowledgement separate', () => {
  const result = reconcileAnswerDraft({
    initialized: true,
    draft: { q1: 'local-unsaved' },
    acknowledged: { q1: 'server-saved' },
    server: { q1: 'server-saved' },
  });
  assert.deepEqual(result, { answers: { q1: 'local-unsaved' }, shouldAdoptServer: false, conflict: false });
});

test('a refetch reports a conflict without replacing dirty answers when another writer changed the server snapshot', () => {
  const result = reconcileAnswerDraft({
    initialized: true,
    draft: { q1: 'local-unsaved' },
    acknowledged: { q1: 'previous-server-answer' },
    server: { q1: 'other-tab-answer' },
  });
  assert.deepEqual(result, { answers: { q1: 'local-unsaved' }, shouldAdoptServer: false, conflict: true });
});

test('initial attempt hydration and later clean refetches adopt the authoritative server snapshot', () => {
  const initial = reconcileAnswerDraft({ initialized: false, draft: {}, acknowledged: {}, server: { q1: 'saved' } });
  assert.deepEqual(initial, { answers: { q1: 'saved' }, shouldAdoptServer: true, conflict: false });
  const cleanRefetch = reconcileAnswerDraft({ initialized: true, draft: { q1: 'saved' }, acknowledged: { q1: 'saved' }, server: { q1: 'new-server-answer' } });
  assert.deepEqual(cleanRefetch, { answers: { q1: 'new-server-answer' }, shouldAdoptServer: true, conflict: false });
});

test('a refetch acknowledges the draft when the server has since saved it', () => {
  const result = reconcileAnswerDraft({
    initialized: true,
    draft: { q1: 'locally-pending' },
    acknowledged: { q1: 'previous-server-answer' },
    server: { q1: 'locally-pending' },
  });
  assert.deepEqual(result, { answers: { q1: 'locally-pending' }, shouldAdoptServer: true, conflict: false });
});

test('deadline conflict accepts terminal attempt state even when the pending draft was not saved', async () => {
  const finalized = { status: 'evaluated', answers: { q1: 'last-server-answer' } };
  const result = await runBoundedSave({
    save: async () => { throw { status: 409 }; },
    reconcile: async () => finalized,
    matches: (state) => snapshotMatches(state.answers, { q1: 'pending-local-answer' }),
    isTerminal: (state) => state.status !== 'in_progress',
    wait: async () => {},
  });
  assert.deepEqual(result, finalized);
});
