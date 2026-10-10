import assert from 'node:assert/strict';
import test from 'node:test';
import React from 'react';
import { renderToString, renderToStaticMarkup } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { createServer } from 'vite';

test('company page server-renders from the company profile without a trust-score endpoint', async () => {
  const vite = await createServer({ root: new URL('../../', import.meta.url).pathname, configFile: false, server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  try {
    const { CompanyPage } = await vite.ssrLoadModule('/src/App.tsx');
    const company = {
      id: 'company-1', version: 1, name: 'Тестовая компания', description: 'Описание',
      website: null, industry: 'ИТ', contact_email: null, contact_phone: null,
    };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['company'], company);
    client.setQueryData(['consents'], {
      documents: [{ scope: 'processing', version: '1', title: 'Обработка', text: 'Тест' }],
      states: [], events: [],
    });
    const element = React.createElement(QueryClientProvider, { client },
      React.createElement(MemoryRouter, null, React.createElement(CompanyPage, { company })));
    const html = renderToString(element);
    assert.match(html, /Тестовая компания/u);
    assert.doesNotMatch(html, /Trust Score|Верифицированный работодатель|95\/100|96\/100/u);
  } finally {
    await vite.close();
  }
});

test('company page renders an unnamed company as incomplete without inventing a name', async () => {
  const vite = await createServer({ root: new URL('../../', import.meta.url).pathname, configFile: false, server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  try {
    const { CompanyPage } = await vite.ssrLoadModule('/src/App.tsx');
    const company = { id: 'company-new', version: 1, name: null, description: null, website: null, industry: null, contact_email: null, contact_phone: null };
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    client.setQueryData(['company'], company);
    client.setQueryData(['consents'], {
      documents: [{ scope: 'processing', version: '1', title: 'Обработка', text: 'Тест' }],
      states: [], events: [],
    });
    const element = React.createElement(QueryClientProvider, { client },
      React.createElement(MemoryRouter, null, React.createElement(CompanyPage, { company })));
    const html = renderToString(element);
    assert.match(html, /Название пока не указано/u);
    assert.match(html, /<input required=""[^>]*id="name" name="name" value=""/u);
    assert.match(html, />—<\/span>/u);
    assert.doesNotMatch(html, /Надёжный работодатель|Верифицированный работодатель|Компания подтверждена/u);
  } finally {
    await vite.close();
  }
});

test('minimal application response server-renders as a redacted row after processing consent is revoked', async () => {
  const vite = await createServer({ root: new URL('../../', import.meta.url).pathname, configFile: false, server: { middlewareMode: true, hmr: false, ws: false }, appType: 'custom' });
  try {
    const { CandidateApplicationRow } = await vite.ssrLoadModule('/src/features/vacancies/VacanciesPage.tsx');
    const html = renderToStaticMarkup(React.createElement(CandidateApplicationRow, {
      app: { id: 'application-1', status: 'reviewed', created_at: '2026-10-10T10:00:00Z', contact_access_revoked: false, has_selected_contacts: false },
      onRevoke: () => {},
    }));
    assert.match(html, /Согласие на обработку отозвано\. Данные отклика скрыты\./u);
    assert.match(html, /Контакты не переданы\./u);
    assert.match(html, /Просмотрен/u);
    assert.doesNotMatch(html, /NaN|undefined/u);
    assert.doesNotMatch(html, /Отозвать доступ к контактам/u);
  } finally {
    await vite.close();
  }
});
