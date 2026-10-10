import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, RequestError, type ApiPayload } from '../../api';
import { createPendingRequest } from '../extensions/task-helpers';
import { buildDraftPayload, candidateFormArtifact, parsePilotObservations, privateAnswerKeyArtifact, serializeJsonArtifact } from './methodist-forms';
import type { BankMonitor, DraftExport, DraftForm, DraftList, DraftPayload, MethodistDraft, PilotReport, ReviewMarks, ReviewOutcome } from './types';
import './methodist.css';

type Tab = 'drafts' | 'bank';
type PendingOperation = { action: string; payload: unknown; idempotencyKey: string };
const emptyForm: DraftForm = {
  source_title: '', source_reference: '', source_excerpt: '', competency_id: '', grade: 'junior',
  question: '', options: ['', '', '', ''], correct_option: '0', rubric: '',
};
const outcomes: { value: ReviewOutcome; label: string }[] = [
  { value: 'approved', label: 'Одобрить черновик' },
  { value: 'changes_requested', label: 'Вернуть на доработку' },
  { value: 'rejected', label: 'Отклонить' },
];
const competencies = [
  { value: 'python-basics', label: 'Основы Python' }, { value: 'http-api', label: 'HTTP и API' },
  { value: 'sql-transactions', label: 'SQL и транзакции' }, { value: 'reliability', label: 'Надёжность' },
];
const marksLabels: { key: keyof ReviewMarks; label: string }[] = [
  { key: 'relevance', label: 'Связь с компетенцией' }, { key: 'level', label: 'Соответствие уровню' },
  { key: 'clarity', label: 'Понятность формулировки' }, { key: 'correctness', label: 'Корректность ответа и критерия' },
];
const statusLabels: Record<MethodistDraft['status'], string> = {
  draft: 'Черновик', approved: 'Одобрен экспертом', changes_requested: 'Нужна доработка', rejected: 'Отклонён',
};
const emptyMarks: ReviewMarks = { relevance: false, level: false, clarity: false, correctness: false };
const draftPath = (id: string) => `/methodist/drafts/${encodeURIComponent(id)}`;
const formatPilotPercent = (value: number | null, samples: number) => value === null
  ? samples < 10 ? 'нужно не менее 10 наблюдений' : 'не объединяется между источниками'
  : `${Math.round(value * 100)}%`;
const asPayload = (payload: object) => payload as ApiPayload;
function downloadJsonFile(filename: string, value: unknown) {
  const url = URL.createObjectURL(new Blob([serializeJsonArtifact(value)], { type: 'application/json;charset=utf-8' }));
  const anchor = document.createElement('a');
  anchor.href = url; anchor.download = filename; anchor.hidden = true;
  document.body.appendChild(anchor); anchor.click(); anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function MethodistWorkspace() {
  const [tab, setTab] = useState<Tab>('drafts');
  const [drafts, setDrafts] = useState<MethodistDraft[]>([]);
  const [monitor, setMonitor] = useState<BankMonitor>();
  const [selectedId, setSelectedId] = useState('');
  const [form, setForm] = useState<DraftForm>(emptyForm);
  const [baseVersion, setBaseVersion] = useState<number>();
  const [editing, setEditing] = useState(false);
  const [latestConflict, setLatestConflict] = useState<MethodistDraft>();
  const [needsRefresh, setNeedsRefresh] = useState(false);
  const [reviewOutcome, setReviewOutcome] = useState<ReviewOutcome>('approved');
  const [reviewMarks, setReviewMarks] = useState<ReviewMarks>(emptyMarks);
  const [reviewComment, setReviewComment] = useState('');
  const [pilotInput, setPilotInput] = useState('source,correct\nsynthetic,true');
  const [pilotReport, setPilotReport] = useState<PilotReport>();
  const [draftExport, setDraftExport] = useState<DraftExport>();
  const [loading, setLoading] = useState(true);
  const [monitorLoading, setMonitorLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [closed, setClosed] = useState(false);
  const [pending, setPending] = useState<PendingOperation>();
  const requestGeneration = useRef(0);
  const accessGeneration = useRef(0);

  const clearProtectedState = useCallback(() => {
    accessGeneration.current += 1;
    setDrafts([]); setMonitor(undefined); setSelectedId(''); setForm(emptyForm); setBaseVersion(undefined);
    setLatestConflict(undefined); setPilotReport(undefined); setDraftExport(undefined); setPending(undefined);
    setNeedsRefresh(false); setEditing(false); setClosed(true); setSaving(false);
  }, []);

  const refreshDrafts = useCallback(async () => {
    const generation = ++requestGeneration.current;
    const access = accessGeneration.current;
    try {
      const result = await api.get<DraftList>('/methodist/drafts');
      if (generation !== requestGeneration.current || access !== accessGeneration.current) return undefined;
      setDrafts(result.items);
      setClosed(false);
      return result.items;
    } catch (reason) {
      if (generation !== requestGeneration.current || access !== accessGeneration.current) return undefined;
      const status = reason instanceof RequestError ? reason.status : 0;
      if ([401, 403, 404].includes(status)) { clearProtectedState(); setError('Доступ к мастерской закрыт. Предыдущие данные скрыты.'); }
      else setError(errorMessage(reason));
      return undefined;
    } finally { if (generation === requestGeneration.current) setLoading(false); }
  }, [clearProtectedState]);

  const refreshMonitor = useCallback(async () => {
    setMonitorLoading(true); setError('');
    const access = accessGeneration.current;
    try {
      const result = await api.get<BankMonitor>('/methodist/bank/monitor');
      if (access !== accessGeneration.current) return;
      setMonitor(result); setClosed(false);
    } catch (reason) {
      if (access !== accessGeneration.current) return;
      const status = reason instanceof RequestError ? reason.status : 0;
      if ([401, 403, 404].includes(status)) { clearProtectedState(); setError('Доступ к мониторингу закрыт. Предыдущие данные мастерской скрыты.'); }
      else setError(errorMessage(reason));
    } finally { if (access === accessGeneration.current) setMonitorLoading(false); }
  }, [clearProtectedState]);

  useEffect(() => {
    void refreshDrafts();
    return () => { requestGeneration.current += 1; accessGeneration.current += 1; };
  }, [refreshDrafts]);

  const selected = drafts.find((draft) => draft.id === selectedId);
  const resetDraftPanels = () => { setPilotReport(undefined); setDraftExport(undefined); setLatestConflict(undefined); };
  const startNew = () => {
    setSelectedId(''); setBaseVersion(undefined); setForm(emptyForm); setEditing(true); setPending(undefined);
    setReviewOutcome('approved'); setReviewMarks(emptyMarks); setReviewComment('');
    setNeedsRefresh(false); setError(''); setNotice(''); resetDraftPanels();
  };
  const startEdit = (draft: MethodistDraft) => {
    setSelectedId(draft.id); setBaseVersion(draft.version);
    setForm({ ...draft, correct_option: String(draft.correct_option), options: [...draft.options] as DraftForm['options'] });
    setReviewOutcome('approved'); setReviewMarks(emptyMarks); setReviewComment('');
    setEditing(true); setPending(undefined); setNeedsRefresh(false); setError(''); setNotice(''); resetDraftPanels();
  };
  const changeForm = (patch: Partial<DraftForm>) => {
    setForm((current) => ({ ...current, ...patch })); setPending(undefined); setError(''); setNotice('');
  };
  const conflictFrom = async (reason: unknown, preserve: boolean) => {
    const status = reason instanceof RequestError ? reason.status : 0;
    if ([401, 403, 404].includes(status)) { clearProtectedState(); setError('Доступ к мастерской закрыт. Предыдущие данные скрыты.'); return; }
    if (status !== 409) { setError(errorMessage(reason)); return; }
    setPending(undefined); setLatestConflict(undefined); setNeedsRefresh(true);
    const items = await refreshDrafts();
    const current = items?.find((item) => item.id === selectedId);
    if (current) setLatestConflict(current);
    setError(preserve
      ? 'Черновик изменился на сервере. Ваша форма сохранена; сравните версии и выберите действие ниже.'
      : 'Версия изменилась. Текущие данные обновлены; повторите действие после проверки.');
  };

  const runMutation = async <T,>(action: string, body: object, send: (payload: object, key: string) => Promise<T>, onSuccess: (result: T) => void) => {
    if (saving || closed) return;
    const request = createPendingRequest(body, () => crypto.randomUUID(), pending?.action === action ? pending : undefined);
    setPending({ action, ...request }); setSaving(true); setError(''); setNotice('');
    const access = accessGeneration.current;
    try {
      const result = await send(request.payload as object, request.idempotencyKey);
      if (access !== accessGeneration.current) return;
      setPending(undefined); onSuccess(result);
    } catch (reason) {
      if (access !== accessGeneration.current) return;
      await conflictFrom(reason, action === 'save-draft');
    } finally { if (access === accessGeneration.current) setSaving(false); }
  };

  const saveDraft = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (latestConflict || closed) return;
    let fields: DraftPayload;
    try { fields = buildDraftPayload(form); }
    catch (reason) { setError(errorMessage(reason)); return; }
    if (selectedId && baseVersion === undefined) return;
    const payload = selectedId ? { expected_version: baseVersion!, ...fields } : fields;
    void runMutation(selectedId ? 'save-draft' : 'create-draft', payload,
      (body, key) => selectedId
        ? api.patch<MethodistDraft>(draftPath(selectedId), asPayload(body), key)
        : api.post<MethodistDraft>('/methodist/drafts', asPayload(body), key),
      (created) => {
        setDrafts((items) => [created, ...items.filter((item) => item.id !== created.id)]);
        setSelectedId(created.id); setBaseVersion(created.version); setForm({ ...created, correct_option: String(created.correct_option), options: [...created.options] as DraftForm['options'] });
        setEditing(false); setNotice('Черновик сохранён. Он не опубликован и не меняет грейды.'); resetDraftPanels();
      });
  };

  const reviewDraft = () => {
    if (!selected || !baseVersion) return;
    const payload = { expected_version: baseVersion, outcome: reviewOutcome, marks: reviewMarks, comment: reviewComment.trim() };
    void runMutation('review', payload, (body, key) => api.post<MethodistDraft>(`${draftPath(selected.id)}/review`, asPayload(body), key), (updated) => {
      setDrafts((items) => items.map((item) => item.id === updated.id ? updated : item)); setBaseVersion(updated.version);
      setNotice('Решение эксперта сохранено для этой версии задания.');
    });
  };

  const addPilot = () => {
    if (!selected || !baseVersion) return;
    let observations;
    try { observations = parsePilotObservations(pilotInput); }
    catch (reason) { setError(errorMessage(reason)); return; }
    const payload = { expected_version: baseVersion, observations };
    void runMutation('pilot', payload, (body, key) => api.post<{ draft_id: string; version: number; accepted_count: number }>(`${draftPath(selected.id)}/pilot`, asPayload(body), key), (result) => {
      setNotice(`Добавлено наблюдений для версии ${result.version}: ${result.accepted_count}. Эти данные экспериментальные и не подтверждают человеческий грейд.`);
      setPilotInput('source,correct\nsynthetic,true');
    });
  };

  const loadReport = async () => {
    if (!selected) return;
    const access = accessGeneration.current;
    try {
      setPilotReport(await api.get<PilotReport>(`${draftPath(selected.id)}/pilot-report`));
      if (access !== accessGeneration.current) setPilotReport(undefined);
      else setError('');
    } catch (reason) {
      if ([401, 403, 404].includes(reason instanceof RequestError ? reason.status : 0)) { clearProtectedState(); setError('Доступ к отчёту пилота закрыт. Предыдущие данные мастерской скрыты.'); }
      else setError(errorMessage(reason));
    }
  };
  const loadExport = async () => {
    if (!selected) return;
    const access = accessGeneration.current;
    try {
      setDraftExport(await api.get<DraftExport>(`${draftPath(selected.id)}/export`));
      if (access !== accessGeneration.current) setDraftExport(undefined);
      else setError('');
    } catch (reason) {
      if ([401, 403, 404].includes(reason instanceof RequestError ? reason.status : 0)) { clearProtectedState(); setError('Доступ к выгрузке закрыт. Предыдущие данные мастерской скрыты.'); }
      else setError(errorMessage(reason));
    }
  };
  const adoptServerVersion = () => {
    if (!latestConflict) return;
    startEdit(latestConflict);
    setNotice(`Загружена версия ${latestConflict.version} с сервера.`);
  };
  const continueWithLocalDraft = () => {
    if (!latestConflict) return;
    setBaseVersion(latestConflict.version); setLatestConflict(undefined); setNeedsRefresh(false); setPending(undefined);
    setError(''); setNotice(`Ваш текст сохранён в форме. Повторное сохранение создаст версию ${latestConflict.version + 1}.`);
  };
  const reloadConflictVersion = async () => {
    const items = await refreshDrafts();
    const latest = items?.find((item) => item.id === selectedId);
    if (latest) setLatestConflict(latest);
  };
  const tabChange = (next: Tab) => {
    setTab(next);
    if (next === 'bank' && !monitor) void refreshMonitor();
  };
  const reviewCanBeApproved = reviewOutcome !== 'approved' || Object.values(reviewMarks).every(Boolean);

  return <section className="methodist-workspace" aria-label="Мастерская методиста">
    <header className="methodist-header">
      <div><p className="methodist-eyebrow">ЗАКРЫТОЕ РАБОЧЕЕ МЕСТО</p><h1>Банк заданий</h1><p>Подготовка, экспертная проверка и пилотирование. Одобрение черновика само по себе не публикует задание и не меняет грейд.</p></div>
      <span className="methodist-access-tag">Доступ методиста</span>
    </header>
    <nav className="methodist-tabs" aria-label="Разделы мастерской">
      <button type="button" aria-current={tab === 'drafts' ? 'page' : undefined} onClick={() => tabChange('drafts')}>Черновики <span>{drafts.length}</span></button>
      <button type="button" aria-current={tab === 'bank' ? 'page' : undefined} onClick={() => tabChange('bank')}>Монитор банка</button>
    </nav>
    {error && <div className="methodist-alert" role="alert">{error}<button type="button" className="text-button" onClick={() => { setError(''); if (tab === 'bank') void refreshMonitor(); else void refreshDrafts(); }}>Обновить</button></div>}
    {notice && <p className="methodist-notice" role="status">{notice}</p>}
    {closed && <p className="methodist-closed" role="status">Доступ закрыт сервером. Все ранее загруженные сведения скрыты.</p>}

    {tab === 'drafts' && !closed && <div className="methodist-drafts-layout">
      <aside className="methodist-list card" aria-label="Список заданий">
        <div className="methodist-list-head"><div><p className="methodist-eyebrow">РАБОЧАЯ ОЧЕРЕДЬ</p><h2>Задания</h2></div><button className="button button-primary button-small" type="button" onClick={startNew}>Новое задание</button></div>
        {loading && <p role="status">Загружаем черновики…</p>}
        {!loading && drafts.length === 0 && <p className="methodist-empty">Пока нет заданий. Создайте черновик с источником и критерием правильного ответа.</p>}
        <ul className="methodist-draft-list">{drafts.map((draft) => <li key={draft.id}><button type="button" className={`methodist-draft-row${draft.id === selectedId ? ' is-selected' : ''}`} onClick={() => { setSelectedId(draft.id); setEditing(false); setNeedsRefresh(false); setForm({ ...draft, correct_option: String(draft.correct_option), options: [...draft.options] as DraftForm['options'] }); setBaseVersion(draft.version); setReviewOutcome('approved'); setReviewMarks(emptyMarks); setReviewComment(''); setError(''); setNotice(''); resetDraftPanels(); }}><strong>{draft.question}</strong><span>{draft.competency_id} · {draft.grade} · v{draft.version}</span><small>{statusLabels[draft.status]}</small></button></li>)}</ul>
      </aside>
      <section className="methodist-detail" aria-label="Редактор и проверка задания">
        {(editing || selected) ? <>
          <section className="card methodist-editor">
            <div className="methodist-section-head"><div><p className="methodist-eyebrow">{selectedId ? `ВЕРСИЯ ${baseVersion ?? '—'}` : 'НОВЫЙ ЧЕРНОВИК'}</p><h2>{editing ? 'Содержание задания' : 'Карточка задания'}</h2></div>{selected && !editing && <button type="button" className="button button-secondary button-small" onClick={() => startEdit(selected)}>Изменить черновик</button>}</div>
            {editing ? <form onSubmit={saveDraft} className="methodist-form">
              <div className="methodist-form-grid"><label className="field"><span>Название источника</span><input value={form.source_title} onChange={(event) => changeForm({ source_title: event.target.value })} maxLength={200} required /></label><label className="field"><span>Ссылка или библиографическая ссылка</span><input type="text" value={form.source_reference} onChange={(event) => changeForm({ source_reference: event.target.value })} placeholder="https://… или название документа" maxLength={2048} required /><small>Это текстовая ссылка. Система не загружает материал автоматически.</small></label></div>
              <label className="field"><span>Фрагмент источника</span><textarea rows={3} value={form.source_excerpt} onChange={(event) => changeForm({ source_excerpt: event.target.value })} maxLength={4000} required /></label>
              <div className="methodist-form-grid"><label className="field"><span>Компетенция</span><select value={form.competency_id} onChange={(event) => changeForm({ competency_id: event.target.value })} required><option value="">Выберите компетенцию</option>{competencies.map((competency) => <option value={competency.value} key={competency.value}>{competency.label}</option>)}</select></label><label className="field"><span>Уровень задания</span><select value={form.grade} onChange={(event) => changeForm({ grade: event.target.value as DraftForm['grade'] })}><option value="junior">Junior</option><option value="middle">Middle</option></select></label></div>
              <label className="field"><span>Вопрос</span><textarea rows={3} value={form.question} onChange={(event) => changeForm({ question: event.target.value })} maxLength={4000} required /></label>
              <fieldset className="methodist-options"><legend>Четыре варианта ответа</legend>{form.options.map((option, index) => <div className="methodist-option" key={index}><label className="field methodist-option-field"><span>Вариант {index + 1}</span><input value={option} onChange={(event) => changeForm({ options: form.options.map((value, item) => item === index ? event.target.value : value) as DraftForm['options'] })} maxLength={1000} required /></label><label className="methodist-correct"><input type="radio" name="correct-option" value={index} checked={form.correct_option === String(index)} onChange={() => changeForm({ correct_option: String(index) })} /><span>Правильный</span></label></div>)}</fieldset>
              <label className="field"><span>Критерий проверки</span><textarea rows={3} value={form.rubric} onChange={(event) => changeForm({ rubric: event.target.value })} maxLength={3000} required /></label>
              <div className="methodist-actions"><button className="button button-primary button-small" disabled={saving || Boolean(latestConflict) || needsRefresh || closed} type="submit">{saving ? 'Сохраняем…' : pending?.action.includes('draft') ? 'Повторить сохранение' : 'Сохранить черновик'}</button><button className="button button-secondary button-small" disabled={saving} type="button" onClick={() => { setEditing(false); setPending(undefined); setLatestConflict(undefined); setNeedsRefresh(false); setError(''); }}>{selected ? 'Отменить изменения' : 'Закрыть'}</button></div>
            </form> : selected && <div className="methodist-readonly"><p className="methodist-question">{selected.question}</p><p><strong>Источник:</strong> {selected.source_title} <span className="methodist-source-ref">{selected.source_reference}</span></p><blockquote>{selected.source_excerpt}</blockquote><p><strong>Компетенция:</strong> {selected.competency_id} · {selected.grade}</p><ol>{selected.options.map((option, index) => <li key={index}>{option}</li>)}</ol><p><strong>Критерий:</strong> {selected.rubric}</p><p><strong>Статус:</strong> {statusLabels[selected.status]} · версия {selected.version}</p></div>}
            {needsRefresh && editing && <div className="methodist-conflict" role="group" aria-label="Разрешение конфликта версии"><strong>{latestConflict ? `На сервере уже версия ${latestConflict.version}.` : 'Версию на сервере пока не удалось загрузить.'}</strong><p>Форма с вашим черновиком сохранена отдельно. Сначала сравните версии. Повторное сохранение заблокировано до выбора.</p><div className="methodist-actions">{latestConflict ? <><button type="button" className="button button-secondary button-small" onClick={adoptServerVersion}>Загрузить версию сервера</button><button type="button" className="button button-primary button-small" onClick={continueWithLocalDraft}>Продолжить с моим черновиком</button></> : <button type="button" className="button button-secondary button-small" onClick={() => void reloadConflictVersion()}>Перезагрузить версию</button>}</div></div>}
          </section>
          {needsRefresh && !editing && <div className="methodist-conflict" role="group" aria-label="Обновление версии"><strong>{latestConflict ? `На сервере уже версия ${latestConflict.version}.` : 'Версию на сервере пока не удалось загрузить.'}</strong><p>Решение или пилотирование остановлено. Текущая форма, отзыв и пакет наблюдений сохранены в этом окне.</p>{latestConflict ? <button type="button" className="button button-secondary button-small" onClick={adoptServerVersion}>Загрузить версию сервера</button> : <button type="button" className="button button-secondary button-small" onClick={() => void reloadConflictVersion()}>Перезагрузить версию</button>}</div>}
          {!editing && selected && <>
            <section className="card methodist-review">
              <div className="methodist-section-head"><div><p className="methodist-eyebrow">ЭКСПЕРТНОЕ РЕШЕНИЕ</p><h2>Проверка версии {selected.version}</h2></div></div>
              {selected.review && <p className="methodist-review-status">Последнее решение: {statusLabels[selected.review.outcome]} · {new Date(selected.review.reviewed_at).toLocaleDateString('ru-RU')}</p>}
              {selected.status !== 'draft' && <p className="methodist-review-status">Решение для этой версии уже зафиксировано. Чтобы отправить её на повторную проверку, создайте новую версию через редактирование.</p>}
              <div className="methodist-mark-grid">{marksLabels.map(({ key, label }) => <label key={key}><input type="checkbox" checked={reviewMarks[key]} onChange={(event) => setReviewMarks((marks) => ({ ...marks, [key]: event.target.checked }))} /><span>{label}</span></label>)}</div>
              <label className="field"><span>Решение</span><select value={reviewOutcome} onChange={(event) => setReviewOutcome(event.target.value as ReviewOutcome)}>{outcomes.map((outcome) => <option value={outcome.value} key={outcome.value}>{outcome.label}</option>)}</select></label>
              <label className="field"><span>Комментарий эксперта</span><textarea value={reviewComment} onChange={(event) => setReviewComment(event.target.value)} maxLength={1000} rows={2} /></label>
              {!reviewCanBeApproved && <p className="methodist-caution">Для одобрения подтвердите все четыре критерия проверки.</p>}
              <button type="button" className="button button-secondary button-small" disabled={saving || Boolean(latestConflict) || !reviewCanBeApproved || selected.status !== 'draft'} onClick={reviewDraft}>Сохранить решение</button>
            </section>
            <section className="card methodist-pilot">
              <div className="methodist-section-head"><div><p className="methodist-eyebrow">ЭКСПЕРИМЕНТАЛЬНЫЕ НАБЛЮДЕНИЯ</p><h2>Пилот версии {selected.version}</h2></div></div>
              <p>Загружайте только источник и правильность ответа. Не добавляйте имена, контакты, ответы с идентификаторами или другие данные участников.</p>
              <label className="field"><span>Пакет CSV · source,correct</span><textarea className="methodist-csv" value={pilotInput} onChange={(event) => { setPilotInput(event.target.value); setPending(undefined); }} rows={4} spellCheck={false} /></label>
              {selected.status !== 'approved' && <p className="methodist-caution">Сначала одобрите текущую версию экспертом, чтобы добавить пилотные наблюдения и подготовить выгрузку.</p>}
              <div className="methodist-actions"><button type="button" className="button button-primary button-small" disabled={saving || Boolean(latestConflict) || selected.status !== 'approved'} onClick={addPilot}>Добавить наблюдения</button><button type="button" className="button button-secondary button-small" disabled={selected.status !== 'approved'} onClick={() => void loadReport()}>Загрузить отчёт пилота</button></div>
              {pilotReport && <div className="methodist-report"><h3>Отчёт · версия {pilotReport.version}</h3><p>{pilotReport.source_title}: {pilotReport.correct_count} правильных из {pilotReport.sample_count} · точность {formatPilotPercent(pilotReport.accuracy, pilotReport.sample_count)}</p><p>Учебные данные: {pilotReport.source_counts.synthetic}; записи пилота: {pilotReport.source_counts.pilot_record}.</p><p>Разбивка точности: учебные данные — {formatPilotPercent(pilotReport.accuracy_by_source.synthetic, pilotReport.source_counts.synthetic)}; пилот — {formatPilotPercent(pilotReport.accuracy_by_source.pilot_record, pilotReport.source_counts.pilot_record)}.</p><p className="methodist-caution">Это экспериментальные наблюдения. Они не подтверждают валидность задания и грейд специалиста. Для статистики требуется минимум {pilotReport.minimum_sample_for_statistics} наблюдений; автоматического вывода или публикации нет.</p></div>}
            </section>
            <section className="card methodist-export">
              <div className="methodist-section-head"><div><p className="methodist-eyebrow">РАЗДЕЛЬНЫЕ АРТЕФАКТЫ</p><h2>Подготовить выгрузку</h2></div><button type="button" className="button button-secondary button-small" disabled={selected.status !== 'approved'} onClick={() => void loadExport()}>Загрузить выгрузку</button></div>
              {selected.status !== 'approved' && <p className="methodist-caution">Выгрузка доступна после экспертного одобрения текущей версии.</p>}
              <p>Форма для слепой проверки и закрытый ключ для методиста представлены в разных блоках. Живая база и грейды не меняются.</p>
              {draftExport && <><div className="methodist-candidate-export"><h3>Форма кандидата · без ключа, выдержки и критерия</h3><p>{draftExport.candidate_form.question}</p><ol>{draftExport.candidate_form.options.map((option, index) => <li key={index}>{option}</li>)}</ol><p>Компетенция: {draftExport.candidate_form.competency_id} · {draftExport.candidate_form.grade}</p><p>Источник: {draftExport.candidate_form.source.title} · {draftExport.candidate_form.source.reference}</p><button type="button" className="button button-secondary button-small" onClick={() => downloadJsonFile(`draft-v${draftExport.version}-candidate-form.json`, candidateFormArtifact(draftExport))}>Скачать форму кандидата · JSON</button></div><details className="methodist-private-export"><summary>Закрытый ключ, выдержка и критерий · только для методиста</summary><p><strong>Правильный вариант:</strong> {draftExport.private_answer_key.correct_option + 1}</p><p><strong>Источник:</strong> {draftExport.candidate_form.source.title} · {draftExport.candidate_form.source.reference}</p><blockquote>{draftExport.private_answer_key.source_excerpt}</blockquote><p><strong>Критерий проверки:</strong> {draftExport.private_answer_key.rubric}</p><button type="button" className="button button-secondary button-small" onClick={() => downloadJsonFile(`draft-v${draftExport.version}-private-methodist-key.json`, privateAnswerKeyArtifact(draftExport))}>Скачать закрытый ключ · JSON</button></details><p className="methodist-caution">Выгрузка относится к черновику v{draftExport.version}. Задание не включено в рабочий банк.</p></>}
            </section>
          </>}
        </> : <div className="card methodist-empty-detail"><h2>Выберите задание</h2><p>Или создайте черновик, чтобы привязать вопрос к источнику и критерию ответа.</p></div>}
      </section>
    </div>}

    {tab === 'bank' && !closed && <section className="methodist-monitor">
      <div className="methodist-monitor-head card"><div><p className="methodist-eyebrow">СВОДНЫЕ ДАННЫЕ · БЕЗ ЛИЧНОСТЕЙ</p><h2>Состояние активного банка</h2><p>Сигналы указывают на материал для экспертной проверки; они не доказывают утечку или помощь кандидату.</p></div><button type="button" className="button button-secondary button-small" onClick={() => void refreshMonitor()} disabled={monitorLoading}>{monitorLoading ? 'Обновляем…' : 'Обновить'}</button></div>
      {monitorLoading && !monitor && <p role="status">Загружаем агрегаты банка…</p>}
      {monitor && <>
        <div className="methodist-stats"><article className="stat-card"><small>Версия активного банка</small><strong>{monitor.bank_version}</strong></article><article className="stat-card"><small>Семейств заданий</small><strong>{monitor.families_total}</strong></article><article className="stat-card"><small>Разрезов покрытия</small><strong>{monitor.coverage.length}</strong></article></div>
        <section className="card"><div className="methodist-section-head"><div><p className="methodist-eyebrow">ПОКРЫТИЕ</p><h2>Компетенции и экспозиция</h2></div></div>{monitor.coverage.length ? <div className="methodist-table-wrap"><table><thead><tr><th>Уровень</th><th>Компетенция</th><th>Семейств в банке</th><th>С экспозицией</th><th>Всего показов</th></tr></thead><tbody>{monitor.coverage.map((row, index) => <tr key={`${row.grade}-${row.competency_id}-${index}`}><td>{row.grade}</td><td>{row.competency_id}</td><td>{row.families_total}</td><td>{row.families_exposed}</td><td>{row.exposure_count}</td></tr>)}</tbody></table></div> : <p>Нет строк покрытия для этой версии банка.</p>}</section>
        <section className="card"><div className="methodist-section-head"><div><p className="methodist-eyebrow">ПОВТОРНОЕ ИСПОЛЬЗОВАНИЕ</p><h2>Экспозиция семейств и вариантов</h2></div></div>{monitor.repeated_use.length ? <div className="methodist-table-wrap"><table><thead><tr><th>Семейство</th><th>Компетенция</th><th>Показы семейства и вариантов</th><th>Повторяется</th><th>Сигнал методисту</th></tr></thead><tbody>{monitor.repeated_use.map((row, index) => <tr key={`${row.grade}-${row.family_id}-${index}`}><td>{row.family_id}</td><td>{row.competency_id} · {row.grade}</td><td>{row.exposure_count}<ul className="methodist-variant-list">{row.variant_exposures.map((variant) => <li key={variant.variant_id}>{variant.variant_id}: {variant.exposure_count}</li>)}</ul></td><td>{row.repeated_use ? 'Да' : 'Нет'}</td><td>{row.review_required ? <span className="methodist-review-flag">Проверить</span> : 'Нет сигнала'}{row.review_signal && <small className="methodist-review-signal">{row.review_signal}</small>}</td></tr>)}</tbody></table></div> : <p>Повторное использование не зафиксировано.</p>}</section>
        <section className="card"><div className="methodist-section-head"><div><p className="methodist-eyebrow">ИЗМЕНЕНИЕ РЕЗУЛЬТАТОВ</p><h2>Окна сложности</h2></div></div>{monitor.difficulty_windows.length ? <div className="methodist-difficulty-list">{monitor.difficulty_windows.map((row, index) => <article key={`${row.window}-${row.grade}-${index}`}><div><strong>{row.window} · {row.grade}</strong><span>{row.sample_count} попыток в агрегате</span></div><p>Средний общий результат: {row.mean_total_score === null ? 'Недостаточно данных' : `${row.mean_total_score.toFixed(2)} балла`}</p>{Object.keys(row.mean_block_scores).length > 0 && <p>Средний результат по блокам: {Object.entries(row.mean_block_scores).map(([block, score]) => `${block}: ${score === null ? '—' : score.toFixed(2)}`).join(' · ')}</p>}<p className={row.review_required ? 'methodist-review-signal' : ''}>{row.review_required ? 'Требуется экспертная проверка' : 'Сигнал для проверки не сформирован'}{row.review_signal ? ` · ${row.review_signal}` : ''}</p><small>{row.interpretation}</small></article>)}</div> : <p>Для окон недостаточно агрегированных наблюдений.</p>}</section>
        <p className="methodist-caution">В мониторинге нет ключей ответов, личных данных и идентификаторов попыток. Изменение результатов просит экспертной проверки; оно не является доказательством утечки или нечестного поведения.</p>
      </>}
    </section>}
  </section>;
}
