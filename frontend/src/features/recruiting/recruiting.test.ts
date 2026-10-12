import assert from 'node:assert/strict';
import test from 'node:test';
import {
  computeStageCounts,
  getStageInfo,
  STAGES,
  type RecruitingRecord,
  type Stage,
} from './recruiting-model.ts';

const createMockRecord = (
  id: string,
  stage: Stage,
  candidateName = 'Тестовый Кандидат',
  needTitle = 'Backend Разработчик',
  note: string | null = null,
): RecruitingRecord => ({
  id,
  candidate_id: `cand-${id}`,
  need_id: `need-${id}`,
  stage,
  note,
  updated_at: '2026-10-10T14:00:00Z',
  candidate_visibility: 'public',
  candidate_display_name: candidateName,
  need_title: needTitle,
  version: 1,
});

test('computeStageCounts returns zeroes for empty record list', () => {
  const counts = computeStageCounts([]);
  assert.deepEqual(counts, {
    reviewing: 0,
    shortlisted: 0,
    invited: 0,
    discussion: 0,
    closed: 0,
  });
});

test('computeStageCounts accurately counts records across all 5 stages', () => {
  const records: RecruitingRecord[] = [
    createMockRecord('1', 'reviewing'),
    createMockRecord('2', 'reviewing'),
    createMockRecord('3', 'shortlisted'),
    createMockRecord('4', 'invited'),
    createMockRecord('5', 'invited'),
    createMockRecord('6', 'invited'),
    createMockRecord('7', 'discussion'),
    createMockRecord('8', 'closed'),
    createMockRecord('9', 'closed'),
  ];

  const counts = computeStageCounts(records);
  assert.equal(counts.reviewing, 2);
  assert.equal(counts.shortlisted, 1);
  assert.equal(counts.invited, 3);
  assert.equal(counts.discussion, 1);
  assert.equal(counts.closed, 2);
});

test('computeStageCounts safely handles unexpected or corrupted stage keys', () => {
  const records = [
    createMockRecord('1', 'reviewing'),
    { ...createMockRecord('2', 'reviewing'), stage: 'unknown_stage' as unknown as Stage },
  ];

  const counts = computeStageCounts(records);
  assert.equal(counts.reviewing, 1);
  assert.equal(counts.shortlisted, 0);
  assert.equal(counts.invited, 0);
  assert.equal(counts.discussion, 0);
  assert.equal(counts.closed, 0);
});

test('getStageInfo returns correct transition chain for all 5 stages', () => {
  assert.equal(getStageInfo('reviewing').nextStage, 'shortlisted');
  assert.equal(getStageInfo('shortlisted').nextStage, 'invited');
  assert.equal(getStageInfo('invited').nextStage, 'discussion');
  assert.equal(getStageInfo('discussion').nextStage, 'closed');
  assert.equal(getStageInfo('closed').nextStage, 'reviewing');
});

test('STAGES defines Russian labels and accent colors for all 5 stages', () => {
  assert.equal(STAGES.length, 5);
  const stageIds = STAGES.map((s) => s.id);
  assert.deepEqual(stageIds, ['reviewing', 'shortlisted', 'invited', 'discussion', 'closed']);

  STAGES.forEach((stage) => {
    assert.ok(stage.label && stage.label.length > 0, `Stage ${stage.id} must have a label`);
    assert.ok(stage.accentColor && stage.accentColor.startsWith('#'), `Stage ${stage.id} must have a hex color`);
  });
});
