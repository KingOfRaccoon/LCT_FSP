import assert from 'node:assert/strict';
import test from 'node:test';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createServer } from 'vite';
import { componentHarness, nodes, textContent } from './helpers/component-harness.mjs';

const frontendRoot = new URL('../', import.meta.url).pathname;
const sandboxPath = new URL('../src/features/sandbox/CodeRunnerSandbox.tsx', import.meta.url).pathname;
const candidatePath = new URL('../src/features/candidate/candidate.tsx', import.meta.url).pathname;

async function loadPolicy(flag) {
  const server = await createServer({
    root: frontendRoot,
    configFile: false,
    define: { 'import.meta.env.VITE_DEMO_PROCTORING': JSON.stringify(flag) },
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  });
  try {
    return await server.ssrLoadModule('/src/features/proctoring/proctoring-policy.ts');
  } finally {
    await server.close();
  }
}

async function renderApp(flag, route, operationsPermissions = []) {
  const server = await createServer({
    root: frontendRoot,
    configFile: false,
    define: { 'import.meta.env.VITE_DEMO_PROCTORING': JSON.stringify(flag) },
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } });
  client.setQueryData(['me'], {
    user_id: 'synthetic-employer', account_type: 'employer', email: 'employer@example.test', email_verified: true,
    company: { name: 'Demo employer' }, candidate_profile: null, operations_permissions: operationsPermissions,
  });
  try {
    const { default: App } = await server.ssrLoadModule('/src/App.tsx');
    return renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(MemoryRouter, { initialEntries: [route] }, createElement(App))));
  } finally {
    client.clear();
    await server.close();
  }
}

test('demo proctoring is disabled by default and requires the explicit true build flag', async () => {
  const storageDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => 'true' } });
  try {
    const nodePolicy = await import('../src/features/proctoring/proctoring-policy.ts');
    assert.equal(nodePolicy.isDemoProctoringEnabled(), false, 'a local storage value cannot enable proctoring');
  } finally {
    if (storageDescriptor) Object.defineProperty(globalThis, 'localStorage', storageDescriptor);
    else delete globalThis.localStorage;
  }
  const disabled = await loadPolicy('false');
  const enabled = await loadPolicy('true');
  assert.equal(disabled.isDemoProctoringEnabled(), false);
  assert.equal(disabled.canOfferDemoCapture({ isDemoAttempt: true, processingAccepted: true, secureContext: true, mediaApisAvailable: true }), false);
  assert.equal(enabled.isDemoProctoringEnabled(), true);
  assert.equal(enabled.canOfferDemoCapture({ isDemoAttempt: false, processingAccepted: true, secureContext: true, mediaApisAvailable: true }), false);
  assert.equal(enabled.canOfferDemoCapture({ isDemoAttempt: true, processingAccepted: false, secureContext: true, mediaApisAvailable: true }), false);
  assert.equal(enabled.canOfferDemoCapture({ isDemoAttempt: true, processingAccepted: true, secureContext: false, mediaApisAvailable: true }), false);
  assert.equal(enabled.canOfferDemoCapture({ isDemoAttempt: true, processingAccepted: true, secureContext: true, mediaApisAvailable: false }), false);
  assert.equal(enabled.canOfferDemoCapture({ isDemoAttempt: true, processingAccepted: true, secureContext: true, mediaApisAvailable: true }), true);
});

test('proctoring route is absent by default and available only in the explicit demo build', async () => {
  const disabled = await renderApp('false', '/proctoring');
  const enabled = await renderApp('true', '/proctoring');
  const disabledNav = await renderApp('false', '/');
  const enabledNav = await renderApp('true', '/');
  assert.doesNotMatch(disabled, /Симуляция локальных сигналов|Проверка фрода/u);
  assert.match(enabled, /Симуляция локальных сигналов/u);
  assert.doesNotMatch(disabledNav, /href="\/proctoring"|Демо-журнал наблюдений/u);
  assert.match(enabledNav, /href="\/proctoring"[^>]*>.*Демо-журнал наблюдений/u);
  assert.doesNotMatch(disabledNav, /Сменить роль: Кандидат|Сменить на: Эксперт|Сменить на: Сдающий/u);
});

test('server review queue is exposed only to the operator review permission', async () => {
  const withoutPermission = await renderApp('false', '/operations/proctoring');
  const withPermission = await renderApp('false', '/operations/proctoring', ['proctoring_review']);
  assert.doesNotMatch(withoutPermission, /Очередь серверной проверки/u);
  assert.match(withPermission, /Очередь серверной проверки/u);
});

test('local demo fraud observations do not hide server assessment feedback', () => {
  const harness = componentHarness(candidatePath, 'AssessmentResultPanel', {
    '../../api': { api: {}, errorMessage: (error) => String(error), skillLabels: {} },
    '../../theme': { ThemeToggle: () => null },
    '../sandbox/CodeRunnerSandbox': { CodeRunnerSandbox: () => null },
  });
  const tree = harness.render({
    attempt: {
      demo: true,
      policy_version: 'policy-v1',
      requested_grade: 'junior',
      form_version: 'form-v1',
      rubric_version: 'rubric-v1',
      result: {
        total_score: 19,
        passed: true,
        grade_awarded: 'junior',
        block_scores: [
          { block_id: 'python-basics', score: 5 },
          { block_id: 'http-api', score: 5 },
          { block_id: 'sql-transactions', score: 5 },
          { block_id: 'reliability', score: 4 },
        ],
        competency_summary: [],
      },
    },
    demoObservationCount: 2,
  });

  const feedback = nodes(tree, (node) => typeof node.type === 'function' && node.type.name === 'ResultCompetencyFeedback')[0];
  assert.ok(feedback, 'server assessment feedback remains mounted alongside local demo observations');
  const rendered = [tree, feedback.type(feedback.props)];
  assert.match(textContent(rendered), /19 \/ 24/u);
  assert.match(textContent(rendered), /DEMO · ЛОКАЛЬНЫЙ ЖУРНАЛ НАБЛЮДЕНИЙ/iu);
  assert.match(textContent(rendered), /не меняет серверную оценку/u);
  assert.doesNotMatch(textContent(rendered), /подтверждённый фрод|экзамен не зачтён/u);
  harness.unmount();
});

test('sandbox examples never report arbitrary code as executed or passed', async () => {
  const harness = componentHarness(sandboxPath, 'CodeRunnerSandbox');
  let tree = harness.render();
  const editor = nodes(tree, (node) => node.type === 'textarea')[0];
  editor.props.onChange({ target: { value: 'class LRUCache: def get( def put( self.capacity self.order return -1' } });
  tree = harness.render();
  const action = nodes(tree, (node) => node.type === 'button').find((node) => /Показать примеры/i.test(textContent(node)));
  assert.ok(action, 'practice examples remain reachable from the editor');
  action.props.onClick();
  tree = harness.render();

  assert.doesNotMatch(textContent(tree), /PASSED|тестов пройдено|\bms\b/u);
  assert.match(textContent(tree), /не запускается|не выполняется/u);
  assert.match(textContent(tree), /Ожидаемое поведение/u);
  harness.unmount();
});
