export type ProctoringSeverity = 'low' | 'medium' | 'high' | 'critical';

export type ProctoringEventType =
  | 'tab_hidden'
  | 'window_blur'
  | 'fullscreen_exit'
  | 'copy_attempt'
  | 'paste_attempt'
  | 'context_menu'
  | 'window_resize'
  | 'face_missing'
  | 'multiple_faces'
  | 'gaze_deviation'
  | 'hotkey_switch'
  | 'devtools_attempt'
  | 'camera_disabled'
  | 'screen_share_stopped'
  | 'audio_anomaly';

export interface ProctoringEvent {
  id: string;
  session_id: string;
  event_type: ProctoringEventType;
  severity: ProctoringSeverity;
  timestamp: string; // ISO string
  duration_seconds?: number;
  metadata?: Record<string, string | number | boolean>;
}

export type FraudRiskLevel = 'clean' | 'suspicious' | 'fraud';

export type ManagerReviewStatus =
  | 'auto_passed'        // Legacy demo presentation code; not an assessment decision
  | 'review_required'    // Local demo signal; not a human review queue
  | 'confirmed_fraud'    // Local demo label; never changes grade or access
  | 'dismissed_clean';   // Local demo label; never confirms a grade

export interface ManagerVerdict {
  reviewed_at: string;
  reviewer_name: string;
  decision: 'confirm_fraud' | 'dismiss_fraud';
  comment: string;
}

export interface ProctoringReportData {
  /** Every report in this client-only feature is synthetic or locally simulated. */
  demo: boolean;
  session_id: string;
  attempt_id: string;
  candidate_id?: string;
  candidate_name?: string;
  specialization_id?: string;
  requested_grade?: string;
  started_at: string;
  completed_at?: string;
  duration_seconds: number;
  video_url?: string; // Camera recording
  screen_video_url?: string; // Screen share recording
  video_available: boolean;
  fraud_score: number; // 0..100
  risk_level: FraudRiskLevel;
  review_status: ManagerReviewStatus;
  manager_verdict?: ManagerVerdict;
  verdict: string;
  events_summary: {
    total_events: number;
    tab_switches: number;
    window_blurs: number;
    face_missing_count: number;
    multiple_faces_count: number;
    gaze_deviations: number;
    clipboard_actions: number;
    screen_interruptions: number;
  };
  events: ProctoringEvent[];
}

export interface FraudBanRecord {
  attempt_id: string;
  specialization_id: string;
  grade: string;
  banned_at: string;
  unban_at: string;
  days_left: number;
  reason: string;
  demo: true;
}

export interface ProctoringSetupState {
  cameraReady: boolean;
  micReady: boolean;
  screenReady: boolean;
  consentGiven: boolean;
}
