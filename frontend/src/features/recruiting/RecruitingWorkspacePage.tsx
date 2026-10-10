import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type ApiPayload, type Schemas } from '../../api';
import { CandidateAutocomplete, type CandidateOption } from './CandidateAutocomplete';
import { NeedSelect } from './NeedSelect';
import {
  RecruitingFunnel,
  RecruitingKanban,
  getStageInfo,
  type RecruitingRecord,
  type Stage,
} from './RecruitingKanban';
import { RecruitingRecordEditor } from './RecruitingRecordEditor';
import './recruiting.css';

export function RecruitingWorkspacePage() {
  const [records, setRecords] = useState<RecruitingRecord[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<string | null>(null);

  // Filters state (UUIDs are internally tracked, but selected via UI dropdown/autocomplete)
  const [candidateFilter, setCandidateFilter] = useState('');
  const [needFilter, setNeedFilter] = useState('');
  const [applied, setApplied] = useState({ candidate: '', need: '' });

  // Funnel & View state
  const [activeStageFilter, setActiveStageFilter] = useState<Stage | null>(null);
  const [viewMode, setViewMode] = useState<'kanban' | 'list'>('kanban');

  // Loading & mutation state
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [pendingRecordIds, setPendingRecordIds] = useState<Set<string>>(new Set());

  // Editing dialog state
  const [editing, setEditing] = useState<{ candidateId: string; needId: string | null }>();

  // Fetch records
  useEffect(() => {
    let current = true;
    setLoading(true);
    setError('');

    const query = new URLSearchParams({ limit: '50' });
    if (cursor) query.set('cursor', cursor);
    if (applied.candidate) query.set('candidate_id', applied.candidate);
    if (applied.need) query.set('need_id', applied.need);

    if (!cursor) setRecords([]);

    api.get<Schemas['RecruitingRecordPage']>(`/recruiting/records?${query.toString()}`)
      .then((page) => {
        if (!current) return;
        setRecords((existing) => (cursor ? [...existing, ...page.items] : page.items));
        setNextCursor(page.next_cursor);
      })
      .catch((reason: unknown) => {
        if (current) setError(errorMessage(reason));
      })
      .finally(() => {
        if (current) setLoading(false);
      });

    return () => {
      current = false;
    };
  }, [cursor, applied, revision]);

  const submitFilter = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setCursor(null);
    setApplied({ candidate: candidateFilter.trim(), need: needFilter.trim() });
  };

  const resetFilters = () => {
    setCandidateFilter('');
    setNeedFilter('');
    setCursor(null);
    setApplied({ candidate: '', need: '' });
    setActiveStageFilter(null);
  };

  // Optimistic 1-click stage advancement
  const handleStageChange = async (record: RecruitingRecord, nextStage: Stage) => {
    if (record.stage === nextStage) return;

    // Optimistically update records
    const previousRecords = [...records];
    setRecords((prev) =>
      prev.map((r) =>
        r.id === record.id
          ? {
              ...r,
              stage: nextStage,
              updated_at: new Date().toISOString(),
              version: r.version + 1,
            }
          : r,
      ),
    );

    setPendingRecordIds((prev) => new Set(prev).add(record.id));

    try {
      const payload: Schemas['RecruitingRecordPutRequest'] = {
        expected_version: record.version,
        need_id: record.need_id,
        stage: nextStage,
        note: record.note,
      };

      const updated = await api.put<RecruitingRecord>(
        `/recruiting/records/${encodeURIComponent(record.candidate_id)}`,
        payload as ApiPayload,
      );

      // Reconcile with authoritative server response
      setRecords((prev) => prev.map((r) => (r.id === record.id ? updated : r)));
    } catch (err) {
      // Revert optimistic update on failure
      setRecords(previousRecords);
      setError(errorMessage(err));
    } finally {
      setPendingRecordIds((prev) => {
        const next = new Set(prev);
        next.delete(record.id);
        return next;
      });
    }
  };

  // Displayed records filtered by active stage in funnel
  const filteredRecords = activeStageFilter
    ? records.filter((r) => r.stage === activeStageFilter)
    : records;

  return (
    <div className="employer-page recruiting-workspace-page">
      <header className="page-header">
        <div>
          <div className="eyebrow">РАБОТА С КАНДИДАТАМИ</div>
          <h1>Рабочая воронка</h1>
          <p>
            Приватные заметки и этапы вашей компании. Они не влияют на оценку кандидата и не
            означают, что приглашение принято или контакт открыт.
          </p>
        </div>
      </header>

      {/* Top action controls & ATS export */}
      <div className="recruiting-toolbar">
        <div className="recruiting-toolbar-left">
          <button
            type="button"
            className="button button-secondary button-small"
            onClick={() => void api.download('/recruiting/export/ats?format=json', 'fsp-records-export.json')}
            title="Выгрузить данные кандидатов в формате ATS JSON"
          >
            📥 Экспорт профилей (JSON)
          </button>

          <div className="recruiting-view-toggle" role="group" aria-label="Режим отображения">
            <button
              type="button"
              className={`toggle-btn ${viewMode === 'kanban' ? 'active' : ''}`}
              onClick={() => setViewMode('kanban')}
              title="Переключить на Канбан-доску"
            >
              ☷ Воронка (Канбан)
            </button>
            <button
              type="button"
              className={`toggle-btn ${viewMode === 'list' ? 'active' : ''}`}
              onClick={() => setViewMode('list')}
              title="Переключить на табличный список"
            >
              ☰ Список
            </button>
          </div>
        </div>

        <button
          type="button"
          className="button button-primary recruiting-new-record-btn"
          onClick={() => setEditing({ candidateId: '', needId: applied.need || null })}
        >
          ＋ Новая запись
        </button>
      </div>

      {/* Interactive top funnel pipeline with counters */}
      <RecruitingFunnel
        records={records}
        activeStageFilter={activeStageFilter}
        onSelectStage={setActiveStageFilter}
      />

      {/* Filter Form without manual UUID inputs */}
      <form className="card employer-record-filters recruiting-filter-form" onSubmit={submitFilter}>
        <div className="recruiting-filter-field">
          <label htmlFor="recruiting-candidate-filter">Кандидат</label>
          <CandidateAutocomplete
            id="recruiting-candidate-filter"
            value={candidateFilter}
            onChange={(cand: CandidateOption | null) => setCandidateFilter(cand?.id || '')}
            existingRecords={records}
            filterMode
            allowClear
            placeholder="Все кандидаты или поиск по имени…"
          />
        </div>

        <div className="recruiting-filter-field">
          <label htmlFor="recruiting-need-filter">Потребность</label>
          <NeedSelect
            id="recruiting-need-filter"
            value={needFilter}
            onChange={setNeedFilter}
            allOptionLabel="Все потребности компании"
            includeAllOption
          />
        </div>

        <div className="recruiting-filter-actions">
          <button type="submit" className="button button-secondary">
            Применить фильтры
          </button>
          {(candidateFilter || needFilter || applied.candidate || applied.need) && (
            <button
              type="button"
              className="button button-secondary"
              onClick={resetFilters}
              title="Сбросить выбранные фильтры"
            >
              Сбросить
            </button>
          )}
        </div>
      </form>

      {error && (
        <div className="error-box" role="alert">
          <span>{error}</span>
          <button
            className="button button-secondary button-small"
            onClick={() => setRevision((v) => v + 1)}
          >
            Повторить
          </button>
        </div>
      )}

      {loading ? (
        <div className="loading">
          <span className="spinner" aria-hidden="true" />
          Загружаем записи вашей компании…
        </div>
      ) : records.length === 0 ? (
        <div className="empty">
          <div className="empty-icon">▤</div>
          <h3>Записей пока нет</h3>
          <p>
            Записи появятся после сохранения рабочего этапа по конкретному кандидату или при
            добавлении новой записи.
          </p>
          <button
            type="button"
            className="button button-primary"
            onClick={() => setEditing({ candidateId: '', needId: applied.need || null })}
          >
            Добавить первого кандидата в воронку
          </button>
        </div>
      ) : viewMode === 'kanban' ? (
        <RecruitingKanban
          records={records}
          activeStageFilter={activeStageFilter}
          onOpenRecord={(cId, nId) => setEditing({ candidateId: cId, needId: nId })}
          onStageChange={handleStageChange}
          pendingRecordIds={pendingRecordIds}
        />
      ) : (
        <div className="employer-record-list">
          {filteredRecords.length === 0 ? (
            <div className="empty">
              <p>На выбранном этапе нет кандидатов.</p>
            </div>
          ) : (
            filteredRecords.map((record) => {
              const stageInfo = getStageInfo(record.stage);
              return (
                <article className="card employer-record-card" key={record.id}>
                  <div className="employer-record-heading">
                    <div>
                      <div className="eyebrow">
                        {record.need_title ?? 'Без привязки к потребности'} ·{' '}
                        {new Date(record.updated_at).toLocaleString('ru-RU')}
                      </div>
                      <h2>{record.candidate_display_name ?? 'Профиль скрыт'}</h2>
                      <code>{record.candidate_id}</code>
                    </div>
                    <span
                      className={`badge employer-badge ${
                        record.candidate_visibility === 'public'
                          ? 'employer-badge-good'
                          : 'employer-badge-muted'
                      }`}
                    >
                      {record.candidate_visibility === 'public'
                        ? 'Публичный профиль'
                        : 'Профиль скрыт'}
                    </span>
                  </div>

                  <div className="employer-record-body">
                    <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                      <span
                        className="badge employer-badge"
                        style={{
                          backgroundColor: stageInfo.accentColor,
                          color: '#ffffff',
                          fontWeight: 600,
                        }}
                      >
                        {stageInfo.label}
                      </span>
                      {stageInfo.nextStage && (
                        <button
                          type="button"
                          className="button button-secondary button-small"
                          onClick={() => void handleStageChange(record, stageInfo.nextStage!)}
                        >
                          {stageInfo.nextLabel}
                        </button>
                      )}
                    </div>
                    <p>{record.note || 'Личной заметки нет.'}</p>
                  </div>

                  <button
                    className="button button-secondary button-small"
                    onClick={() => setEditing({ candidateId: record.candidate_id, needId: record.need_id })}
                  >
                    Открыть свою запись
                  </button>
                </article>
              );
            })
          )}
        </div>
      )}

      {nextCursor && !loading && (
        <button
          className="button button-secondary employer-next-page"
          onClick={() => setCursor(nextCursor)}
        >
          Загрузить следующую страницу
        </button>
      )}

      {editing && (
        <RecruitingRecordEditor
          candidateId={editing.candidateId}
          needId={editing.needId}
          existingRecords={records}
          onSaved={() => {
            setEditing(undefined);
            setRevision((value) => value + 1);
          }}
          onCancel={() => setEditing(undefined)}
        />
      )}
    </div>
  );
}
