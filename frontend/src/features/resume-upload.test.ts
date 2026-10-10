import assert from 'node:assert/strict';
import test from 'node:test';
import { api, rotateCsrf } from '../api.ts';

test('PDF upload keeps browser multipart boundary and authenticated CSRF without serializing the file', async () => {
  const original = globalThis.fetch;
  const calls: { url: string; init?: RequestInit }[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input); calls.push({ url, init });
    return new Response(JSON.stringify({ data: url.endsWith('/csrf') ? { csrf_token: 'test-token' } : { needs_review: true } }), { status: 200, headers: { 'Content-Type': 'application/json' } });
  };
  rotateCsrf();
  try {
    const form = new FormData(); form.append('file', new Blob(['%PDF-1.7']), 'resume.pdf');
    const result = await api.upload<{ needs_review: boolean }>('/me/resume/import', form);
    assert.equal(result.needs_review, true);
    const upload = calls.find(call => call.url.endsWith('/me/resume/import'))!;
    assert.equal(upload.init?.body, form);
    assert.equal(upload.init?.credentials, 'include');
    const headers = upload.init!.headers as Record<string, string>;
    assert.equal(headers['X-CSRF-Token'], 'test-token');
    assert.equal(headers['Content-Type'], undefined);
    assert.ok(headers['Idempotency-Key']);
  } finally { globalThis.fetch = original; rotateCsrf(); }
});
