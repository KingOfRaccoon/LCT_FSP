import assert from 'node:assert/strict';
import test from 'node:test';
import path from 'node:path';
import { RequestError } from '../src/api.ts';
import { componentHarness, deferred, nodes } from './helpers/component-harness.mjs';

const root = path.resolve('src/features/recruiting');

const candidate = (id, name) => ({
  id,
  display_name: name,
  headline: null,
  verified_grade: null,
  declared_grade: null,
});

const record = (id, stage = 'reviewing') => ({
  id: `record-${id}`,
  candidate_id: id,
  company_id: 'company-1',
  need_id: null,
  version: 1,
  stage,
  note: null,
  updated_at: '2026-10-10T14:00:00Z',
  candidate_visibility: 'public',
  candidate_display_name: `Кандидат ${id}`,
  need_title: null,
});
const noExistingRecords = [];

test('candidate autocomplete follows every cursor page so later candidates are searchable', async () => {
  const cursors = [];
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async (_url, payload) => {
          cursors.push(payload.cursor);
          return payload.cursor
            ? { items: [candidate('candidate-2', 'Поздний кандидат')], next_cursor: null }
            : { items: [candidate('candidate-1', 'Первый кандидат')], next_cursor: 'page-2' };
        },
      },
      errorMessage: (error) => String(error),
    },
  });

  harness.render({ value: '', onChange() {}, existingRecords: noExistingRecords });
  await harness.flush();
  const input = nodes(harness.tree, (node) => node.type === 'input')[0];
  input.props.onFocus();
  await harness.flush();

  assert.deepEqual(cursors, [null, 'page-2']);
  assert.match(JSON.stringify(harness.tree), /Поздний кандидат/);
  harness.unmount();
});

test('candidate autocomplete stops on a repeated cursor and deduplicates records', async () => {
  let calls = 0;
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async () => {
          calls++;
          return { items: [candidate('same-id', calls === 1 ? 'Из поиска' : 'Дубликат')], next_cursor: 'repeat' };
        },
      },
      errorMessage: (error) => String(error),
    },
  });

  harness.render({ value: '', onChange() {}, existingRecords: noExistingRecords });
  await harness.flush();
  assert.equal(calls, 2);
  nodes(harness.tree, (node) => node.type === 'input')[0].props.onFocus();
  await harness.flush();
  assert.match(JSON.stringify(harness.tree), /Из поиска/);
  assert.doesNotMatch(JSON.stringify(harness.tree), /Дубликат/);
  harness.unmount();
});

test('candidate autocomplete reports an empty cursor without requesting a duplicate first page', async () => {
  let calls = 0;
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async () => {
          calls++;
          return { items: [candidate('first', 'Первый')], next_cursor: '' };
        },
      },
      errorMessage: (error) => String(error),
    },
  });

  harness.render({ value: '', onChange() {}, existingRecords: noExistingRecords });
  await harness.flush();
  nodes(harness.tree, (node) => node.type === 'input')[0].props.onFocus();
  await harness.flush();
  assert.equal(calls, 1);
  assert.match(JSON.stringify(harness.tree), /пустой курсор/);
  harness.unmount();
});

test('candidate autocomplete exposes a failed later page and can retry it', async () => {
  let calls = 0;
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async (_url, payload) => {
          calls++;
          if (!payload.cursor) return { items: [candidate('first', 'Первый')], next_cursor: 'later' };
          if (calls === 2) throw new Error('Страница недоступна');
          return { items: [candidate('later', 'Второй')], next_cursor: null };
        },
      },
      errorMessage: (error) => error.message,
    },
  });

  harness.render({ value: '', onChange() {}, existingRecords: noExistingRecords });
  await harness.flush();
  let input = nodes(harness.tree, (node) => node.type === 'input')[0];
  input.props.onFocus();
  await harness.flush();

  assert.match(JSON.stringify(harness.tree), /Страница недоступна/);
  let retry = nodes(harness.tree, (node) => node.type === 'button' && /Повторить/.test(JSON.stringify(node)))[0];
  assert.ok(retry, 'failed candidate page should offer a retry');
  retry.props.onClick();
  await harness.flush();

  assert.equal(calls, 4);
  assert.match(JSON.stringify(harness.tree), /Второй/);
  harness.unmount();
});

test('candidate retry removes a previously public candidate missing from the fresh result', async () => {
  let calls = 0;
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async (_url, payload) => {
          calls++;
          if (calls === 1) return { items: [candidate('revoked', 'Скрытый после отзыва')], next_cursor: 'later' };
          if (calls === 2) throw new Error('Страница недоступна');
          assert.equal(payload.cursor, null);
          return { items: [], next_cursor: null };
        },
      },
      errorMessage: (error) => error.message,
    },
  });

  harness.render({ value: '', onChange() {}, existingRecords: noExistingRecords });
  await harness.flush();
  nodes(harness.tree, (node) => node.type === 'input')[0].props.onFocus();
  await harness.flush();
  assert.match(JSON.stringify(harness.tree), /Скрытый после отзыва/);
  nodes(harness.tree, (node) => node.type === 'button' && /Повторить/.test(JSON.stringify(node)))[0].props.onClick();
  await harness.flush();

  assert.doesNotMatch(JSON.stringify(harness.tree), /Скрытый после отзыва/);
  harness.unmount();
});

test('candidate partial-page error does not claim that only saved-record candidates are shown', async () => {
  const savedRecord = { candidate_id: 'saved', candidate_display_name: 'Сохранённый', candidate_visibility: 'hidden' };
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async (_url, payload) => {
          if (!payload.cursor) return { items: [candidate('public', 'Публичный')], next_cursor: 'later' };
          throw new Error('Страница недоступна');
        },
      },
      errorMessage: (error) => error.message,
    },
  });

  harness.render({ value: '', onChange() {}, existingRecords: [savedRecord] });
  await harness.flush();
  nodes(harness.tree, (node) => node.type === 'input')[0].props.onFocus();
  await harness.flush();

  assert.match(JSON.stringify(harness.tree), /загружен частично/i);
  assert.doesNotMatch(JSON.stringify(harness.tree), /только кандидаты из уже сохранённых записей/i);
  harness.unmount();
});

test('hidden existing-record state redacts cached and late public candidate details', async () => {
  const latePage = deferred();
  const publicCandidate = {
    ...candidate('candidate-private', 'Private Candidate Name'),
    headline: 'Private public headline',
    verified_grade: 'middle',
    declared_grade: 'junior',
  };
  const staleSelection = {
    id: publicCandidate.id,
    displayName: publicCandidate.display_name,
    headline: publicCandidate.headline,
    verifiedGrade: publicCandidate.verified_grade,
    declaredGrade: publicCandidate.declared_grade,
    visibility: 'public',
  };
  const hiddenRecord = {
    candidate_id: publicCandidate.id,
    candidate_display_name: null,
    candidate_visibility: 'hidden',
  };
  const harness = componentHarness(path.join(root, 'CandidateAutocomplete.tsx'), 'CandidateAutocomplete', {
    '../../api': {
      api: {
        post: async (_url, payload) => payload.cursor
          ? latePage.promise
          : { items: [publicCandidate], next_cursor: 'late-page' },
      },
      errorMessage: (error) => error.message,
    },
  });
  const props = {
    value: publicCandidate.id,
    selectedCandidate: staleSelection,
    onChange() {},
    filterMode: true,
    existingRecords: noExistingRecords,
  };

  harness.render(props);
  await harness.flush();
  nodes(harness.tree, (node) => node.type === 'input')[0].props.onFocus();
  await harness.flush();
  assert.match(JSON.stringify(harness.tree), /Private Candidate Name/);

  const hiddenFrame = harness.render({ ...props, existingRecords: [hiddenRecord] });
  assert.doesNotMatch(JSON.stringify(hiddenFrame), /Private Candidate Name|Private public headline|middle|junior/);
  await harness.flush();
  assert.doesNotMatch(JSON.stringify(harness.tree), /Private Candidate Name|Private public headline|middle|junior/);
  assert.match(JSON.stringify(harness.tree), /Профиль скрыт/);

  latePage.resolve({ items: [publicCandidate], next_cursor: null });
  await harness.flush();
  assert.doesNotMatch(JSON.stringify(harness.tree), /Private Candidate Name|Private public headline|middle|junior/);

  harness.render({ ...props, existingRecords: [hiddenRecord], readOnly: true, filterMode: false });
  await harness.flush();
  assert.match(JSON.stringify(harness.tree), /Профиль скрыт/);
  assert.doesNotMatch(JSON.stringify(harness.tree), /Private Candidate Name|Private public headline|middle|junior/);
  harness.unmount();
});

test('need selector includes needs after the first cursor page', async () => {
  const requests = [];
  const firstPage = Array.from({ length: 100 }, (_, index) => ({
    id: `need-${index}`,
    title: `Потребность ${index}`,
    target_grade: 'junior',
    status: 'active',
  }));
  const harness = componentHarness(path.join(root, 'NeedSelect.tsx'), 'NeedSelect', {
    '../../api': {
      api: {
        get: async (url) => {
          requests.push(url);
          return requests.length === 1
            ? { items: firstPage, page: { next_cursor: 'needs-page-2', limit: 100 } }
            : { items: [{ id: 'need-late', title: 'Поздняя потребность', target_grade: 'middle', status: 'active' }], page: { next_cursor: null, limit: 100 } };
        },
      },
      errorMessage: (error) => String(error),
    },
  });

  harness.render({ value: '', onChange() {} });
  await harness.flush();
  assert.equal(requests.length, 2);
  assert.match(requests[1], /cursor=needs-page-2/);
  assert.match(JSON.stringify(harness.tree), /Поздняя потребность/);
  harness.unmount();
});

test('need selector retries a failed cursor page and preserves its selected value', async () => {
  let calls = 0;
  const selectedNeed = { id: 'need-late', title: 'Поздняя потребность', target_grade: 'middle', status: 'active' };
  const harness = componentHarness(path.join(root, 'NeedSelect.tsx'), 'NeedSelect', {
    '../../api': {
      api: {
        get: async (url) => {
          calls++;
          if (url.includes('cursor=needs-page-2') && calls === 2) throw new Error('Страница недоступна');
          if (url.includes('cursor=needs-page-2')) return { items: [selectedNeed], page: { next_cursor: null, limit: 100 } };
          return { items: [{ id: 'need-first', title: 'Первая потребность', target_grade: 'junior', status: 'active' }], page: { next_cursor: 'needs-page-2', limit: 100 } };
        },
      },
      errorMessage: (error) => error.message,
    },
  });

  harness.render({ value: 'need-late', onChange() {}, includeAllOption: false });
  await harness.flush();
  const retry = nodes(harness.tree, (node) => node.type === 'button' && /Повторить загрузку потребностей/.test(JSON.stringify(node)))[0];
  assert.ok(retry, 'partial need list should offer a retry');
  retry.props.onClick();
  await harness.flush();

  const select = nodes(harness.tree, (node) => node.type === 'select')[0];
  assert.equal(calls, 4);
  assert.equal(select.props.value, 'need-late');
  assert.match(JSON.stringify(harness.tree), /Поздняя потребность/);
  harness.unmount();
});

test('stage failure only rolls back its row and preserves a concurrently saved record', async () => {
  const firstMutation = deferred();
  const secondMutation = deferred();
  const initial = [record('candidate-1'), record('candidate-2')];
  const harness = componentHarness(path.join(root, 'RecruitingWorkspacePage.tsx'), 'RecruitingWorkspacePage', {
    '../../api': {
      api: {
        get: async () => ({ items: initial, next_cursor: null }),
        put: (url) => url.endsWith('candidate-1') ? firstMutation.promise : secondMutation.promise,
        download() {},
      },
      errorMessage: (error) => error.message,
      RequestError,
    },
  });

  harness.render();
  await harness.flush();
  let board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  const onStageChange = board.props.onStageChange;
  const firstRequest = onStageChange(initial[0], 'shortlisted');
  const secondRequest = onStageChange(initial[1], 'shortlisted');
  secondMutation.resolve({ ...initial[1], stage: 'shortlisted', version: 2 });
  await secondRequest;
  firstMutation.reject(new Error('conflict'));
  await firstRequest;
  await harness.flush();

  board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  assert.deepEqual(board.props.records.map((item) => [item.id, item.stage]), [
    ['record-candidate-1', 'reviewing'],
    ['record-candidate-2', 'shortlisted'],
  ]);
  harness.unmount();
});

test('stage failure after navigating filters does not restore the previous result set', async () => {
  const mutation = deferred();
  const first = record('candidate-old');
  const filtered = record('candidate-filtered');
  let listCalls = 0;
  const harness = componentHarness(path.join(root, 'RecruitingWorkspacePage.tsx'), 'RecruitingWorkspacePage', {
    '../../api': {
      api: {
        get: async (url) => {
          listCalls++;
          return url.includes('candidate_id=candidate-filtered')
            ? { items: [filtered], next_cursor: null }
            : { items: [first], next_cursor: null };
        },
        put: () => mutation.promise,
        download() {},
      },
      errorMessage: (error) => error.message,
      RequestError,
    },
  });

  harness.render();
  await harness.flush();
  let board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  const pending = board.props.onStageChange(first, 'shortlisted');
  let candidateFilter = nodes(harness.tree, (node) => node.type?.name === 'CandidateAutocomplete')[0];
  candidateFilter.props.onChange({ id: 'candidate-filtered' });
  await harness.flush();
  const filteredForm = nodes(harness.tree, (node) => node.type === 'form')[0];
  filteredForm.props.onSubmit({ preventDefault() {} });
  await harness.flush();
  mutation.reject(new Error('conflict'));
  await pending;
  await harness.flush();

  board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  assert.ok(listCalls >= 2);
  assert.deepEqual(board.props.records.map((item) => item.id), ['record-candidate-filtered']);
  harness.unmount();
});

test('late stage success reloads current results instead of overwriting a refreshed same-id row', async () => {
  const mutation = deferred();
  const initial = record('candidate-shared');
  const refreshed = { ...initial, stage: 'invited', version: 3, updated_at: '2026-10-11T12:00:00Z' };
  let listCalls = 0;
  const harness = componentHarness(path.join(root, 'RecruitingWorkspacePage.tsx'), 'RecruitingWorkspacePage', {
    '../../api': {
      api: {
        get: async (url) => {
          listCalls++;
          if (url.includes('candidate_id=candidate-filtered')) return { items: [refreshed], next_cursor: null };
          return { items: [initial], next_cursor: null };
        },
        put: () => mutation.promise,
        download() {},
      },
      errorMessage: (error) => error.message,
      RequestError,
    },
  });

  harness.render();
  await harness.flush();
  let board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  const pending = board.props.onStageChange(initial, 'shortlisted');
  nodes(harness.tree, (node) => node.type?.name === 'CandidateAutocomplete')[0]
    .props.onChange({ id: 'candidate-filtered' });
  await harness.flush();
  nodes(harness.tree, (node) => node.type === 'form')[0].props.onSubmit({ preventDefault() {} });
  await harness.flush();

  mutation.resolve({ ...initial, stage: 'shortlisted', version: 2 });
  await pending;
  await harness.flush();
  board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];

  assert.ok(listCalls >= 3, 'late success should reload the current list scope');
  assert.deepEqual(board.props.records.map((item) => [item.id, item.stage, item.version]), [
    [refreshed.id, 'invited', 3],
  ]);
  harness.unmount();
});

test('VERSION_CONFLICT refreshes the affected row version so a stage retry can succeed', async () => {
  const stale = { ...record('candidate-conflict'), need_id: 'need-old' };
  const refreshed = { ...stale, need_id: 'need-new', version: 2, note: 'Обновлено параллельно' };
  const updated = { ...refreshed, stage: 'shortlisted', version: 3 };
  const other = record('candidate-other');
  const otherUpdated = { ...other, stage: 'shortlisted', version: 2 };
  const refreshRequest = deferred();
  const otherMutation = deferred();
  const payloads = [];
  let puts = 0;
  const harness = componentHarness(path.join(root, 'RecruitingWorkspacePage.tsx'), 'RecruitingWorkspacePage', {
    '../../api': {
      api: {
        get: async (url) => {
          if (url.includes('candidate_id=candidate-conflict')) {
            return url.includes('need_id=need-old')
              ? { items: [], next_cursor: null }
              : refreshRequest.promise;
          }
          return { items: [stale, other], next_cursor: null };
        },
        put: (url, payload) => {
          payloads.push({ candidate_id: url.split('candidate_id=')[1] ?? url.split('/').at(-1), ...payload });
          if (url.endsWith('candidate-other')) return otherMutation.promise;
          puts++;
          if (puts === 1) return Promise.reject(new RequestError(409, { error: { code: 'VERSION_CONFLICT', message: 'Changed' } }));
          return Promise.resolve(updated);
        },
        download() {},
      },
      errorMessage: (error) => error.message,
      RequestError,
    },
  });

  harness.render();
  await harness.flush();
  let board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  const conflictRequest = board.props.onStageChange(stale, 'shortlisted');
  await Promise.resolve();
  await harness.flush();

  board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  const otherRequest = board.props.onStageChange(other, 'shortlisted');
  otherMutation.resolve(otherUpdated);
  await otherRequest;
  refreshRequest.resolve({ items: [refreshed], next_cursor: null });
  await conflictRequest;
  await harness.flush();

  board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  const current = board.props.records.find((item) => item.id === stale.id);
  assert.equal(current.version, 2);
  assert.equal(current.note, 'Обновлено параллельно');
  assert.deepEqual(board.props.records.find((item) => item.id === other.id), otherUpdated);
  await board.props.onStageChange(current, 'shortlisted');
  await harness.flush();

  assert.equal(payloads.findLast((payload) => payload.candidate_id === 'candidate-conflict').expected_version, 2);
  assert.equal(nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0].props.records.find((item) => item.id === stale.id).version, 3);
  harness.unmount();
});

test('VERSION_CONFLICT removes a moved record from an applied need filter', async () => {
  const stale = { ...record('candidate-moved'), need_id: 'need-old' };
  const moved = { ...stale, need_id: 'need-new', version: 2 };
  const harness = componentHarness(path.join(root, 'RecruitingWorkspacePage.tsx'), 'RecruitingWorkspacePage', {
    '../../api': {
      api: {
        get: async (url) => {
          if (url.startsWith('/needs')) return { items: [], page: { next_cursor: null, limit: 100 } };
          if (url.includes('candidate_id=candidate-moved')) {
            return url.includes('need_id=need-old')
              ? { items: [], next_cursor: null }
              : { items: [moved], next_cursor: null };
          }
          if (url.includes('need_id=need-old')) return { items: [stale], next_cursor: null };
          return { items: [stale], next_cursor: null };
        },
        put: () => Promise.reject(new RequestError(409, { error: { code: 'VERSION_CONFLICT', message: 'Changed' } })),
        download() {},
      },
      errorMessage: (error) => error.message,
      RequestError,
    },
  });

  harness.render();
  await harness.flush();
  nodes(harness.tree, (node) => node.type?.name === 'NeedSelect')[0].props.onChange('need-old');
  await harness.flush();
  nodes(harness.tree, (node) => node.type === 'form')[0].props.onSubmit({ preventDefault() {} });
  await harness.flush();
  const board = nodes(harness.tree, (node) => node.type?.name === 'RecruitingKanban')[0];
  await board.props.onStageChange(stale, 'shortlisted');
  await harness.flush();

  const funnel = nodes(harness.tree, (node) => node.type?.name === 'RecruitingFunnel')[0];
  assert.deepEqual(funnel.props.records, []);
  harness.unmount();
});
