import test from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateFraudScore,
  applyFraudBan,
  getActiveFraudBan,
  submitManagerVerdict,
  buildProctoringReport,
  getFraudStatusBadgeInfo,
  saveProctoringVideoBlob,
  loadProctoringVideoBlob,
  loadManagerReviewQueue,
} from './proctoring/proctoring-storage.ts';
import type { ProctoringEvent } from './proctoring/proctoring-types.ts';

test('calculateFraudScore returns auto_passed for clean session', () => {
  const events: ProctoringEvent[] = [
    {
      id: '1',
      session_id: 'test',
      event_type: 'context_menu',
      severity: 'low',
      timestamp: new Date().toISOString(),
    },
  ];

  const result = calculateFraudScore(events);
  assert.equal(result.riskLevel, 'clean');
  assert.equal(result.reviewStatus, 'auto_passed');
  assert.ok(result.fraudScore <= 25);
});

test('calculateFraudScore flags review_required on gaze deviation and tab hidden', () => {
  const events: ProctoringEvent[] = [
    {
      id: '1',
      session_id: 'test',
      event_type: 'gaze_deviation',
      severity: 'high',
      timestamp: new Date().toISOString(),
      duration_seconds: 4,
    },
    {
      id: '2',
      session_id: 'test',
      event_type: 'tab_hidden',
      severity: 'high',
      timestamp: new Date().toISOString(),
      duration_seconds: 2,
    },
  ];

  const result = calculateFraudScore(events);
  assert.equal(result.reviewStatus, 'review_required');
  assert.ok(result.fraudScore > 25);
  assert.ok(result.verdict.includes('проверку экспертом'));
});

test('30-day fraud ban is created and checked correctly', () => {
  const ban = applyFraudBan({
    attemptId: 'att-fraud-1',
    specializationId: 'backend-python-v1',
    grade: 'junior',
    reason: 'Шпаргалка и второй монитор',
  });

  assert.equal(ban.days_left, 30);
  assert.equal(ban.grade, 'junior');

  const active = getActiveFraudBan('backend-python-v1', 'junior');
  assert.ok(active !== null);
  assert.equal(active?.days_left, 30);
});

test('manager verdict confirm_fraud annuls attempt and applies 30-day ban', () => {
  const report = buildProctoringReport({
    sessionId: 'sess-manager-test',
    attemptId: 'att-mgr-1',
    specializationId: 'backend-python-v1',
    requestedGrade: 'middle',
    startedAt: new Date(Date.now() - 60000).toISOString(),
    completedAt: new Date().toISOString(),
    events: [{ id: '1', session_id: 's', event_type: 'camera_disabled', severity: 'critical', timestamp: new Date().toISOString() }],
    videoBlob: null,
  });

  const updated = submitManagerVerdict({
    attemptId: 'att-mgr-1',
    decision: 'confirm_fraud',
    reviewerName: 'Главный экзаменатор',
    comment: 'Камера отключена, на шеринге обнаружен браузер с ChatGPT',
  });

  assert.ok(updated !== null);
  assert.equal(updated?.review_status, 'confirmed_fraud');
  assert.ok(updated?.verdict.includes('заблокирован на 30 дней'));

  const ban = getActiveFraudBan('backend-python-v1', 'middle');
  assert.ok(ban !== null);
  assert.equal(ban?.days_left, 30);
});

test('manager verdict dismiss_fraud clears suspicion and confirms clean grade without ban', () => {
  buildProctoringReport({
    sessionId: 'sess-dismiss-test',
    attemptId: 'att-dismiss-1',
    specializationId: 'backend-python-v1',
    requestedGrade: 'junior',
    startedAt: new Date(Date.now() - 60000).toISOString(),
    completedAt: new Date().toISOString(),
    events: [{ id: '1', session_id: 's', event_type: 'window_blur', severity: 'medium', timestamp: new Date().toISOString() }],
    videoBlob: null,
  });

  const updated = submitManagerVerdict({
    attemptId: 'att-dismiss-1',
    decision: 'dismiss_fraud',
    reviewerName: 'Эксперт ФСП',
    comment: 'Ложное срабатывание, уведомление ОС',
  });

  assert.ok(updated !== null);
  assert.equal(updated?.review_status, 'dismissed_clean');
  assert.ok(updated?.verdict.includes('отклонены экспертом'));

  const ban = getActiveFraudBan('backend-python-v1', 'junior');
  assert.equal(ban, null);
});

test('getFraudStatusBadgeInfo returns correct badges and tones for all fraud statuses', () => {
  const fraudBadge = getFraudStatusBadgeInfo('confirmed_fraud');
  assert.equal(fraudBadge.tone, 'danger');
  assert.ok(fraudBadge.label.includes('Фрод подтвержден'));

  const reviewBadge = getFraudStatusBadgeInfo('review_required');
  assert.equal(reviewBadge.tone, 'warm');
  assert.ok(reviewBadge.label.includes('На проверке эксперта'));

  const cleanBadge = getFraudStatusBadgeInfo('dismissed_clean');
  assert.equal(cleanBadge.tone, 'good');
  assert.ok(cleanBadge.label.includes('оправдан'));

  const autoBadge = getFraudStatusBadgeInfo('auto_passed');
  assert.equal(autoBadge.tone, 'good');
  assert.ok(autoBadge.label.includes('нарушений нет'));

  const fallbackBadge = getFraudStatusBadgeInfo(null, 'invalidated');
  assert.equal(fallbackBadge.tone, 'danger');
  assert.ok(fallbackBadge.label.includes('Аннулирована'));
});

test('video blob persistence saves and retrieves video for attempt', async () => {
  const testBlob = new Blob(['sample-video-chunks-data'], { type: 'video/webm' });
  await saveProctoringVideoBlob('att-video-test-1', testBlob);

  const loaded = await loadProctoringVideoBlob('att-video-test-1');
  assert.ok(loaded !== null);
  assert.equal(loaded?.type, 'video/webm');
});

test('loadManagerReviewQueue returns queued sessions for staff and employer view', () => {
  const queue = loadManagerReviewQueue();
  assert.ok(queue.length > 0);
  assert.ok(queue.some((q) => q.review_status === 'review_required'));
});
