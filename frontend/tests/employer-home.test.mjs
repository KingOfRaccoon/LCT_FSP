import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createServer } from 'vite';

let server;
let App;
before(async () => {
  server = await createServer({
    root: fileURLToPath(new URL('../', import.meta.url)), configFile: false,
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  });
  ({ default: App } = await server.ssrLoadModule('/src/App.tsx'));
});
after(async () => { await server?.close(); });

const empty = { items: [], page: { next_cursor: null, limit: 20 } };
const invitationPage = (states, next_cursor = null) => ({
  items: states.map((state, index) => ({ id: `inv-${index}`, state })),
  page: { next_cursor, limit: 20 },
});

async function home({ needs = empty, sent = empty, needsError, sentError } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryOnMount: false, staleTime: Infinity, gcTime: Infinity } } });
  client.setQueryData(['me'], {
    user_id: 'synthetic-employer', account_type: 'employer', email: 'employer@example.test', email_verified: true,
    company: { id: 'synthetic-company', name: 'Синтетическая компания' }, candidate_profile: null,
  });
  for (const [key, data, error] of [['needs', needs, needsError], ['sentInvitations', sent, sentError]]) {
    if (data !== null) client.setQueryData([key], data);
    if (error) {
      await assert.rejects(client.fetchQuery({ queryKey: [key], staleTime: 0, queryFn: async () => { throw new Error(error); } }));
    }
  }
  try {
    return renderToStaticMarkup(createElement(QueryClientProvider, { client },
      createElement(MemoryRouter, { initialEntries: ['/'] }, createElement(App))));
  } finally { client.clear(); }
}

function stat(html, label) {
  const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = html.match(new RegExp(`<span>${escaped}</span>[\\s\\S]*?</div><strong>(.*?)</strong><small>([\\s\\S]*?)</small>`));
  assert.ok(match, `Missing statistic: ${label}`);
  return { value: match[1], detail: match[2] };
}

test('a first page with no pending invitations and a cursor never displays an exact zero', async () => {
  const html = await home({ sent: invitationPage(Array(20).fill('accepted'), 'page-two') });
  assert.equal(stat(html, 'В ожидании').value, '—');
  assert.match(stat(html, 'В ожидании').detail, /неизвестно/iu);
  assert.match(html, /href="\/invitations"/);
});

test('pending invitations on an incomplete page display a lower bound', async () => {
  const html = await home({ sent: invitationPage(['pending', 'accepted', 'pending', 'pending'], 'more') });
  assert.equal(stat(html, 'В ожидании').value, '≥3');
  assert.match(stat(html, 'В ожидании').detail, /не менее 3/iu);
});

test('an exhausted page permits an exact zero or positive pending count', async () => {
  assert.equal(stat(await home({ sent: invitationPage(['declined', 'accepted']) }), 'В ожидании').value, '0');
  assert.equal(stat(await home({ sent: invitationPage(['pending', 'pending']) }), 'В ожидании').value, '2');
});

test('pending needs never hide the queue, header, quick links or completed invitation statistic', async () => {
  const html = await home({ needs: null, sent: invitationPage(['pending']) });
  assert.match(html, /Действия и сроки/);
  assert.match(html, /Добро пожаловать/);
  assert.match(html, /Быстрый переход/);
  assert.equal(stat(html, 'В ожидании').value, '1');
  assert.equal(stat(html, 'Активные потребности').value, '—');
  assert.doesNotMatch(html, /Добавьте первую потребность/);
});

test('a needs error remains local even when a failed refetch retains stale data', async () => {
  const html = await home({ needsError: 'Сбой списка потребностей', sent: invitationPage(['pending']) });
  assert.match(html, /Действия и сроки/);
  assert.match(html, /Сбой списка потребностей/);
  assert.match(html, /Повторить/);
  assert.equal(stat(html, 'Активные потребности').value, '—');
  assert.equal(stat(html, 'В ожидании').value, '1');
  assert.doesNotMatch(html, /Добавьте первую потребность/);
});

test('pending invitations never hide ready needs or the queue', async () => {
  const html = await home({ sent: null });
  assert.match(html, /Действия и сроки/);
  assert.match(html, /Добавьте первую потребность/);
  assert.equal(stat(html, 'Активные потребности').value, '0');
  assert.equal(stat(html, 'В ожидании').value, '—');
});

test('an invitation refetch error cannot present a cached count as current', async () => {
  const html = await home({ sent: invitationPage(['pending']), sentError: 'Сбой списка приглашений' });
  assert.match(html, /Действия и сроки/);
  assert.match(html, /Сбой списка приглашений/);
  assert.match(html, /Быстрый переход/);
  assert.equal(stat(html, 'В ожидании').value, '—');
  assert.match(html, /Добавьте первую потребность/);
});

test('partial needs with no active rows do not claim an empty workspace', async () => {
  const html = await home({ needs: { items: [], page: { next_cursor: 'more-needs', limit: 50 } } });
  assert.equal(stat(html, 'Активные потребности').value, '—');
  assert.doesNotMatch(html, /Добавьте первую потребность/);
  assert.match(html, /этой странице/iu);
});

test('a partial active-need count is a lower bound', async () => {
  const html = await home({ needs: { items: [{ id: 'need-1', title: 'Синтетическая роль', status: 'active', target_grade: 'junior', work_mode: 'remote', workload_hours_per_week: 40 }], page: { next_cursor: 'more', limit: 50 } } });
  assert.equal(stat(html, 'Активные потребности').value, '≥1');
  assert.match(html, /Синтетическая роль/);
});
