import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { test } from 'node:test';

const submission = new URL('../../submission/', import.meta.url);

test('submission manifest describes the exact packaged file bytes', () => {
  const manifest = JSON.parse(readFileSync(new URL('manifest.json', submission), 'utf8'));
  assert.equal(manifest.files.length, 6);
  for (const entry of manifest.files) {
    const bytes = readFileSync(new URL(entry.name, submission));
    assert.equal(bytes.length, entry.bytes, `${entry.name}: byte count`);
    assert.equal(createHash('sha256').update(bytes).digest('hex'), entry.sha256, `${entry.name}: SHA-256`);
  }
});
