import type { Schemas } from '../../api';

export type RecruitingRecord = Schemas['RecruitingRecord'];
export type Stage = Schemas['RecruitingStage'];

export interface StageInfo {
  id: Stage;
  label: string;
  nextLabel?: string;
  nextStage?: Stage;
  accentColor: string;
}

export const STAGES: StageInfo[] = [
  {
    id: 'reviewing',
    label: 'На рассмотрении',
    nextLabel: 'В шорт-лист →',
    nextStage: 'shortlisted',
    accentColor: '#3b82f6', // blue
  },
  {
    id: 'shortlisted',
    label: 'В шорт-листе',
    nextLabel: 'Пригласить →',
    nextStage: 'invited',
    accentColor: '#8b5cf6', // purple
  },
  {
    id: 'invited',
    label: 'Приглашение отправлено',
    nextLabel: 'На обсуждение →',
    nextStage: 'discussion',
    accentColor: '#f59e0b', // amber
  },
  {
    id: 'discussion',
    label: 'Обсуждение',
    nextLabel: 'Закрыть этап →',
    nextStage: 'closed',
    accentColor: '#10b981', // emerald
  },
  {
    id: 'closed',
    label: 'Закрыто',
    nextLabel: '↩ В работу',
    nextStage: 'reviewing',
    accentColor: '#6b7280', // gray
  },
];

export function getStageInfo(stage: Stage): StageInfo {
  return STAGES.find((s) => s.id === stage) || STAGES[0];
}

export function computeStageCounts(records: RecruitingRecord[]): Record<Stage, number> {
  const counts: Record<Stage, number> = {
    reviewing: 0,
    shortlisted: 0,
    invited: 0,
    discussion: 0,
    closed: 0,
  };
  records.forEach((r) => {
    if (r.stage in counts) {
      counts[r.stage] += 1;
    }
  });
  return counts;
}
