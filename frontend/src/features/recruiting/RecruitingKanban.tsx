import { useState } from 'react';
import { CandidateAvatar } from './CandidateAutocomplete';
import {
  STAGES,
  computeStageCounts,
  getStageInfo,
  type RecruitingRecord,
  type Stage,
  type StageInfo,
} from './recruiting-model';

export {
  STAGES,
  computeStageCounts,
  getStageInfo,
  type RecruitingRecord,
  type Stage,
  type StageInfo,
};

export interface RecruitingFunnelProps {
  records: RecruitingRecord[];
  activeStageFilter: Stage | null;
  onSelectStage: (stage: Stage | null) => void;
}

export function RecruitingFunnel({
  records,
  activeStageFilter,
  onSelectStage,
}: RecruitingFunnelProps) {
  const counts = computeStageCounts(records);
  const total = records.length;

  return (
    <nav className="recruiting-funnel" aria-label="Воронка найма по этапам">
      <button
        type="button"
        className={`funnel-step ${activeStageFilter === null ? 'active' : ''}`}
        onClick={() => onSelectStage(null)}
      >
        <span className="funnel-step-label">Все записи</span>
        <span className="funnel-badge">{total}</span>
      </button>

      <div className="funnel-steps-container">
        {STAGES.map((stage, index) => {
          const isActive = activeStageFilter === stage.id;
          const count = counts[stage.id];

          return (
            <div key={stage.id} className="funnel-step-wrapper">
              <button
                type="button"
                className={`funnel-step ${isActive ? 'active' : ''}`}
                style={{
                  borderBottomColor: isActive ? stage.accentColor : 'transparent',
                }}
                onClick={() => onSelectStage(isActive ? null : stage.id)}
                title={`Фильтр по этапу: ${stage.label}`}
              >
                <span className="funnel-step-num" style={{ color: stage.accentColor }}>
                  {index + 1}
                </span>
                <span className="funnel-step-label">{stage.label}</span>
                <span
                  className="funnel-badge"
                  style={{
                    backgroundColor: count > 0 ? stage.accentColor : undefined,
                    color: count > 0 ? '#ffffff' : undefined,
                  }}
                >
                  {count}
                </span>
              </button>
              {index < STAGES.length - 1 && (
                <span className="funnel-arrow" aria-hidden="true">
                  →
                </span>
              )}
            </div>
          );
        })}
      </div>
    </nav>
  );
}

export interface RecruitingKanbanProps {
  records: RecruitingRecord[];
  activeStageFilter: Stage | null;
  onOpenRecord: (candidateId: string, needId: string | null) => void;
  onStageChange: (record: RecruitingRecord, nextStage: Stage) => Promise<void>;
  pendingRecordIds?: Set<string>;
}

export function RecruitingKanban({
  records,
  activeStageFilter,
  onOpenRecord,
  onStageChange,
  pendingRecordIds = new Set(),
}: RecruitingKanbanProps) {
  const [movingId, setMovingId] = useState<string | null>(null);

  const displayedStages = activeStageFilter
    ? STAGES.filter((s) => s.id === activeStageFilter)
    : STAGES;

  const handleAdvance = async (record: RecruitingRecord, nextStage: Stage) => {
    if (movingId) return;
    setMovingId(record.id);
    try {
      await onStageChange(record, nextStage);
    } finally {
      setMovingId(null);
    }
  };

  return (
    <div
      className={`recruiting-kanban-board ${activeStageFilter ? 'single-column' : ''}`}
      role="region"
      aria-label="Канбан-доска кандидатов"
    >
      {displayedStages.map((stage) => {
        const columnRecords = records.filter((r) => r.stage === stage.id);

        return (
          <div key={stage.id} className="kanban-column" data-stage={stage.id}>
            <header className="kanban-column-header">
              <div className="kanban-header-title">
                <span
                  className="kanban-header-dot"
                  style={{ backgroundColor: stage.accentColor }}
                />
                <h3>{stage.label}</h3>
              </div>
              <span className="kanban-count-pill">{columnRecords.length}</span>
            </header>

            <div className="kanban-column-content">
              {columnRecords.length === 0 ? (
                <div className="kanban-empty-column">
                  <span>Нет кандидатов</span>
                </div>
              ) : (
                columnRecords.map((record) => {
                  const isPending =
                    movingId === record.id || pendingRecordIds.has(record.id);
                  const displayName = record.candidate_display_name || 'Профиль скрыт';
                  const dateStr = new Date(record.updated_at).toLocaleDateString('ru-RU', {
                    day: 'numeric',
                    month: 'short',
                    hour: '2-digit',
                    minute: '2-digit',
                  });

                  return (
                    <article
                      key={record.id}
                      className={`card kanban-card ${isPending ? 'card-pending' : ''}`}
                    >
                      <div className="kanban-card-top">
                        <div className="kanban-card-author">
                          <CandidateAvatar name={displayName} size={30} />
                          <div className="kanban-card-meta">
                            <strong className="kanban-card-name" title={displayName}>
                              {displayName}
                            </strong>
                            <span className="kanban-card-time">{dateStr}</span>
                          </div>
                        </div>

                        <span
                          className={`badge employer-badge ${
                            record.candidate_visibility === 'public'
                              ? 'employer-badge-good'
                              : 'employer-badge-muted'
                          }`}
                        >
                          {record.candidate_visibility === 'public' ? 'Публичный' : 'Скрыт'}
                        </span>
                      </div>

                      <div className="kanban-card-need">
                        <span className="kanban-need-tag" title={record.need_title || 'Без привязки'}>
                          💼 {record.need_title || 'Без привязки к потребности'}
                        </span>
                      </div>

                      {record.note ? (
                        <p className="kanban-card-note" title={record.note}>
                          «{record.note}»
                        </p>
                      ) : (
                        <p className="kanban-card-note empty">Заметки пока нет</p>
                      )}

                      <div className="kanban-card-actions">
                        <div className="kanban-card-stage-selector">
                          <select
                            className="kanban-quick-stage-select"
                            value={record.stage}
                            disabled={isPending}
                            onChange={(e) =>
                              void handleAdvance(record, e.target.value as Stage)
                            }
                            aria-label="Сменить этап кандидата"
                          >
                            {STAGES.map((s) => (
                              <option key={s.id} value={s.id}>
                                {s.label}
                              </option>
                            ))}
                          </select>
                        </div>

                        {stage.nextStage && (
                          <button
                            type="button"
                            className="button button-primary button-small kanban-advance-btn"
                            disabled={isPending}
                            onClick={() => void handleAdvance(record, stage.nextStage!)}
                            title={`Перевести на этап: ${getStageInfo(stage.nextStage).label}`}
                          >
                            {isPending ? '…' : stage.nextLabel}
                          </button>
                        )}

                        <button
                          type="button"
                          className="button button-secondary button-small kanban-edit-btn"
                          disabled={isPending}
                          onClick={() => onOpenRecord(record.candidate_id, record.need_id)}
                          title="Редактировать заметку и привязку"
                        >
                          ✏️
                        </button>
                      </div>
                    </article>
                  );
                })
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
