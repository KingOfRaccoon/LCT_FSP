import assert from 'node:assert/strict';
import test from 'node:test';
import { readHiringPlan, hiringEffortMinutes, toStepInstant, nextStepStatus } from './hiring/hiring-plan.ts';

function form(enabled = true) {
  const data = new FormData();
  if (enabled) data.set('hiring_plan_enabled', 'on');
  data.set('hiring_response_days', '3');
  data.append('hiring_stage_title', '  Технический разговор  ');
  data.append('hiring_stage_minutes', '30');
  data.append('hiring_stage_criteria', '  Обсудить проектирование API  ');
  return data;
}

test('optional hiring plan remains absent until explicitly enabled', () => {
  assert.equal(readHiringPlan(form(false)), null);
});

test('hiring plan preserves declared stages and trims text', () => {
  assert.deepEqual(readHiringPlan(form()), { response_time_days: 3, stages: [{ title: 'Технический разговор', effort_minutes: 30, criteria: 'Обсудить проектирование API' }] });
});

test('hiring plan rejects blank criteria and invalid effort or response time', () => {
  for (const [name, value] of [['hiring_stage_title', '  '], ['hiring_stage_criteria', ''], ['hiring_stage_minutes', '0'], ['hiring_stage_minutes', '481'], ['hiring_stage_minutes', '2.5'], ['hiring_response_days', '31']]) {
    const data = form(); data.set(name, value);
    assert.throws(() => readHiringPlan(data));
  }
});

test('hiring plan cannot silently ignore an extra incomplete or sixth stage', () => {
  const data = form(); data.append('hiring_stage_title', 'Ещё один этап');
  assert.throws(() => readHiringPlan(data));
  const tooMany = form();
  for (let i = 0; i < 5; i++) { tooMany.append('hiring_stage_title', 'Разговор'); tooMany.append('hiring_stage_minutes', '10'); tooMany.append('hiring_stage_criteria', 'Уточнить опыт'); }
  assert.throws(() => readHiringPlan(tooMany));
});

test('candidate sees the total effort across all stages', () => {
  const plan = readHiringPlan(form())!;
  plan.stages.push({ title: 'Практика', effort_minutes: 40, criteria: 'Объяснить решение' });
  assert.equal(hiringEffortMinutes(plan), 70);
});

test('next-step time is optional but invalid input cannot become an unknown deadline', () => {
  assert.equal(toStepInstant(''), null);
  assert.equal(toStepInstant('2026-10-10T15:00:00+03:00'), '2026-10-10T12:00:00.000Z');
  assert.throws(() => toStepInstant('invalid'));
  assert.throws(() => toStepInstant('2026-02-31T12:00'));
  assert.throws(() => toStepInstant('2026-10-10T24:00'));
  assert.throws(() => toStepInstant('2026-02-30T12:00:00+03:00'));
});

test('an expired plan is distinct from completion and no deadline', () => {
  const now = Date.parse('2026-10-10T12:00:00Z');
  assert.equal(nextStepStatus({ completed: false, due_at: '2026-10-09T12:00:00Z' }, now), 'overdue');
  assert.equal(nextStepStatus({ completed: true, due_at: '2026-10-09T12:00:00Z' }, now), 'completed');
  assert.equal(nextStepStatus({ completed: false, due_at: null }, now), 'planned');
});
