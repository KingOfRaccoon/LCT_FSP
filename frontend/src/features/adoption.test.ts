import assert from 'node:assert/strict';
import test from 'node:test';
import { buildSelectedProfilePatch } from './adoption/resume-selection.ts';
import { salaryPreferenceFromForm } from './adoption/types.ts';

test('resume import applies only explicitly selected fields and preserves existing profile evidence', () => {
  const profile = {
    version: 7,
    skills: [{ skill_id: 'python-basics' }],
    experience_entries: [{ id: 'existing', company: 'Старая компания', role: 'Разработчик', description: 'Сохранить', start_month: '2022-01', end_month: null, skill_ids: ['python-basics'] }],
    project_entries: [],
  };
  const draft = {
    headline: 'Backend-разработчик',
    summary: 'Из резюме',
    skills: ['python-basics', 'http-api'],
    experience_entries: [{ id: 'imported', company: 'Новая компания', role: 'Инженер', description: 'Импортировано', start_month: '2024-01', end_month: null, skill_ids: ['http-api'] }],
  };

  const patch = buildSelectedProfilePatch(profile, draft, ['headline', 'skills', 'experience_entries']);

  assert.deepEqual(patch, {
    expected_version: 7,
    headline: 'Backend-разработчик',
    skills: ['python-basics', 'http-api'],
    experience_entries: [profile.experience_entries[0], ...draft.experience_entries],
  });
  assert.equal('summary' in patch, false);
});

test('resume import with no selected fields produces only the optimistic version guard', () => {
  assert.deepEqual(buildSelectedProfilePatch({ version: 3, skills: [], experience_entries: [], project_entries: [] }, { headline: 'Ignored' }, []), { expected_version: 3 });
});

test('resume import ignores grade and server version fields even if a response contains them', () => {
  const untrusted = { declared_grade: 'middle', expected_version: 999 } as never;
  const patch = buildSelectedProfilePatch({ version: 3, skills: [], experience_entries: [], project_entries: [] }, untrusted, ['declared_grade', 'expected_version'] as never);
  assert.deepEqual(patch, { expected_version: 3 });
});

test('resume import does not reuse an existing experience identity', () => {
  const existing = { id: 'same-id', company: 'Кандидатский факт', role: 'Старая роль', description: '', start_month: '2022-01', end_month: null, skill_ids: [] };
  const imported = { id: 'same-id', company: 'Из резюме', role: 'Новая роль', description: '', start_month: '2024-01', end_month: null, skill_ids: [] };
  const patch = buildSelectedProfilePatch({ version: 2, skills: [], experience_entries: [existing], project_entries: [] }, { experience_entries: [imported] }, ['experience_entries']);
  assert.equal(patch.experience_entries?.[0].id, 'same-id');
  assert.equal(patch.experience_entries?.[0].role, 'Старая роль');
  assert.equal(patch.experience_entries?.[1].role, 'Новая роль');
  assert.notEqual(patch.experience_entries?.[1].id, 'same-id');
});

test('resume entry merge preserves existing values and IDs while removing server-only fields', () => {
  const existingExperience = { id: 'existing-exp', company: 'Старая компания', role: 'Разработчик', description: 'Факт кандидата', start_month: '2022-01', end_month: null, skill_ids: ['python-basics'], provenance: 'candidate_declared', created_at: 'server-only' };
  const importedExperience = { id: 'imported-exp', company: 'Новая компания', role: 'Инженер', description: 'Из резюме', start_month: '2024-01', end_month: null, skill_ids: ['http-api'], provenance: 'server-only', score: 99 };
  const existingProject = { id: 'existing-project', title: 'Старый проект', description: 'Сохранить', url: null, skill_ids: ['python-basics'], provenance: 'candidate_declared', verified_at: 'server-only' };
  const importedProject = { id: 'imported-project', title: 'Новый проект', description: 'Из резюме', url: 'https://example.test', skill_ids: ['http-api'], provenance: 'server-only' };
  const patch = buildSelectedProfilePatch(
    { version: 4, skills: [], experience_entries: [existingExperience], project_entries: [existingProject] },
    { experience_entries: [importedExperience], project_entries: [importedProject] },
    ['experience_entries', 'project_entries'],
  );

  assert.deepEqual(patch.experience_entries, [
    { id: 'existing-exp', company: 'Старая компания', role: 'Разработчик', description: 'Факт кандидата', start_month: '2022-01', end_month: null, skill_ids: ['python-basics'] },
    { id: 'imported-exp', company: 'Новая компания', role: 'Инженер', description: 'Из резюме', start_month: '2024-01', end_month: null, skill_ids: ['http-api'] },
  ]);
  assert.deepEqual(patch.project_entries, [
    { id: 'existing-project', title: 'Старый проект', description: 'Сохранить', url: null, skill_ids: ['python-basics'] },
    { id: 'imported-project', title: 'Новый проект', description: 'Из резюме', url: 'https://example.test', skill_ids: ['http-api'] },
  ]);
});

test('salary preferences convert rubles to kopecks and carry explicit workload and tax basis', () => {
  assert.deepEqual(salaryPreferenceFromForm({ minRubles: '120000', maxRubles: '180000', basis: 'net', workloadType: 'part_time', hoursPerWeek: '24' }), {
    min_kopecks: 12_000_000,
    max_kopecks: 18_000_000,
    currency: 'RUB',
    period: 'month',
    basis: 'net',
    workload_type: 'part_time',
    hours_per_week: 24,
  });
});

test('salary preference rejects invalid ranges and workload-hour combinations', () => {
  assert.throws(() => salaryPreferenceFromForm({ minRubles: '180000', maxRubles: '120000', basis: 'gross', workloadType: 'full_time', hoursPerWeek: '40' }));
  assert.throws(() => salaryPreferenceFromForm({ minRubles: '1.5', maxRubles: '10', basis: 'gross', workloadType: 'full_time', hoursPerWeek: '40' }));
  assert.throws(() => salaryPreferenceFromForm({ minRubles: '1', maxRubles: '10', basis: 'gross', workloadType: 'full_time', hoursPerWeek: '39' }));
  assert.throws(() => salaryPreferenceFromForm({ minRubles: '1', maxRubles: '10', basis: 'gross', workloadType: 'part_time', hoursPerWeek: '40' }));
});
