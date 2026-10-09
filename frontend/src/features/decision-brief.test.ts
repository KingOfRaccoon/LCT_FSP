import assert from 'node:assert/strict';
import test from 'node:test';
import type { Schemas } from '../api';
import { summarizeDecision } from './decision-brief.ts';

type Check = Schemas['FitCheck'];

const check = (type: Check['type'], status: Check['status'], options: Partial<Check> = {}): Check => ({
  type,
  skill_id: null,
  importance: null,
  status,
  expected: '',
  observed: null,
  reason: '',
  ...options,
});

test('groups test-confirmed skills separately from conditions matching candidate declarations', () => {
  const brief = summarizeDecision([
    check('skill', 'met', { skill_id: 'python-basics', importance: 'required' }),
    check('work_mode', 'met', { expected: 'remote', observed: 'remote', reason: 'Совпадает с предпочтением кандидата' }),
  ]);

  assert.deepEqual(brief.confirmedSkills.map(({ skill_id }) => skill_id), ['python-basics']);
  assert.deepEqual(brief.matchingConditions.map(({ type }) => type), ['work_mode']);
});

test('keeps required skill and grade gaps distinct from preferred topics', () => {
  const brief = summarizeDecision([
    check('skill', 'declared', { skill_id: 'http-api', importance: 'required' }),
    check('grade', 'unknown'),
    check('skill', 'unknown', { skill_id: 'docker', importance: 'preferred' }),
  ]);

  assert.deepEqual(brief.requiredToClarify.map(({ type, skill_id }) => [type, skill_id]), [
    ['skill', 'http-api'], ['grade', null],
  ]);
  assert.deepEqual(brief.preferredTopics.map(({ skill_id }) => skill_id), ['docker']);
});

test('keeps confirmed grade and specialization separate from matching declared conditions', () => {
  const brief = summarizeDecision([
    check('grade', 'met', { expected: 'middle', observed: 'middle' }),
    check('specialization', 'met', { expected: 'backend-python-v1', observed: 'backend-python-v1' }),
    check('work_mode', 'met', { expected: 'remote', observed: 'remote' }),
  ]);

  assert.equal(brief.grade?.type, 'grade');
  assert.equal(brief.specialization?.type, 'specialization');
  assert.deepEqual(brief.matchingConditions.map(({ type }) => type), ['work_mode']);

  const unresolvedSpecialization = summarizeDecision([check('specialization', 'unknown')]);
  assert.equal(unresolvedSpecialization.specialization?.status, 'unknown');
  assert.deepEqual(unresolvedSpecialization.matchingConditions, []);
});

test('retains unknown work conditions and every mismatch with required context', () => {
  const checks = [
    check('workload', 'unknown'),
    check('city', 'mismatch', { expected: 'Москва', observed: 'Казань' }),
    check('city', 'unknown', { reason: 'Город неизвестен' }),
    check('work_mode', 'declared', { expected: 'remote', observed: null }),
    check('work_mode', 'mismatch', { expected: 'remote', observed: 'office' }),
    check('workload', 'mismatch', { expected: '40', observed: '20' }),
    check('grade', 'mismatch', { expected: 'middle', observed: 'junior' }),
    check('skill', 'mismatch', { skill_id: 'postgres', importance: 'required' }),
    check('specialization', 'mismatch', { expected: 'backend-python-v1', observed: 'other' }),
    check('skill', 'unknown', { skill_id: 'docker', importance: 'required' }),
  ];
  const brief = summarizeDecision(checks);

  assert.deepEqual(brief.openConditions.map(({ type }) => type), ['workload', 'city', 'work_mode']);
  assert.deepEqual(brief.requiredToClarify.map(({ skill_id }) => skill_id), ['docker']);
  assert.deepEqual(brief.mismatches, checks.filter(({ status }) => status === 'mismatch'));
});
