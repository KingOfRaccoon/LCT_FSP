import assert from 'node:assert/strict';
import { before, after, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createServer } from 'vite';

let vite;
let App;
before(async () => {
  vite = await createServer({ root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    server: { middlewareMode: true, ws: false, hmr: false, watch: null }, appType: 'custom' });
  ({ default: App } = await vite.ssrLoadModule('/src/App.tsx'));
});
after(async () => { await vite?.close(); });

const profile = {
  id: 'synthetic-candidate', version: 1, display_name: 'Кандидат для проверки', headline: null, summary: null,
  declared_grade: 'junior', verified_grade: null, allowed_work_modes: ['remote'], cities: [],
  max_weekly_hours: 40, desired_hours_per_week: null, skills: [], experience_entries: [], project_entries: [],
  profile_complete: true, missing_required_fields: [], visibility: 'hidden', active_attempt_id: null,
  assessment_eligibility: { junior: { allowed: true }, middle: { allowed: true } },
};
const attempt = (id, status, total_score = null) => ({
  id, status, requested_grade: 'junior', total_score, passed: status === 'evaluated',
  deadline_at: '2026-10-10T10:30:00Z', evaluated_at: status === 'evaluated' ? '2026-10-10T10:20:00Z' : null,
});

function screen(route, pages = []) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } });
  client.setQueryData(['me'], { user_id: 'synthetic-user', account_type: 'candidate', email: 'candidate@example.test',
    email_verified: true, company: null, candidate_profile: profile });
  client.setQueryData(['profile'], profile);
  client.setQueryData(['ownContacts'], { channels: [{ type: 'email', value: 'candidate@example.test', enabled: true }] });
  client.setQueryData(['consents'], { states: [{ scope: 'processing', accepted: true }, { scope: 'publication', accepted: true }], documents: [], events: [] });
  client.setQueryData(['taxonomy'], { skills: [], specializations: [], assessment_release: { status: 'published_demo', real_grade_release_ready: false } });
  client.setQueryData(['attempts'], { pages: pages.length ? pages : [{ items: [], next_cursor: null }], pageParams: pages.map((_, i) => i === 0 ? undefined : 'page-two') });
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(MemoryRouter, { initialEntries: [route] }, createElement(App))));
  } finally { client.clear(); }
}

test('profile describes invitation acceptance and separately chosen contacts for a voluntary application', () => {
  const html = screen('/profile');
  assert.match(html, /принятия вами приглашения.*передачи выбранных каналов при самостоятельном отклике/u);
  assert.match(html, /отдельно выбираете, какие контакты передать компании этой вакансии/u);
  assert.match(html, /Публикация профиля сама по себе контакты не открывает/u);
  assert.doesNotMatch(html, /Контакты открываются только после принятия приглашения|компании только после принятия вами приглашения/u);
});

test('history opens each completed attempt from loaded pages and preserves its result or invalidated status', () => {
  const html = screen('/assessment', [
    { items: [attempt('completed-one', 'evaluated', 20)], next_cursor: 'page-two' },
    { items: [attempt('invalidated-two', 'invalidated')], next_cursor: null },
  ]);
  assert.match(html, /href="\/assessment\/completed-one"[^>]*>Посмотреть результат/u);
  assert.match(html, /href="\/assessment\/invalidated-two"[^>]*>Посмотреть результат/u);
  assert.match(html, /20<!-- --> \/ 24|20 \/ 24/u);
  assert.match(html, /Аннулирована/u);
  assert.match(html, /DEMO|демо-оценки/u);
});

test('active and submitted attempts offer distinct continuation and inspection actions', () => {
  const html = screen('/assessment', [{ items: [attempt('active-id', 'in_progress'), attempt('submitted-id', 'submitted')], next_cursor: null }]);
  assert.match(html, /href="\/assessment\/active-id"[^>]*>Продолжить/u);
  assert.match(html, /href="\/assessment\/submitted-id"[^>]*>Открыть попытку/u);
  assert.match(html, /Идёт/u);
  assert.match(html, /На проверке/u);
});
