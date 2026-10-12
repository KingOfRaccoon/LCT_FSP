import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { componentHarness, deferred, nodes, textContent } from './helpers/component-harness.mjs';

const component = fileURLToPath(new URL('../src/features/methodist/MethodistWorkspace.tsx', import.meta.url));
class MockRequestError extends Error { constructor(status) { super(`HTTP ${status}`); this.status = status; } }
const draft = (id, version, question, status = 'approved') => ({
  id, version, question, status, source_title: `${id} source`, source_reference: `https://${id}.test`,
  source_excerpt: `${id} excerpt`, competency_id: 'http-api', grade: 'junior',
  options: ['one', 'two', 'three', 'four'], correct_option: 0, rubric: `${id} rubric`,
});
const report = (id, version, title = `${id} report`) => ({
  draft_id: id, version, status: 'approved', source_title: title, sample_count: 1, correct_count: 1,
  accuracy: 1, source_counts: { synthetic: 1, pilot_record: 0 },
  accuracy_by_source: { synthetic: 1, pilot_record: null }, minimum_sample_for_statistics: 10,
  evidence_sources: ['synthetic'], human_results_claimed: false,
});
const draftExport = (id, version, question = `${id} export`) => ({
  draft_id: id, version, candidate_form: {
    format: 'draft-v1', source: { title: `${id} source`, reference: `https://${id}.test` },
    competency_id: 'http-api', grade: 'junior', question, options: ['one', 'two', 'three', 'four'],
  },
  private_answer_key: { format: 'draft-v1', draft_id: id, version, correct_option: 0, source_excerpt: `${id} excerpt`, rubric: `${id} rubric` },
  live_bank_published: false,
});

function workspace(get, { post = async () => { throw new Error('Unexpected POST'); }, onErrorMessage } = {}) {
  return componentHarness(component, 'MethodistWorkspace', {
    '../../api': { api: { get, post }, RequestError: MockRequestError, errorMessage: onErrorMessage ?? ((error) => error.message) },
  });
}

async function click(harness, label) {
  const button = nodes(harness.tree, (node) => node.type === 'button' && textContent(node).trim() === label)[0];
  assert.ok(button, `Missing button: ${label}`);
  button.props.onClick();
  harness.render();
  await harness.flush();
}

async function selectDraft(harness, question) {
  const button = nodes(harness.tree, (node) => node.type === 'button' && node.props.className?.includes('methodist-draft-row') && textContent(node).includes(question))[0];
  assert.ok(button, `Missing draft row: ${question}`);
  button.props.onClick();
  harness.render();
  await harness.flush();
}

async function ready(get, options) {
  const harness = workspace(get, options);
  harness.render();
  await harness.flush();
  return harness;
}

test('a report requested for draft A is discarded after selecting draft B', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A'), draft('B', 1, 'Question B')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await selectDraft(harness, 'Question B');
  pending.resolve(report('A', 1));
  await harness.flush();
  assert.doesNotMatch(textContent(harness.tree), /A report/);
  harness.unmount();
});

test('an export requested for draft A is discarded after selecting draft B', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A'), draft('B', 1, 'Question B')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить выгрузку');
  await selectDraft(harness, 'Question B');
  pending.resolve(draftExport('A', 1));
  await harness.flush();
  assert.doesNotMatch(textContent(harness.tree), /A export/);
  harness.unmount();
});

test('a stale report failure cannot replace the current draft or clear its access', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A'), draft('B', 1, 'Question B')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await selectDraft(harness, 'Question B');
  pending.reject(new Error('stale A failed'));
  await harness.flush();
  assert.match(textContent(harness.tree), /Question B/);
  assert.doesNotMatch(textContent(harness.tree), /stale A failed|Доступ к мастерской закрыт/);
  harness.unmount();
});

test('a stale authorization failure cannot clear the current selection', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A'), draft('B', 1, 'Question B')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await selectDraft(harness, 'Question B');
  pending.reject(new MockRequestError(403));
  await harness.flush();
  assert.match(textContent(harness.tree), /Question B/);
  assert.doesNotMatch(textContent(harness.tree), /Доступ к отчёту пилота закрыт|stale forbidden/);
  harness.unmount();
});

test('a report 403 from draft A is stale after switching A to B and back to A', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A'), draft('B', 1, 'Question B')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await selectDraft(harness, 'Question B');
  await selectDraft(harness, 'Question A');
  pending.reject(new MockRequestError(403));
  await harness.flush();
  assert.match(textContent(harness.tree), /Question AИсточник/);
  assert.doesNotMatch(textContent(harness.tree), /Доступ к отчёту пилота закрыт/);
  harness.unmount();
});

test('a report success from draft A is stale after switching A to B and back to A', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A'), draft('B', 1, 'Question B')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await selectDraft(harness, 'Question B');
  await selectDraft(harness, 'Question A');
  pending.resolve(report('A', 1, 'stale A report'));
  await harness.flush();
  assert.doesNotMatch(textContent(harness.tree), /stale A report/);
  harness.unmount();
});

test('a report with a mismatched draft id or version is not displayed', async () => {
  const harness = await ready((path) => path === '/methodist/drafts'
    ? Promise.resolve({ items: [draft('A', 2, 'Question A')] })
    : Promise.resolve(report('A', 1, 'wrong-version report')));
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  assert.doesNotMatch(textContent(harness.tree), /wrong-version report/);
  harness.unmount();
});

test('an in-flight report for an older version is discarded when the same draft advances', async () => {
  const pending = deferred();
  const harness = await ready(
    (path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A', 'draft')] }) : pending.promise,
    { post: async () => draft('A', 2, 'Question A v2', 'approved') },
  );
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  for (const checkbox of nodes(harness.tree, (node) => node.type === 'input' && node.props.type === 'checkbox')) checkbox.props.onChange({ target: { checked: true } });
  harness.render();
  const saveReview = nodes(harness.tree, (node) => node.type === 'button' && textContent(node).trim() === 'Сохранить решение')[0];
  assert.ok(saveReview);
  saveReview.props.onClick();
  harness.render();
  await harness.flush();
  pending.resolve(report('A', 1));
  await harness.flush();
  assert.doesNotMatch(textContent(harness.tree), /A report/);
  harness.unmount();
});

test('loaded report and export are hidden after the same draft advances to a new version', async () => {
  const harness = await ready(
    (path) => {
      if (path === '/methodist/drafts') return Promise.resolve({ items: [draft('A', 1, 'Question A', 'draft')] });
      if (path.endsWith('/pilot-report')) return Promise.resolve(report('A', 1, 'Version 1 report'));
      if (path.endsWith('/export')) return Promise.resolve(draftExport('A', 1, 'Version 1 export'));
      throw new Error(`Unexpected GET ${path}`);
    },
    { post: async () => draft('A', 2, 'Question A v2', 'approved') },
  );
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await click(harness, 'Загрузить выгрузку');
  for (const checkbox of nodes(harness.tree, (node) => node.type === 'input' && node.props.type === 'checkbox')) checkbox.props.onChange({ target: { checked: true } });
  harness.render();
  const saveReview = nodes(harness.tree, (node) => node.type === 'button' && textContent(node).trim() === 'Сохранить решение')[0];
  assert.ok(saveReview);
  saveReview.props.onClick();
  harness.render();
  await harness.flush();
  assert.match(textContent(harness.tree), /Пилот версии 2/);
  assert.doesNotMatch(textContent(harness.tree), /Version 1 report|Version 1 export/);
  harness.unmount();
});

test('a pending response cannot update state after unmount', async () => {
  const pending = deferred();
  let formattedErrors = 0;
  const harness = await ready(
    (path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A')] }) : pending.promise,
    { onErrorMessage: () => { formattedErrors += 1; return 'formatted'; } },
  );
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  harness.unmount();
  pending.reject(new Error('late failure'));
  await harness.flush();
  assert.equal(formattedErrors, 0);
});

test('an access-generation change invalidates an older report response', async () => {
  const pendingReport = deferred();
  const forbidden = new MockRequestError(403);
  const harness = await ready((path) => {
    if (path === '/methodist/drafts') return Promise.resolve({ items: [draft('A', 1, 'Question A')] });
    if (path.endsWith('/pilot-report')) return pendingReport.promise;
    return Promise.reject(forbidden);
  });
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  await click(harness, 'Монитор банка');
  await harness.flush();
  pendingReport.resolve(report('A', 1));
  await harness.flush();
  assert.match(textContent(harness.tree), /Доступ к мониторингу закрыт/);
  assert.doesNotMatch(textContent(harness.tree), /A report/);
  harness.unmount();
});

test('starting a new draft invalidates an in-flight export for the previous selection', async () => {
  const pending = deferred();
  const harness = await ready((path) => path === '/methodist/drafts' ? Promise.resolve({ items: [draft('A', 1, 'Question A')] }) : pending.promise);
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить выгрузку');
  await click(harness, 'Новое задание');
  pending.resolve(draftExport('A', 1));
  await harness.flush();
  await selectDraft(harness, 'Question A');
  assert.doesNotMatch(textContent(harness.tree), /A export/);
  harness.unmount();
});

test('a current forbidden report response closes access and clears protected data', async () => {
  const error = new MockRequestError(403);
  const harness = await ready((path) => path === '/methodist/drafts'
    ? Promise.resolve({ items: [draft('A', 1, 'Question A')] })
    : Promise.reject(error));
  await selectDraft(harness, 'Question A');
  await click(harness, 'Загрузить отчёт пилота');
  assert.match(textContent(harness.tree), /Доступ к отчёту пилота закрыт/);
  assert.doesNotMatch(textContent(harness.tree), /Question AИсточник/);
  harness.unmount();
});
