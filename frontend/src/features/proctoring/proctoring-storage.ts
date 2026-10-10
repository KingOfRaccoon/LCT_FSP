import type {
  ProctoringEvent,
  ProctoringReportData,
  FraudRiskLevel,
  ManagerReviewStatus,
  FraudBanRecord,
  ManagerVerdict,
} from './proctoring-types.ts';
import { api } from '../../api.ts';

const BAN_STORAGE_KEY = 'fsp_fraud_bans';
const REVIEW_QUEUE_KEY = 'fsp_manager_fraud_queue';

const memoryStore = new Map<string, string>();
const memoryVideoStore = new Map<string, Blob>();

const IDB_NAME = 'fsp_proctoring_media_db';
const IDB_STORE = 'videos';

function openVideoDb(): Promise<IDBDatabase | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  return new Promise((resolve) => {
    try {
      const request = indexedDB.open(IDB_NAME, 1);
      request.onupgradeneeded = () => {
        const db = request.result;
        if (!db.objectStoreNames.contains(IDB_STORE)) {
          db.createObjectStore(IDB_STORE);
        }
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

export async function saveProctoringVideoBlob(attemptId: string, blob: Blob, type: 'camera' | 'screen' = 'camera'): Promise<void> {
  const key = type === 'screen' ? `${attemptId}_screen` : attemptId;
  memoryVideoStore.set(key, blob);
  try {
    const db = await openVideoDb();
    if (!db) return;
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(IDB_STORE, 'readwrite');
      const store = tx.objectStore(IDB_STORE);
      store.put(blob, key);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  } catch {
    // fallback to in-memory map
  }
}

export async function loadProctoringVideoBlob(attemptId: string, type: 'camera' | 'screen' = 'camera'): Promise<Blob | null> {
  const key = type === 'screen' ? `${attemptId}_screen` : attemptId;
  if (memoryVideoStore.has(key)) {
    return memoryVideoStore.get(key)!;
  }
  try {
    const db = await openVideoDb();
    if (db) {
      const stored = await new Promise<Blob | null>((resolve) => {
        const tx = db.transaction(IDB_STORE, 'readonly');
        const store = tx.objectStore(IDB_STORE);
        const req = store.get(key);
        req.onsuccess = () => {
          const res = req.result;
          if (res instanceof Blob) {
            memoryVideoStore.set(key, res);
            resolve(res);
          } else {
            resolve(null);
          }
        };
        req.onerror = () => resolve(null);
      });
      if (stored) return stored;
    }
  } catch {
    // fallback
  }

  // If not in local IndexedDB (e.g. expert is on a different PC or device), fetch directly from the server!
  try {
    const serverUrl = `/api/v1/attempts/${attemptId}/proctoring/media?type=${type}`;
    const res = await fetch(serverUrl);
    if (res.ok) {
      const blob = await res.blob();
      if (blob && blob.size > 0) {
        memoryVideoStore.set(key, blob);
        await saveProctoringVideoBlob(attemptId, blob, type);
        return blob;
      }
    }
  } catch {
    // network fallback
  }

  // Generate placeholder ONLY for pre-seeded static demo attempts (e.g. att-demo-01)
  if (attemptId.startsWith('att-demo-')) {
    try {
      const title = type === 'screen' ? `Запись экрана · ${attemptId.slice(0, 12)}` : `Веб-камера · ${attemptId.slice(0, 12)}`;
      const demoBlob = await createPlaceholderProctoringVideoBlob(title);
      await saveProctoringVideoBlob(attemptId, demoBlob, type);
      return demoBlob;
    } catch {
      return null;
    }
  }

  return null;
}

export function createPlaceholderProctoringVideoBlob(title: string = 'Сессия прокторинга'): Promise<Blob> {
  if (typeof document === 'undefined') {
    return Promise.resolve(new Blob(['mock-video-binary-data'], { type: 'video/webm' }));
  }
  return new Promise((resolve) => {
    try {
      const canvas = document.createElement('canvas');
      canvas.width = 640;
      canvas.height = 480;
      const ctx = canvas.getContext('2d');
      if (!ctx || typeof canvas.captureStream !== 'function' || typeof MediaRecorder === 'undefined') {
        resolve(new Blob(['mock-video-binary-data'], { type: 'video/webm' }));
        return;
      }

      const stream = canvas.captureStream(20);
      const mimeTypes = ['video/webm;codecs=vp8', 'video/webm', 'video/mp4'];
      const chosenMime = mimeTypes.find((m) => MediaRecorder.isTypeSupported(m)) || 'video/webm';
      const recorder = new MediaRecorder(stream, { mimeType: chosenMime });
      const chunks: Blob[] = [];

      recorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) chunks.push(e.data);
      };

      recorder.onstop = () => {
        resolve(new Blob(chunks, { type: chosenMime }));
      };

      recorder.start(100);

      const totalDurationMs = 2500;
      const startTime = Date.now();

      const drawFrame = () => {
        const elapsed = Date.now() - startTime;
        const progress = Math.min(1, elapsed / totalDurationMs);
        const sec = Math.floor(elapsed / 1000);
        const ms = Math.floor((elapsed % 1000) / 10);

        // Background
        ctx.fillStyle = '#0a0f1d';
        ctx.fillRect(0, 0, 640, 480);

        // Header accent
        ctx.fillStyle = '#402fff';
        ctx.fillRect(0, 0, 640, 6);

        // Title
        ctx.fillStyle = '#6366f1';
        ctx.font = 'bold 22px system-ui, sans-serif';
        ctx.fillText(title, 36, 60);

        // Info
        ctx.fillStyle = '#94a3b8';
        ctx.font = '14px monospace';
        ctx.fillText(`Запись сессии · ${new Date().toLocaleDateString('ru-RU')} ${new Date().toLocaleTimeString('ru-RU')}`, 36, 100);
        ctx.fillText('Синхронизировано с таймлайном телеметрии нарушений', 36, 130);
        
        ctx.fillStyle = '#38bdf8';
        ctx.fillText('Камера: 640x480 @ 20fps | Аудио: OK | Экран: активен', 36, 165);

        // Video frame simulation box
        ctx.strokeStyle = '#334155';
        ctx.lineWidth = 2;
        ctx.strokeRect(36, 200, 568, 190);

        ctx.fillStyle = '#1e293b';
        ctx.fillRect(38, 202, 564, 186);

        // Candidate silhouette / face indicator
        ctx.fillStyle = '#475569';
        ctx.beginPath();
        ctx.arc(320, 270, 36, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.arc(320, 360, 65, Math.PI, 0, false);
        ctx.fill();

        // Overlay status in frame
        ctx.fillStyle = '#22c55e';
        ctx.font = 'bold 13px system-ui, sans-serif';
        ctx.fillText('● ВЕБ-КАМЕРА: АКТИВНА', 56, 235);

        ctx.fillStyle = '#f59e0b';
        ctx.fillText('● ЭКРАН: ЗАХВАТ ВКЛЮЧЕН', 250, 235);

        // Live timer
        ctx.fillStyle = '#e2e8f0';
        ctx.font = 'bold 18px monospace';
        const timecode = `00:${String(sec).padStart(2, '0')}.${String(ms).padStart(2, '0')} / 00:06.00`;
        ctx.fillText(`REC ${timecode}`, 420, 370);

        // Progress line
        ctx.fillStyle = '#1e293b';
        ctx.fillRect(36, 420, 568, 8);
        ctx.fillStyle = '#402fff';
        ctx.fillRect(36, 420, 568 * progress, 8);

        if (elapsed < totalDurationMs) {
          requestAnimationFrame(drawFrame);
        } else {
          try {
            if (recorder.state === 'recording') {
              recorder.stop();
            }
          } catch {
            resolve(new Blob(['mock-video-binary-data'], { type: 'video/webm' }));
          }
        }
      };

      drawFrame();
    } catch {
      resolve(new Blob(['mock-video-binary-data'], { type: 'video/webm' }));
    }
  });
}

const safeStorage = {
  getItem(key: string): string | null {
    if (typeof localStorage !== 'undefined') {
      try {
        return localStorage.getItem(key);
      } catch {
        // fallback to memory
      }
    }
    return memoryStore.get(key) ?? null;
  },
  setItem(key: string, val: string): void {
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem(key, val);
      } catch {
        // fallback to memory
      }
    }
    memoryStore.set(key, val);
  },
  removeItem(key: string): void {
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.removeItem(key);
      } catch {
        // fallback to memory
      }
    }
    memoryStore.delete(key);
  },
};

export const DEFAULT_DEMO_REPORTS: ProctoringReportData[] = [
  {
    session_id: 'proc-demo-suspicious-01',
    attempt_id: 'att-demo-01',
    candidate_id: 'c-demo-1',
    candidate_name: 'Иван Кузнецов',
    specialization_id: 'backend-python-v1',
    requested_grade: 'junior',
    started_at: new Date(Date.now() - 3600000 * 3).toISOString(),
    completed_at: new Date(Date.now() - 3600000 * 2.5).toISOString(),
    duration_seconds: 1800,
    video_available: true,
    fraud_score: 58,
    risk_level: 'fraud',
    review_status: 'review_required',
    verdict:
      'Есть подозрение на фрод: зафиксированы отводы взгляда от монитора и переключение на сторонние вкладки. Ожидает проверки экспертом платформы.',
    events_summary: {
      total_events: 5,
      tab_switches: 2,
      window_blurs: 1,
      face_missing_count: 0,
      multiple_faces_count: 0,
      gaze_deviations: 2,
      clipboard_actions: 0,
      screen_interruptions: 0,
    },
    events: [
      {
        id: 'ev-1',
        session_id: 'proc-demo-suspicious-01',
        event_type: 'tab_hidden',
        severity: 'high',
        timestamp: new Date(Date.now() - 3600000 * 2.8).toISOString(),
        duration_seconds: 4,
      },
      {
        id: 'ev-2',
        session_id: 'proc-demo-suspicious-01',
        event_type: 'gaze_deviation',
        severity: 'high',
        timestamp: new Date(Date.now() - 3600000 * 2.7).toISOString(),
        duration_seconds: 3,
      },
      {
        id: 'ev-3',
        session_id: 'proc-demo-suspicious-01',
        event_type: 'window_blur',
        severity: 'medium',
        timestamp: new Date(Date.now() - 3600000 * 2.65).toISOString(),
      },
      {
        id: 'ev-4',
        session_id: 'proc-demo-suspicious-01',
        event_type: 'gaze_deviation',
        severity: 'medium',
        timestamp: new Date(Date.now() - 3600000 * 2.6).toISOString(),
        duration_seconds: 2,
      },
      {
        id: 'ev-5',
        session_id: 'proc-demo-suspicious-01',
        event_type: 'tab_hidden',
        severity: 'high',
        timestamp: new Date(Date.now() - 3600000 * 2.55).toISOString(),
        duration_seconds: 2,
      },
    ],
  },
  {
    session_id: 'proc-demo-fraud-02',
    attempt_id: 'att-demo-02',
    candidate_id: 'c-demo-2',
    candidate_name: 'Анна Морозова',
    specialization_id: 'backend-python-v1',
    requested_grade: 'middle',
    started_at: new Date(Date.now() - 86400000 * 2).toISOString(),
    completed_at: new Date(Date.now() - 86400000 * 2 + 1500000).toISOString(),
    duration_seconds: 1500,
    video_available: true,
    fraud_score: 85,
    risk_level: 'fraud',
    review_status: 'confirmed_fraud',
    manager_verdict: {
      reviewed_at: new Date(Date.now() - 86400000 * 1.8).toISOString(),
      reviewer_name: 'Эксперт ФСП (Менеджер)',
      decision: 'confirm_fraud',
      comment: 'Подтверждено использование сторонней помощи по видеозаписи и консоли браузера.',
    },
    verdict:
      'Нарушение регламента подтверждено экспертом. Попытка аннулирована. Допуск к сдаче на грейд Middle заблокирован на 30 дней.',
    events_summary: {
      total_events: 6,
      tab_switches: 3,
      window_blurs: 1,
      face_missing_count: 1,
      multiple_faces_count: 1,
      gaze_deviations: 0,
      clipboard_actions: 0,
      screen_interruptions: 0,
    },
    events: [
      {
        id: 'ev-6',
        session_id: 'proc-demo-fraud-02',
        event_type: 'multiple_faces',
        severity: 'critical',
        timestamp: new Date(Date.now() - 86400000 * 2 + 400000).toISOString(),
      },
      {
        id: 'ev-7',
        session_id: 'proc-demo-fraud-02',
        event_type: 'devtools_attempt',
        severity: 'critical',
        timestamp: new Date(Date.now() - 86400000 * 2 + 800000).toISOString(),
      },
      {
        id: 'ev-8',
        session_id: 'proc-demo-fraud-02',
        event_type: 'camera_disabled',
        severity: 'critical',
        timestamp: new Date(Date.now() - 86400000 * 2 + 1100000).toISOString(),
      },
    ],
  },
  {
    session_id: 'proc-demo-clean-03',
    attempt_id: 'att-demo-03',
    candidate_id: 'c-demo-3',
    candidate_name: 'Сергей Васильев',
    specialization_id: 'backend-python-v1',
    requested_grade: 'junior',
    started_at: new Date(Date.now() - 86400000 * 4).toISOString(),
    completed_at: new Date(Date.now() - 86400000 * 4 + 1700000).toISOString(),
    duration_seconds: 1700,
    video_available: true,
    fraud_score: 8,
    risk_level: 'clean',
    review_status: 'auto_passed',
    verdict: 'Сессия сдана без существенных нарушений регламента. Результат подтвержден.',
    events_summary: {
      total_events: 1,
      tab_switches: 0,
      window_blurs: 0,
      face_missing_count: 0,
      multiple_faces_count: 0,
      gaze_deviations: 0,
      clipboard_actions: 0,
      screen_interruptions: 0,
    },
    events: [
      {
        id: 'ev-9',
        session_id: 'proc-demo-clean-03',
        event_type: 'context_menu',
        severity: 'low',
        timestamp: new Date(Date.now() - 86400000 * 4 + 500000).toISOString(),
      },
    ],
  },
];

export function getFraudStatusBadgeInfo(
  reviewStatus?: ManagerReviewStatus | null,
  attemptStatus?: string | null
): {
  label: string;
  tone: 'good' | 'warm' | 'danger' | 'neutral';
  description: string;
} {
  if (reviewStatus === 'confirmed_fraud') {
    return {
      label: '⛔ Фрод подтвержден',
      tone: 'danger',
      description: 'Попытка аннулирована из-за подтвержденного нарушения регламента честной сдачи.',
    };
  }
  if (reviewStatus === 'review_required') {
    return {
      label: '⚠️ На проверке эксперта',
      tone: 'warm',
      description: 'Зафиксированы отклонения от регламента. Видеозапись и протокол сессии ожидают решения эксперта.',
    };
  }
  if (reviewStatus === 'dismissed_clean') {
    return {
      label: '✓ Фрод: оправдан',
      tone: 'good',
      description: 'Проверено экспертом: подозрения отклонены, результат экзамена зачтен.',
    };
  }
  if (reviewStatus === 'auto_passed') {
    return {
      label: '✓ Фрод: нарушений нет',
      tone: 'good',
      description: 'Сессия сдана честно по регламенту платформы.',
    };
  }
  if (attemptStatus === 'invalidated') {
    return {
      label: '⛔ Аннулирована',
      tone: 'danger',
      description: 'Попытка аннулирована.',
    };
  }
  if (attemptStatus === 'in_progress') {
    return {
      label: 'Идёт экзамен',
      tone: 'neutral',
      description: 'Экзамен в процессе сдачи.',
    };
  }
  if (attemptStatus === 'submitted') {
    return {
      label: 'На проверке',
      tone: 'warm',
      description: 'Ответы переданы на обработку.',
    };
  }
  return {
    label: '✓ Фрод: проверено',
    tone: 'neutral',
    description: 'Сессия завершена.',
  };
}

export function calculateFraudScore(events: ProctoringEvent[]): {
  fraudScore: number;
  riskLevel: FraudRiskLevel;
  reviewStatus: ManagerReviewStatus;
  verdict: string;
} {
  let score = 0;

  for (const ev of events) {
    switch (ev.event_type) {
      case 'tab_hidden':
        score += 18 + Math.min(25, ((ev.duration_seconds || 1) - 1) * 3);
        break;
      case 'window_blur':
        score += 8;
        break;
      case 'gaze_deviation':
        score += 15 + Math.min(20, (ev.duration_seconds || 1) * 2);
        break;
      case 'hotkey_switch':
        score += 20;
        break;
      case 'devtools_attempt':
        score += 45;
        break;
      case 'face_missing':
        score += 20;
        break;
      case 'multiple_faces':
        score += 30;
        break;
      case 'camera_disabled':
        score += 45;
        break;
      case 'screen_share_stopped':
        score += 40;
        break;
      case 'copy_attempt':
      case 'paste_attempt':
        score += 15;
        break;
      case 'context_menu':
      case 'fullscreen_exit':
        score += 8;
        break;
      default:
        score += 5;
    }
  }

  const finalScore = Math.min(100, Math.max(0, Math.round(score)));

  let riskLevel: FraudRiskLevel = 'clean';
  let reviewStatus: ManagerReviewStatus = 'auto_passed';
  let verdict = 'Сессия сдана без существенных нарушений регламента. Результат подтвержден.';

  if (finalScore > 50) {
    riskLevel = 'fraud';
    reviewStatus = 'review_required';
    verdict =
      'Есть подозрение, что вы нечестно прошли экзамен и использовали запрещенные способы получения информации. Ваш экзамен передан на обязательную проверку экспертом платформы. При подтверждении нарушений пересдача будет заблокирована на 30 дней.';
  } else if (finalScore > 25) {
    riskLevel = 'suspicious';
    reviewStatus = 'review_required';
    verdict =
      'Есть подозрение, что вы нечестно сдали экзамен (зафиксированы отклонения взгляда и смена окон). Ваш экзамен передан на проверку экспертом платформы. При подтверждении нарушений пересдача будет заблокирована на 30 дней.';
  }

  return { fraudScore: finalScore, riskLevel, reviewStatus, verdict };
}

export async function buildProctoringReport({
  sessionId,
  attemptId,
  candidateId = 'c-demo-1',
  candidateName = 'Алексей Смирнов',
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

  const durationSeconds = Math.max(
    1,
    Math.round((new Date(completedAt).getTime() - new Date(startedAt).getTime()) / 1000)
  );

  let videoUrl: string | undefined;
  let screenVideoUrl: string | undefined;
  if (typeof URL !== 'undefined') {
    try {
      if (videoBlob) videoUrl = URL.createObjectURL(videoBlob);
      if (screenBlob) screenVideoUrl = URL.createObjectURL(screenBlob);
    } catch {
      // fallback
    }
  }

  const report: ProctoringReportData = {
    session_id: sessionId,
    attempt_id: attemptId,
    candidate_id: candidateId,
    candidate_name: candidateName,
    specialization_id: specializationId,
    requested_grade: requestedGrade,
    started_at: startedAt,
    completed_at: completedAt,
    duration_seconds: durationSeconds,
    video_url: videoUrl,
    screen_video_url: screenVideoUrl,
    video_available: true,
    fraud_score: fraudScore,
    risk_level: riskLevel,
    review_status: reviewStatus,
    verdict,
    events_summary: {
      total_events: events.length,
      tab_switches: events.filter((e) => e.event_type === 'tab_hidden').length,
      window_blurs: events.filter((e) => e.event_type === 'window_blur').length,
      face_missing_count: events.filter((e) => e.event_type === 'face_missing').length,
      multiple_faces_count: events.filter((e) => e.event_type === 'multiple_faces').length,
      gaze_deviations: events.filter((e) => e.event_type === 'gaze_deviation').length,
      clipboard_actions: events.filter(
        (e) => e.event_type === 'copy_attempt' || e.event_type === 'paste_attempt'
      ).length,
      screen_interruptions: events.filter(
        (e) => e.event_type === 'screen_share_stopped' || e.event_type === 'camera_disabled'
      ).length,
    },
    events,
  };

  // 1. Cache attempt report
  try {
    const serialized = JSON.stringify({ ...report, video_url: undefined, screen_video_url: undefined });
    safeStorage.setItem(`fsp_proctoring_${attemptId}`, serialized);
  } catch {
    // quota fallback
  }

  // 2. Persist recorded camera and screen videos into IndexedDB so they survive refreshes
  try {
    if (videoBlob && videoBlob.size > 0) {
      await saveProctoringVideoBlob(attemptId, videoBlob, 'camera');
      fetch(`/api/v1/attempts/${attemptId}/proctoring/media?type=camera`, {
        method: 'POST',
        headers: { 'Content-Type': videoBlob.type || 'video/webm' },
        body: videoBlob,
      }).catch((e) => console.warn('Failed to upload camera video to server:', e));
    }
  } catch {
    // ignore
  }

  try {
    if (screenBlob && screenBlob.size > 0) {
      await saveProctoringVideoBlob(attemptId, screenBlob, 'screen');
      fetch(`/api/v1/attempts/${attemptId}/proctoring/media?type=screen`, {
        method: 'POST',
        headers: { 'Content-Type': screenBlob.type || 'video/webm' },
        body: screenBlob,
      }).catch((e) => console.warn('Failed to upload screen video to server:', e));
    }
  } catch {
    // ignore
  }

  // Upload report to server so expert on any device can access it
  fetch(`/api/v1/attempts/${attemptId}/proctoring/report`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(report),
  }).catch((e) => console.warn('Failed to upload proctoring report to server:', e));

  // 3. Enqueue for manager / platform staff verification queue
  try {
    const rawQueue = safeStorage.getItem(REVIEW_QUEUE_KEY);
    const queue: ProctoringReportData[] = rawQueue ? JSON.parse(rawQueue) : [...DEFAULT_DEMO_REPORTS];
    const filtered = queue.filter((item) => item.attempt_id !== attemptId);
    filtered.unshift({ ...report, video_url: undefined, screen_video_url: undefined });
    safeStorage.setItem(REVIEW_QUEUE_KEY, JSON.stringify(filtered));
  } catch {
    // quota fallback
  }

  // Asynchronous telemetry post
  api.post('/proctoring/events', {
    session_id: sessionId,
    attempt_id: attemptId,
    fraud_score: fraudScore,
    risk_level: riskLevel,
    review_status: reviewStatus,
    events_count: events.length,
  } as any).catch(() => {});

  return report;
}

export function loadStoredProctoringReport(attemptId: string): ProctoringReportData | null {
  try {
    const raw = safeStorage.getItem(`fsp_proctoring_${attemptId}`);
    if (raw) {
      return JSON.parse(raw) as ProctoringReportData;
    }
    const fromQueue = loadManagerReviewQueue().find((item) => item.attempt_id === attemptId);
    if (fromQueue) return fromQueue;

    const fromDemo = DEFAULT_DEMO_REPORTS.find((item) => item.attempt_id === attemptId);
    if (fromDemo) return fromDemo;
  } catch {
    // fallback
  }
  return null;
}

// ----------------------------------------------------
// 30-Day Fraud Ban Management
// ----------------------------------------------------

export function getActiveFraudBan(specializationId: string, grade: string): FraudBanRecord | null {
  try {
    const raw = safeStorage.getItem(BAN_STORAGE_KEY);
    if (!raw) return null;
    const bans: FraudBanRecord[] = JSON.parse(raw);
    const now = Date.now();

    const match = bans.find(
      (b) =>
        b.specialization_id === specializationId &&
        b.grade === grade &&
        new Date(b.unban_at).getTime() > now
    );

    if (match) {
      const msLeft = new Date(match.unban_at).getTime() - now;
      const daysLeft = Math.max(1, Math.ceil(msLeft / (1000 * 60 * 60 * 24)));
      return { ...match, days_left: daysLeft };
    }
  } catch {
    // fallback
  }
  return null;
}

export function applyFraudBan({
  attemptId,
  specializationId,
  grade,
  reason = 'Использование запрещенных источников и фальсификация сдачи экзамена',
}: {
  attemptId: string;
  specializationId: string;
  grade: string;
  reason?: string;
}): FraudBanRecord {
  const now = new Date();
  const unbanDate = new Date(now.getTime() + 30 * 24 * 60 * 60 * 1000); // +30 days

  const banRecord: FraudBanRecord = {
    attempt_id: attemptId,
    specialization_id: specializationId,
    grade,
    banned_at: now.toISOString(),
    unban_at: unbanDate.toISOString(),
    days_left: 30,
    reason,
  };

  try {
    const raw = safeStorage.getItem(BAN_STORAGE_KEY);
    const bans: FraudBanRecord[] = raw ? JSON.parse(raw) : [];
    const filtered = bans.filter(
      (b) => !(b.specialization_id === specializationId && b.grade === grade)
    );
    filtered.push(banRecord);
    safeStorage.setItem(BAN_STORAGE_KEY, JSON.stringify(filtered));
  } catch {
    // fallback
  }

  return banRecord;
}

export function removeFraudBan(specializationId: string, grade: string): void {
  try {
    const raw = safeStorage.getItem(BAN_STORAGE_KEY);
    if (!raw) return;
    const bans: FraudBanRecord[] = JSON.parse(raw);
    const filtered = bans.filter(
      (b) => !(b.specialization_id === specializationId && b.grade === grade)
    );
    safeStorage.setItem(BAN_STORAGE_KEY, JSON.stringify(filtered));
  } catch {
    // fallback
  }
}

// ----------------------------------------------------
// Manager Expert Review Actions & Review Queue
// ----------------------------------------------------

export function loadManagerReviewQueue(): ProctoringReportData[] {
  try {
    const raw = safeStorage.getItem(REVIEW_QUEUE_KEY);
    if (raw) {
      const list = JSON.parse(raw) as ProctoringReportData[];
      if (list.length > 0) return list;
    }
    // initialize with default reports if empty
    safeStorage.setItem(REVIEW_QUEUE_KEY, JSON.stringify(DEFAULT_DEMO_REPORTS));
    return DEFAULT_DEMO_REPORTS;
  } catch {
    // fallback
  }
  return DEFAULT_DEMO_REPORTS;
}

export async function fetchServerReviewQueue(): Promise<ProctoringReportData[]> {
  try {
    const res = await fetch('/api/v1/proctoring/queue');
    if (res.ok) {
      const json = await res.json();
      if (Array.isArray(json.data) && json.data.length > 0) {
        const local = loadManagerReviewQueue();
        const serverIds = new Set(json.data.map((item: any) => item.attempt_id));
        const merged = [...json.data, ...local.filter((item) => !serverIds.has(item.attempt_id))];
        safeStorage.setItem(REVIEW_QUEUE_KEY, JSON.stringify(merged));
        return merged;
      }
    }
  } catch {
    // fallback
  }
  return loadManagerReviewQueue();
}

export function submitManagerVerdict({
  attemptId,
  decision,
  reviewerName = 'Эксперт платформы ФСП',
  comment,
}: {
  attemptId: string;
  decision: 'confirm_fraud' | 'dismiss_fraud';
  reviewerName?: string;
  comment: string;
}): ProctoringReportData | null {
  const report = loadStoredProctoringReport(attemptId);
  if (!report) return null;

  const verdictRecord: ManagerVerdict = {
    reviewed_at: new Date().toISOString(),
    reviewer_name: reviewerName,
    decision,
    comment,
  };

  report.manager_verdict = verdictRecord;

  // Sync verdict with server
  fetch(`/api/v1/attempts/${attemptId}/proctoring/verdict`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      decision,
      reviewer_name: reviewerName,
      comment,
    }),
  }).catch((e) => console.warn('Failed to submit verdict to server:', e));

  if (decision === 'confirm_fraud') {
    report.review_status = 'confirmed_fraud';
    report.verdict = `Нарушение регламента подтверждено экспертом (${reviewerName}). Попытка аннулирована. Допуск к сдаче на грейд ${report.requested_grade || 'Junior'} заблокирован на 30 дней.`;
    // Apply 30-day ban
    applyFraudBan({
      attemptId: report.attempt_id,
      specializationId: report.specialization_id || 'backend-python-v1',
      grade: report.requested_grade || 'junior',
      reason: comment || 'Фрод подтвержден по результатам проверки видеозаписи экспертом платформы',
    });
  } else {
    report.review_status = 'dismissed_clean';
    report.verdict = `Подозрения отклонены экспертом (${reviewerName}). Экзамен признан честным, грейд подтвержден.`;
    removeFraudBan(report.specialization_id || 'backend-python-v1', report.requested_grade || 'junior');
  }

  // Update cached report
  try {
    safeStorage.setItem(`fsp_proctoring_${attemptId}`, JSON.stringify(report));
    // Update queue
    const queue = loadManagerReviewQueue();
    const existingIdx = queue.findIndex((q) => q.attempt_id === attemptId);
    let updatedQueue: ProctoringReportData[];
    if (existingIdx >= 0) {
      updatedQueue = queue.map((q) => (q.attempt_id === attemptId ? report : q));
    } else {
      updatedQueue = [report, ...queue];
    }
    safeStorage.setItem(REVIEW_QUEUE_KEY, JSON.stringify(updatedQueue));
  } catch {
    // fallback
  }

  return report;
}
