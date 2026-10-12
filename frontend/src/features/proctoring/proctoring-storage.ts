import type {
  ProctoringEvent,
  ProctoringReportData,
  FraudRiskLevel,
  ManagerReviewStatus,
  FraudBanRecord,
  ManagerVerdict,
} from './proctoring-types.ts';

const demoVideos = new Map<string, Blob>();
const demoReports = new Map<string, ProctoringReportData>();
const demoBans = new Map<string, FraudBanRecord>();
const objectUrls = new Set<string>();
let demoQueue: ProctoringReportData[] | null = null;

const emptySummary = () => ({
  total_events: 0,
  tab_switches: 0,
  window_blurs: 0,
  face_missing_count: 0,
  multiple_faces_count: 0,
  gaze_deviations: 0,
  clipboard_actions: 0,
  screen_interruptions: 0,
});

function makeDemoReport({
  sessionId,
  attemptId,
  candidateId,
  candidateName,
  grade,
  hoursAgo,
  score,
  risk,
  status,
  events,
}: {
  sessionId: string;
  attemptId: string;
  candidateId: string;
  candidateName: string;
  grade: string;
  hoursAgo: number;
  score: number;
  risk: FraudRiskLevel;
  status: ManagerReviewStatus;
  events: ProctoringEvent[];
}): ProctoringReportData {
  const startedAt = new Date(Date.now() - hoursAgo * 60 * 60 * 1000).toISOString();
  return {
    demo: true,
    session_id: sessionId,
    attempt_id: attemptId,
    candidate_id: candidateId,
    candidate_name: candidateName,
    specialization_id: 'backend-python-v1',
    requested_grade: grade,
    started_at: startedAt,
    completed_at: new Date(new Date(startedAt).getTime() + 30 * 60 * 1000).toISOString(),
    duration_seconds: 1800,
    video_available: false,
    fraud_score: score,
    risk_level: risk,
    review_status: status,
    verdict: 'DEMO only: synthetic example signal. It is not an expert decision and does not affect an assessment, grade, or retake access.',
    events_summary: { ...emptySummary(), total_events: events.length },
    events,
  };
}

export const DEFAULT_DEMO_REPORTS: ProctoringReportData[] = [
  makeDemoReport({
    sessionId: 'demo-session-01',
    attemptId: 'att-demo-01',
    candidateId: 'candidate-demo-01',
    candidateName: 'DEMO · Иван Кузнецов',
    grade: 'junior',
    hoursAgo: 3,
    score: 58,
    risk: 'fraud',
    status: 'review_required',
    events: [
      { id: 'demo-event-1', session_id: 'demo-session-01', event_type: 'tab_hidden', severity: 'medium', timestamp: new Date(Date.now() - 2.8 * 60 * 60 * 1000).toISOString() },
      { id: 'demo-event-2', session_id: 'demo-session-01', event_type: 'gaze_deviation', severity: 'medium', timestamp: new Date(Date.now() - 2.7 * 60 * 60 * 1000).toISOString() },
    ],
  }),
  makeDemoReport({
    sessionId: 'demo-session-02',
    attemptId: 'att-demo-02',
    candidateId: 'candidate-demo-02',
    candidateName: 'DEMO · Анна Морозова',
    grade: 'middle',
    hoursAgo: 48,
    score: 85,
    risk: 'fraud',
    status: 'confirmed_fraud',
    events: [
      { id: 'demo-event-3', session_id: 'demo-session-02', event_type: 'multiple_faces', severity: 'high', timestamp: new Date(Date.now() - 47 * 60 * 60 * 1000).toISOString() },
      { id: 'demo-event-4', session_id: 'demo-session-02', event_type: 'camera_disabled', severity: 'high', timestamp: new Date(Date.now() - 46 * 60 * 60 * 1000).toISOString() },
    ],
  }),
  makeDemoReport({
    sessionId: 'demo-session-03',
    attemptId: 'att-demo-03',
    candidateId: 'candidate-demo-03',
    candidateName: 'DEMO · Сергей Васильев',
    grade: 'junior',
    hoursAgo: 96,
    score: 8,
    risk: 'clean',
    status: 'auto_passed',
    events: [{ id: 'demo-event-5', session_id: 'demo-session-03', event_type: 'context_menu', severity: 'low', timestamp: new Date(Date.now() - 95 * 60 * 60 * 1000).toISOString() }],
  }),
];

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function videoKey(attemptId: string, type: 'camera' | 'screen'): string {
  return `${attemptId}:${type}`;
}

export function clearDemoProctoringData(): void {
  demoVideos.clear();
  demoReports.clear();
  demoBans.clear();
  demoQueue = null;
  for (const url of objectUrls) URL.revokeObjectURL(url);
  objectUrls.clear();
}

export async function saveProctoringVideoBlob(
  attemptId: string,
  blob: Blob,
  type: 'camera' | 'screen' = 'camera',
): Promise<void> {
  demoVideos.set(videoKey(attemptId, type), blob);
}

export async function loadProctoringVideoBlob(
  attemptId: string,
  type: 'camera' | 'screen' = 'camera',
): Promise<Blob | null> {
  return demoVideos.get(videoKey(attemptId, type)) ?? null;
}

export function getFraudStatusBadgeInfo(
  reviewStatus?: ManagerReviewStatus | null,
  attemptStatus?: string | null,
): {
  label: string;
  tone: 'good' | 'warm' | 'danger' | 'neutral';
  description: string;
} {
  const demoOnly = 'Локальный DEMO-макет. Это не экспертное решение и не меняет оценку или доступ к пересдаче.';
  if (reviewStatus === 'confirmed_fraud') return { label: 'DEMO · высокий сигнал', tone: 'danger', description: demoOnly };
  if (reviewStatus === 'review_required') return { label: 'DEMO · сигнал для просмотра', tone: 'warm', description: demoOnly };
  if (reviewStatus === 'dismissed_clean') return { label: 'DEMO · сигнал снят', tone: 'good', description: demoOnly };
  if (reviewStatus === 'auto_passed') return { label: 'DEMO · низкий сигнал', tone: 'good', description: demoOnly };
  if (attemptStatus === 'invalidated') return { label: 'Сервер отменил попытку', tone: 'danger', description: 'Статус пришёл от сервера оценки.' };
  if (attemptStatus === 'in_progress') return { label: 'Идёт оценка', tone: 'neutral', description: 'Оценка в процессе.' };
  if (attemptStatus === 'submitted') return { label: 'На проверке ответов', tone: 'warm', description: 'Ответы переданы на обработку.' };
  return { label: 'DEMO · пример', tone: 'neutral', description: demoOnly };
}

export function calculateFraudScore(events: ProctoringEvent[]): {
  fraudScore: number;
  riskLevel: FraudRiskLevel;
  reviewStatus: ManagerReviewStatus;
  verdict: string;
} {
  const weights: Record<ProctoringEvent['event_type'], number> = {
    tab_hidden: 18,
    window_blur: 8,
    fullscreen_exit: 8,
    copy_attempt: 15,
    paste_attempt: 15,
    context_menu: 8,
    window_resize: 5,
    face_missing: 20,
    multiple_faces: 30,
    gaze_deviation: 15,
    hotkey_switch: 20,
    devtools_attempt: 45,
    camera_disabled: 45,
    screen_share_stopped: 40,
    audio_anomaly: 5,
  };
  const fraudScore = Math.min(100, events.reduce((sum, event) => sum + weights[event.event_type], 0));
  const riskLevel: FraudRiskLevel = fraudScore > 50 ? 'fraud' : fraudScore > 25 ? 'suspicious' : 'clean';
  const reviewStatus: ManagerReviewStatus = riskLevel === 'clean' ? 'auto_passed' : 'review_required';
  return {
    fraudScore,
    riskLevel,
    reviewStatus,
    verdict: 'DEMO only: this heuristic is an unvalidated signal, not evidence of misconduct or a grade decision.',
  };
}

export async function buildProctoringReport({
  sessionId,
  attemptId,
  candidateId,
  candidateName,
  specializationId = 'backend-python-v1',
  requestedGrade = 'junior',
  startedAt,
  completedAt,
  events,
  videoBlob,
  screenBlob,
}: {
  sessionId: string;
  attemptId: string;
  candidateId?: string;
  candidateName?: string;
  specializationId?: string;
  requestedGrade?: string;
  startedAt: string;
  completedAt: string;
  events: ProctoringEvent[];
  videoBlob: Blob | null;
  screenBlob?: Blob | null;
}): Promise<ProctoringReportData> {
  const { fraudScore, riskLevel, reviewStatus, verdict } = calculateFraudScore(events);
  const durationSeconds = Math.max(1, Math.round((new Date(completedAt).getTime() - new Date(startedAt).getTime()) / 1000));
  let videoUrl: string | undefined;
  let screenVideoUrl: string | undefined;
  if (videoBlob) {
    await saveProctoringVideoBlob(attemptId, videoBlob, 'camera');
    videoUrl = URL.createObjectURL(videoBlob);
    objectUrls.add(videoUrl);
  }
  if (screenBlob) {
    await saveProctoringVideoBlob(attemptId, screenBlob, 'screen');
    screenVideoUrl = URL.createObjectURL(screenBlob);
    objectUrls.add(screenVideoUrl);
  }
  const report: ProctoringReportData = {
    demo: true,
    session_id: sessionId,
    attempt_id: attemptId,
    candidate_id: candidateId,
    candidate_name: candidateName ? `DEMO · ${candidateName}` : 'DEMO · Участник',
    specialization_id: specializationId,
    requested_grade: requestedGrade,
    started_at: startedAt,
    completed_at: completedAt,
    duration_seconds: durationSeconds,
    video_url: videoUrl,
    screen_video_url: screenVideoUrl,
    video_available: Boolean(videoBlob || screenBlob),
    fraud_score: fraudScore,
    risk_level: riskLevel,
    review_status: reviewStatus,
    verdict,
    events_summary: {
      total_events: events.length,
      tab_switches: events.filter((event) => event.event_type === 'tab_hidden').length,
      window_blurs: events.filter((event) => event.event_type === 'window_blur').length,
      face_missing_count: events.filter((event) => event.event_type === 'face_missing').length,
      multiple_faces_count: events.filter((event) => event.event_type === 'multiple_faces').length,
      gaze_deviations: events.filter((event) => event.event_type === 'gaze_deviation').length,
      clipboard_actions: events.filter((event) => event.event_type === 'copy_attempt' || event.event_type === 'paste_attempt').length,
      screen_interruptions: events.filter((event) => event.event_type === 'screen_share_stopped' || event.event_type === 'camera_disabled').length,
    },
    events: clone(events),
  };
  demoReports.set(attemptId, report);
  const queue = loadManagerReviewQueue().filter((item) => item.attempt_id !== attemptId);
  queue.unshift(report);
  demoQueue = queue;
  return clone(report);
}

export function loadStoredProctoringReport(attemptId: string): ProctoringReportData | null {
  const stored = demoReports.get(attemptId) ?? loadManagerReviewQueue().find((item) => item.attempt_id === attemptId);
  return stored ? clone(stored) : null;
}

export function getActiveFraudBan(specializationId: string, grade: string): FraudBanRecord | null {
  const ban = demoBans.get(`${specializationId}:${grade}`);
  if (!ban || new Date(ban.unban_at).getTime() <= Date.now()) return null;
  const daysLeft = Math.max(1, Math.ceil((new Date(ban.unban_at).getTime() - Date.now()) / 86_400_000));
  return { ...ban, days_left: daysLeft };
}

export function applyFraudBan({
  attemptId,
  specializationId,
  grade,
  reason = 'DEMO signal simulation',
}: {
  attemptId: string;
  specializationId: string;
  grade: string;
  reason?: string;
}): FraudBanRecord {
  const now = new Date();
  const ban: FraudBanRecord = {
    attempt_id: attemptId,
    specialization_id: specializationId,
    grade,
    banned_at: now.toISOString(),
    unban_at: new Date(now.getTime() + 30 * 86_400_000).toISOString(),
    days_left: 30,
    reason: `DEMO only: ${reason}`,
    demo: true,
  };
  demoBans.set(`${specializationId}:${grade}`, ban);
  return { ...ban };
}

export function removeFraudBan(specializationId: string, grade: string): void {
  demoBans.delete(`${specializationId}:${grade}`);
}

export function loadManagerReviewQueue(): ProctoringReportData[] {
  if (!demoQueue) demoQueue = clone(DEFAULT_DEMO_REPORTS);
  return clone(demoQueue);
}

export function submitManagerVerdict({
  attemptId,
  decision,
  reviewerName = 'DEMO reviewer',
  comment,
}: {
  attemptId: string;
  decision: 'confirm_fraud' | 'dismiss_fraud';
  reviewerName?: string;
  comment: string;
}): ProctoringReportData | null {
  const report = loadStoredProctoringReport(attemptId);
  if (!report) return null;
  const managerVerdict: ManagerVerdict = {
    reviewed_at: new Date().toISOString(),
    reviewer_name: `DEMO · ${reviewerName}`,
    decision,
    comment,
  };
  report.manager_verdict = managerVerdict;
  report.review_status = decision === 'confirm_fraud' ? 'confirmed_fraud' : 'dismissed_clean';
  report.verdict = decision === 'confirm_fraud'
    ? 'DEMO only: the local reviewer simulation selected “signal”. No misconduct is confirmed and no grade or access changes.'
    : 'DEMO only: the local reviewer simulation dismissed the signal. No grade or access changes.';
  if (decision === 'confirm_fraud') {
    applyFraudBan({ attemptId, specializationId: report.specialization_id || 'backend-python-v1', grade: report.requested_grade || 'junior' });
  } else {
    removeFraudBan(report.specialization_id || 'backend-python-v1', report.requested_grade || 'junior');
  }
  demoReports.set(attemptId, report);
  demoQueue = loadManagerReviewQueue().map((item) => item.attempt_id === attemptId ? report : item);
  return clone(report);
}
