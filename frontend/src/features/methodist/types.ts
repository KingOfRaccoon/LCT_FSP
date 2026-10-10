export type Grade = 'junior' | 'middle';
export type ReviewOutcome = 'approved' | 'changes_requested' | 'rejected';
export type PilotSource = 'synthetic' | 'pilot_record';

export type DraftFields = {
  source_title: string;
  source_reference: string;
  source_excerpt: string;
  competency_id: string;
  grade: Grade;
  question: string;
  options: [string, string, string, string];
  correct_option: number;
  rubric: string;
};
export type DraftForm = Omit<DraftFields, 'correct_option'> & { correct_option: string };
export type DraftPayload = DraftFields;
export type DraftRevision = DraftFields & { version: number };
export type ReviewMarks = { relevance: boolean; level: boolean; clarity: boolean; correctness: boolean };
export type DraftReview = { actor_id: string; outcome: ReviewOutcome; marks: ReviewMarks; comment: string; reviewed_at: string };
export type MethodistDraft = DraftRevision & {
  id: string;
  status: 'draft' | ReviewOutcome;
  review?: DraftReview | null;
  created_at?: string;
  updated_at?: string;
};
export type PilotObservation = { source: PilotSource; correct: boolean };
export type PilotReport = {
  draft_id: string;
  version: number;
  status: ReviewOutcome | 'draft';
  source_title: string;
  sample_count: number;
  correct_count: number;
  accuracy: number | null;
  source_counts: Record<PilotSource, number>;
  accuracy_by_source: Record<PilotSource, number | null>;
  minimum_sample_for_statistics: 10;
  evidence_sources: PilotSource[];
  human_results_claimed: false;
};
export type DraftExport = {
  draft_id: string;
  version: number;
  candidate_form: {
    format: 'draft-v1'; source: { title: string; reference: string };
    competency_id: string; grade: Grade; question: string; options: [string, string, string, string];
  };
  private_answer_key: { format: 'draft-v1'; draft_id: string; version: number; correct_option: number; source_excerpt: string; rubric: string };
  live_bank_published: false;
};
export type DraftList = { items: MethodistDraft[] };

export type BankMonitor = {
  bank_version: string;
  families_total: number;
  coverage: { grade: Grade; competency_id: string; families_total: number; families_exposed: number; exposure_count: number }[];
  repeated_use: { grade: Grade; competency_id: string; family_id: string; exposure_count: number; variant_exposures: { variant_id: string; exposure_count: number }[]; repeated_use: boolean; review_required: boolean; review_signal: string | null }[];
  difficulty_windows: { window: string; grade: Grade; sample_count: number; mean_total_score: number | null; mean_block_scores: Record<string, number | null>; review_required: boolean; review_signal: string | null; interpretation: string }[];
  answer_keys_included: false;
  person_or_attempt_data_included: false;
};
