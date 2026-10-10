import assert from 'node:assert/strict';
import test from 'node:test';
import { buildDraftPayload, candidateFormArtifact, parsePilotObservations, pilotAccuracy, privateAnswerKeyArtifact, serializeJsonArtifact } from './methodist/methodist-forms.ts';
import type { DraftExport } from './methodist/types.ts';

const validDraft = {
  source_title: 'HTTP RFC', source_reference: 'https://example.test/rfc', source_excerpt: 'A short source excerpt.',
  competency_id: 'http-api', grade: 'middle' as const, question: 'Какой статус вернуть?',
  options: ['200', '301', '400', '500'], correct_option: '2', rubric: 'Выбрать ошибку клиента.',
};

test('draft payload trims authored text, keeps source reference plain, and requires exactly four options', () => {
  assert.deepEqual(buildDraftPayload(validDraft), {
    source_title: 'HTTP RFC', source_reference: 'https://example.test/rfc', source_excerpt: 'A short source excerpt.',
    competency_id: 'http-api', grade: 'middle', question: 'Какой статус вернуть?',
    options: ['200', '301', '400', '500'], correct_option: 2, rubric: 'Выбрать ошибку клиента.',
  });
  assert.throws(() => buildDraftPayload({ ...validDraft, options: ['A', 'B', 'C', ''] }), /четыре непустых варианта/);
  assert.throws(() => buildDraftPayload({ ...validDraft, options: ['A', 'B', 'a ', 'D'] }), /различаться/);
  assert.throws(() => buildDraftPayload({ ...validDraft, correct_option: '4' }), /правильный вариант/);
  assert.equal(buildDraftPayload({ ...validDraft, source_reference: 'DOI: 10.1234/example' }).source_reference, 'DOI: 10.1234/example');
  assert.throws(() => buildDraftPayload({ ...validDraft, source_reference: 'x'.repeat(2049) }), /2048/);
});

test('pilot batch parser accepts only anonymous source and correctness columns', () => {
  assert.deepEqual(parsePilotObservations('source,correct\nsynthetic,true\npilot_record,false\nsynthetic,1'), [
    { source: 'synthetic', correct: true },
    { source: 'pilot_record', correct: false },
    { source: 'synthetic', correct: true },
  ]);
  assert.throws(() => parsePilotObservations('source,correct\nИван,ivan@example.test,true'), /только source и correct/);
  assert.throws(() => parsePilotObservations('source,correct\nsynthetic,maybe'), /true\/false/);
});

test('pilot report hides accuracy below ten observations and labels experimental evidence', () => {
  assert.equal(pilotAccuracy(8, 9), null);
  assert.equal(pilotAccuracy(8, 10), 0.8);
});

test('candidate and private downloads remain separate and the source excerpt stays in the private key', () => {
  const exported: DraftExport = {
    draft_id: 'draft-1', version: 3,
    candidate_form: {
      format: 'draft-v1', source: { title: 'RFC', reference: 'https://example.test/rfc' },
      competency_id: 'http-api', grade: 'middle', question: 'Status?', options: ['200', '301', '400', '500'],
    },
    private_answer_key: { format: 'draft-v1', draft_id: 'draft-1', version: 3, correct_option: 2, source_excerpt: 'Private answer evidence.', rubric: 'Верный ответ—list' },
    live_bank_published: false,
  };
  const candidateJson = serializeJsonArtifact(candidateFormArtifact(exported));
  const privateKeyJson = serializeJsonArtifact(privateAnswerKeyArtifact(exported));
  assert.equal(candidateFormArtifact(exported).source.reference, 'https://example.test/rfc');
  assert.equal(candidateJson.includes('Private answer evidence'), false);
  assert.equal(candidateJson.includes('correct_option'), false);
  assert.equal(candidateJson.includes('Верный ответ—list'), false);
  assert.equal(privateKeyJson.includes('Private answer evidence'), true);
  assert.equal(privateKeyJson.includes('Верный ответ—list'), true);
  assert.equal(privateAnswerKeyArtifact(exported).correct_option, 2);
});
