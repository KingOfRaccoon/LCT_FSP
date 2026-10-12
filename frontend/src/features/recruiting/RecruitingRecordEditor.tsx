import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react';
import { useLocation } from 'react-router';
import { api, errorMessage, type ApiPayload, type Schemas } from '../../api';
import { CandidateAutocomplete, type CandidateOption } from './CandidateAutocomplete';
import { NeedSelect } from './NeedSelect';
import { STAGES, type Stage } from './RecruitingKanban';

export type RecruitingRecord = Schemas['RecruitingRecord'];

export interface RecruitingRecordEditorProps {
  candidateId: string;
  needId?: string | null;
  onSaved?: (record: RecruitingRecord) => void;
  onCancel?: () => void;
  existingRecords?: RecruitingRecord[];
}

export function RecruitingRecordEditor({
  candidateId,
  needId,
  onSaved,
  onCancel,
  existingRecords = [],
}: RecruitingRecordEditorProps) {
  const [current, setCurrent] = useState<RecruitingRecord>();
  const [selectedCandidate, setSelectedCandidate] = useState<CandidateOption | null>(null);
  const [stage, setStage] = useState<Stage>('reviewing');
  const [note, setNote] = useState('');
  const [selectedNeedId, setSelectedNeedId] = useState(needId ?? '');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState('');

  const location = useLocation();
  const dialog = useRef<HTMLElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  // Manage focus trap
  useEffect(() => {
    previouslyFocused.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const first = dialog.current?.querySelector<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    first?.focus();
    return () => {
      if (previouslyFocused.current?.isConnected) previouslyFocused.current.focus();
      previouslyFocused.current = null;
    };
  }, []);

  // Fetch record details if candidateId is provided
  useEffect(() => {
    let active = true;
    if (!candidateId) {
      setCurrent(undefined);
      setSelectedNeedId(needId ?? '');
      setStage('reviewing');
      setNote('');
      setLoading(false);
      return () => {
        active = false;
      };
    }

    setLoading(true);
    setError('');
    const query = new URLSearchParams({ candidate_id: candidateId });
    api.get<Schemas['RecruitingRecordPage']>(`/recruiting/records?${query.toString()}`)
      .then((page) => {
        if (!active) return;
        const own = page.items.find((record) => record.candidate_id === candidateId);
        setCurrent(own);
        setSelectedNeedId(own ? own.need_id ?? '' : needId ?? '');
        setStage(own?.stage ?? 'reviewing');
        setNote(own?.note ?? '');
        if (own) {
          setSelectedCandidate({
            id: own.candidate_id,
            displayName: own.candidate_display_name || 'Профиль кандидата',
            visibility: own.candidate_visibility,
          });
        }
      })
      .catch((reason: unknown) => {
        if (active) setError(errorMessage(reason));
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [candidateId, needId, location.key]);

  const effectiveCandidateId = candidateId || selectedCandidate?.id || '';

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError('');
    setSaved('');

    if (!effectiveCandidateId) {
      setError('Выберите кандидата из списка или найдите по имени.');
      return;
    }

    setSaving(true);
    try {
      let existing = current;
      if (!existing) {
        const lookup = new URLSearchParams({
          candidate_id: effectiveCandidateId,
          limit: '100',
        });
        const page = await api.get<Schemas['RecruitingRecordPage']>(
          `/recruiting/records?${lookup.toString()}`,
        );
        existing = page.items.find((record) => record.candidate_id === effectiveCandidateId);
        if (existing) {
          setCurrent(existing);
        }
      }

      const payload: Schemas['RecruitingRecordPutRequest'] = {
        expected_version: existing?.version ?? 0,
        need_id: selectedNeedId.trim() || null,
        stage,
        note: note.trim() || null,
      };

      const record = await api.put<RecruitingRecord>(
        `/recruiting/records/${encodeURIComponent(effectiveCandidateId)}`,
        payload as ApiPayload,
      );

      setCurrent(record);
      setStage(record.stage);
      setNote(record.note ?? '');
      setSaved('Запись сохранена для вашей компании.');
      onSaved?.(record);
    } catch (reason) {
      setError(errorMessage(reason));
    } finally {
      setSaving(false);
    }
  };

  const onDialogKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onCancel?.();
      return;
    }
    if (event.key !== 'Tab') return;
    const focusable = dialog.current?.querySelectorAll<HTMLElement>(
      'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    if (!focusable?.length) return;
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (!dialog.current?.contains(document.activeElement)) {
      event.preventDefault();
      (event.shiftKey ? last : first).focus();
    } else if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  };

  return (
    <div
      className="employer-modal-backdrop"
      role="presentation"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onCancel?.();
      }}
    >
      <section
        ref={dialog}
        className="card employer-record-editor recruiting-editor-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="record-editor-title"
        aria-describedby="record-editor-description"
        onKeyDown={onDialogKeyDown}
      >
        <div className="card-head">
          <div>
            <div className="eyebrow">ТОЛЬКО ВАША КОМПАНИЯ</div>
            <h2 id="record-editor-title">
              {candidateId ? 'Рабочая запись кандидата' : 'Новая запись кандидата'}
            </h2>
          </div>
          <button className="text-button" type="button" onClick={onCancel} aria-label="Закрыть">
            Закрыть ×
          </button>
        </div>

        <p id="record-editor-description" className="employer-muted">
          Заметка и этап видны только вашей компании. Они не отправляются кандидату и не влияют
          на публичный рейтинг.
        </p>

        {error && <div className="error-box" role="alert"><span>{error}</span></div>}
        {saved && <div className="notice notice-success">{saved}</div>}

        {loading ? (
          <div className="loading">
            <span className="spinner" aria-hidden="true" />
            Проверяем текущую версию записи…
          </div>
        ) : (
          <form className="form-stack" onSubmit={(event) => void submit(event)}>
            <div className="field">
              <span>Кандидат</span>
              <CandidateAutocomplete
                value={effectiveCandidateId}
                onChange={(cand) => setSelectedCandidate(cand)}
                existingRecords={existingRecords}
                selectedCandidate={selectedCandidate}
                readOnly={Boolean(candidateId)}
                required
                placeholder="Начните вводить имя или выберите из списка…"
              />
            </div>

            <div className="field">
              <span>Привязка к потребности</span>
              <NeedSelect
                value={selectedNeedId}
                onChange={(id) => setSelectedNeedId(id)}
                allOptionLabel="Без привязки к потребности"
                includeAllOption
              />
              <small>
                Связь с потребностью можно изменить; запись остаётся одной на кандидата в вашей
                компании.
              </small>
            </div>

            {current?.candidate_visibility === 'hidden' && (
              <div className="notice notice-danger">
                Кандидат скрыл публичный профиль. Сохранённая заметка вашей компании остаётся
                доступна; публичные сведения и контакты не загружаются.
              </div>
            )}

            <label className="field">
              <span>Этап воронки вашей компании</span>
              <select value={stage} onChange={(event) => setStage(event.target.value as Stage)}>
                {STAGES.map((option) => (
                  <option key={option.id} value={option.id}>
                    {option.label}
                  </option>
                ))}
              </select>
            </label>

            <label className="field">
              <span>
                Приватная заметка <small>до 2000 символов</small>
              </span>
              <textarea
                value={note}
                onChange={(event) => setNote(event.target.value)}
                rows={5}
                maxLength={2000}
                placeholder="Факты, впечатления после собеседования и следующие шаги команды"
              />
              <small>{note.length}/2000</small>
            </label>

            <div className="employer-editor-actions">
              <button type="button" className="button button-secondary" onClick={onCancel}>
                Отмена
              </button>
              <button
                className="button button-primary"
                disabled={saving || !effectiveCandidateId}
              >
                {saving
                  ? 'Сохраняем…'
                  : current
                  ? `Сохранить · версия ${current.version}`
                  : 'Создать запись'}
              </button>
            </div>
          </form>
        )}
      </section>
    </div>
  );
}
