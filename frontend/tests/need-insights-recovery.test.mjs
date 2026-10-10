import assert from 'node:assert/strict';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { componentHarness, deferred, nodes, textContent } from './helpers/component-harness.mjs';

const componentFile = fileURLToPath(new URL('../src/features/adoption/NeedInsightsPanel.tsx', import.meta.url));
const need = (id = 'need-1', version = 1, title = `Роль v${version}`) => ({ id, version, title });
const insights = (id = 'need-1', version = 1, profilePool = 12) => ({
  need_id: id,
  need_version: version,
  generated_at: '2026-10-10T10:00:00Z',
  policy_version: 'policy-v1',
  scope: 'current_visible_platform_profiles',
  counts: { profile_pool: profilePool, confirmed: 4, needs_clarification: 2, needs_confirmation: 1, excluded: 3, demo: 2 },
  mandatory_requirements: { unknown_test_evidence_pairs: 2, blocked_by_matching_policy_count: 3 },
  requirements: [],
  salary: { scope: 'fixed_visible_specialization_grade_cohort', summary_available: false, known_count: null, compatible_count: null, unknown_count: null, incompatible_count: null, within_range_count: null, range_count_available: false, range_count_suppression_reason: 'privacy_protected', median_min_kopecks: null, median_max_kopecks: null, low_sample: true },
});
class RequestError extends Error {
  constructor(status, body) { super(body.error?.message ?? 'Request failed'); this.status = status; this.code = body.error?.code ?? 'REQUEST_FAILED'; }
}
const requestError = (status, code, message) => new RequestError(status, { error: { code, message } });
function harness(get) {
  return componentHarness(componentFile, 'NeedInsightsPanel', {
    '../../api': { api: { get }, errorMessage: (error) => error.message, skillLabels: {}, RequestError },
  });
}
function buttons(host) { return nodes(host.tree, (node) => node.type === 'button'); }
function click(host, label) {
  const button = buttons(host).find((node) => textContent(node).includes(label));
  assert.ok(button, `Expected a button containing “${label}”`);
  button.props.onClick();
}

test('a VERSION_CONFLICT recovery refreshes only local need state and retries insights at the returned version', async () => {
  const calls = [];
  const get = async (path) => {
    calls.push(path);
    if (path.endsWith('/insights?expected_version=1')) throw requestError(409, 'VERSION_CONFLICT', 'Версия потребности изменилась.');
    if (path === '/needs/need-1') return need('need-1', 2, 'Свежая роль');
    if (path.endsWith('/insights?expected_version=2')) return insights('need-1', 2, 22);
    throw new Error(`Unexpected request: ${path}`);
  };
  const host = harness(get);
  host.render({ need: need() });
  await host.flush();
  assert.match(textContent(host.tree), /Версия потребности изменилась/);
  assert.doesNotMatch(textContent(host.tree), /профилей в текущей видимой выборке/);
  click(host, 'Обновить');
  await host.flush();
  assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1', '/needs/need-1/insights?expected_version=2']);
  assert.match(textContent(host.tree), /Версия 2/);
  assert.match(textContent(host.tree), /22профилей в текущей видимой выборке/);
  assert.doesNotMatch(textContent(host.tree), /Свежая роль/);
  assert.equal(calls.some((path) => path.startsWith('PATCH ')), false);
});

test('a delayed response for old props cannot replace analytics for the newer need version', async () => {
  const old = deferred();
  const calls = [];
  const get = (path) => { calls.push(path); return path.endsWith('expected_version=1') ? old.promise : Promise.resolve(insights('need-1', 2, 22)); };
  const host = harness(get);
  host.render({ need: need('need-1', 1) });
  host.render({ need: need('need-1', 2) });
  await host.flush();
  old.resolve(insights('need-1', 1, 99));
  await host.flush();
  assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1/insights?expected_version=2']);
  assert.match(textContent(host.tree), /Версия 2/);
  assert.match(textContent(host.tree), /22профилей в текущей видимой выборке/);
  assert.doesNotMatch(textContent(host.tree), /99профилей/);
});

test('a delayed error for old props cannot hide analytics for the newer need version', async () => {
  const old = deferred();
  const get = (path) => path.endsWith('expected_version=1') ? old.promise : Promise.resolve(insights('need-1', 2, 22));
  const host = harness(get);
  host.render({ need: need('need-1', 1) });
  host.render({ need: need('need-1', 2) });
  await host.flush();
  old.reject(requestError(403, 'FORBIDDEN', 'Старый отказ доступа.'));
  await host.flush();
  assert.match(textContent(host.tree), /22профилей/);
  assert.doesNotMatch(textContent(host.tree), /Старый отказ доступа/);
});

test('changing props hides previously loaded analytics before the new request settles', async () => {
  const next = deferred();
  const get = (path) => path.endsWith('expected_version=1') ? Promise.resolve(insights('need-1', 1, 12)) : next.promise;
  const host = harness(get);
  host.render({ need: need('need-1', 1) });
  await host.flush();
  assert.match(textContent(host.tree), /12профилей/);
  host.render({ need: need('need-1', 2) });
  assert.doesNotMatch(textContent(host.tree), /12профилей/);
  next.resolve(insights('need-1', 2, 22));
  await host.flush();
  assert.match(textContent(host.tree), /22профилей/);
});

test('an unrelated conflict or transient network error retries the original insights version without refreshing the need', async (t) => {
  for (const failure of [requestError(409, 'OTHER_CONFLICT', 'Конфликт другого типа.'), new Error('Нет связи')]) {
    await t.test(failure.message, async () => {
      let attempts = 0;
      const calls = [];
      const get = async (path) => {
        calls.push(path);
        if (path === '/needs/need-1') return need('need-1', 2);
        if (++attempts === 1) throw failure;
        return insights('need-1', 1, 12);
      };
      const host = harness(get);
      host.render({ need: need() });
      await host.flush();
      click(host, 'Повторить');
      await host.flush();
      assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1/insights?expected_version=1']);
      assert.match(textContent(host.tree), /Версия 1/);
    });
  }
});

test('a second version conflict exposes the refreshed version and does not automatically loop', async () => {
  const calls = [];
  const get = async (path) => {
    calls.push(path);
    if (path.endsWith('/insights?expected_version=1')) throw requestError(409, 'VERSION_CONFLICT', 'Изменена версия 1.');
    if (path === '/needs/need-1') return need('need-1', 2);
    if (path.endsWith('/insights?expected_version=2')) throw requestError(409, 'VERSION_CONFLICT', 'Изменена версия 2.');
    throw new Error(`Unexpected request: ${path}`);
  };
  const host = harness(get);
  host.render({ need: need() });
  await host.flush();
  click(host, 'Обновить');
  await host.flush();
  assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1', '/needs/need-1/insights?expected_version=2']);
  assert.match(textContent(host.tree), /версия 2/iu);
  assert.equal(buttons(host).filter((node) => textContent(node).includes('Обновить')).length, 1);
});

test('recovery rejects a different need id and a version older than the requested snapshot', async (t) => {
  for (const [name, latest] of [['wrong id', need('other-need', 2)], ['older version', need('need-1', 0)]]) {
    await t.test(name, async () => {
      const calls = [];
      const get = async (path) => {
        calls.push(path);
        if (path.endsWith('/insights?expected_version=1')) throw requestError(409, 'VERSION_CONFLICT', 'Конфликт версии.');
        return latest;
      };
      const host = harness(get);
      host.render({ need: need() });
      await host.flush();
      click(host, 'Обновить');
      await host.flush();
      assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1']);
      assert.match(textContent(host.tree), /не удалось обновить потребность|другая потребность|версия потребности/iu);
      assert.doesNotMatch(textContent(host.tree), /профилей в текущей видимой выборке/);
    });
  }
});

test('recovery rejects analytics whose id or version differs from the refreshed snapshot', async (t) => {
  for (const [name, response] of [['wrong id', insights('other-need', 2, 99)], ['wrong version', insights('need-1', 1, 99)]]) {
    await t.test(name, async () => {
      const calls = [];
      const get = async (path) => {
        calls.push(path);
        if (path.endsWith('expected_version=1')) throw requestError(409, 'VERSION_CONFLICT', 'Конфликт версии.');
        if (path === '/needs/need-1') return need('need-1', 2);
        return response;
      };
      const host = harness(get);
      host.render({ need: need() });
      await host.flush();
      click(host, 'Обновить');
      await host.flush();
      assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1', '/needs/need-1/insights?expected_version=2']);
      assert.match(textContent(host.tree), /аналитика для другой версии потребности/iu);
      assert.doesNotMatch(textContent(host.tree), /99профилей/);
    });
  }
});

test('an access failure while refreshing clears prior analytics and exposes the current error', async (t) => {
  for (const [status, code, message] of [[401, 'UNAUTHENTICATED', 'Войдите заново.'], [403, 'FORBIDDEN', 'Доступ отозван.'], [404, 'NOT_FOUND', 'Потребность больше недоступна.']]) {
    await t.test(String(status), async () => {
      const calls = [];
      const get = async (path) => {
        calls.push(path);
        if (path.endsWith('expected_version=1')) throw requestError(409, 'VERSION_CONFLICT', 'Конфликт версии.');
        throw requestError(status, code, message);
      };
      const host = harness(get);
      host.render({ need: need() });
      await host.flush();
      click(host, 'Обновить');
      await host.flush();
      assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1']);
      assert.match(textContent(host.tree), new RegExp(message));
      assert.doesNotMatch(textContent(host.tree), /профилей в текущей видимой выборке/);
    });
  }
});

test('401, 403, and 404 hide analytics and report access or visibility failure', async (t) => {
  for (const [status, code, message] of [[401, 'UNAUTHENTICATED', 'Войдите в аккаунт.'], [403, 'FORBIDDEN', 'Нет доступа к этой потребности.'], [404, 'NOT_FOUND', 'Потребность не найдена.']]) {
    await t.test(String(status), async () => {
      const host = harness(async () => { throw requestError(status, code, message); });
      host.render({ need: need() });
      await host.flush();
      assert.match(textContent(host.tree), new RegExp(message));
      assert.doesNotMatch(textContent(host.tree), /профилей в текущей видимой выборке/);
    });
  }
});

test('an in-flight protected response after unmount is ignored', async () => {
  const pending = deferred();
  const host = harness(() => pending.promise);
  host.render({ need: need() });
  host.unmount();
  pending.resolve(insights('need-1', 1, 99));
  await host.flush();
  assert.doesNotMatch(textContent(host.tree), /99профилей/);
});

test('a later recovery cannot downgrade the panel snapshot after a second conflict', async () => {
  const calls = [];
  let needReads = 0;
  const get = async (path) => {
    calls.push(path);
    if (path.endsWith('expected_version=1')) throw requestError(409, 'VERSION_CONFLICT', 'Конфликт версии 1.');
    if (path.endsWith('expected_version=2')) throw requestError(409, 'VERSION_CONFLICT', 'Конфликт версии 2.');
    if (path === '/needs/need-1') return ++needReads === 1 ? need('need-1', 2) : need('need-1', 1);
    throw new Error(`Unexpected request: ${path}`);
  };
  const host = harness(get);
  host.render({ need: need('need-1', 1) });
  await host.flush();
  click(host, 'Обновить');
  await host.flush();
  assert.match(textContent(host.tree), /Текущая версия потребности: 2/);
  click(host, 'Обновить');
  await host.flush();
  assert.deepEqual(calls, ['/needs/need-1/insights?expected_version=1', '/needs/need-1', '/needs/need-1/insights?expected_version=2', '/needs/need-1']);
  assert.match(textContent(host.tree), /более старую версию/);
  assert.match(textContent(host.tree), /Текущая версия потребности: 2/);
});
