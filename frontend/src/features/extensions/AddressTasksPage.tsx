import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { api, errorMessage, RequestError, type ApiPayload, type Candidate, type Need, type Schemas } from '../../api';
import { canReviewShortTask, canRevokeTaskSharing, canShowTaskResults, createPendingRequest, flattenMatchCandidates, taskValidationErrors } from './task-helpers';
import { SolutionEditorModal } from './SolutionEditorModal';
import './extensions.css';

type Role = 'candidate' | 'employer';
type RubricCriterion = Schemas['ShortTaskRubricCriterion'];
type Task = Schemas['ShortTask'];
type NeedPage = { items: Need[]; page?: { next_cursor: string | null } };
type CandidateIdentity = Pick<Candidate, 'id' | 'display_name' | 'headline' | 'verified_grade' | 'declared_grade' | 'demo'>;
type MatchCandidate = Schemas['MatchCandidate'];
type TaskCreateBody = Schemas['ShortTaskCreateRequest'] & { expected_answer: string | null };

const initialRubric = (): RubricCriterion[] => [{ id: 'reasoning', label: '', max_points: 5 }];
const defaultDeadline = () => {
  const date = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);
  date.setMinutes(date.getMinutes() - date.getTimezoneOffset());
  return date.toISOString().slice(0, 16);
};
const taskStatus: Record<Task['status'], string> = { offered: 'Ожидает ответа', accepted: 'Принята', declined: 'Отклонена', submitted: 'Решение отправлено', reviewed: 'Проверена работодателем', withdrawn: 'Отозвана', expired: 'Срок истёк' };
const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const matchStateLabel: Record<MatchCandidate['state'], string> = { confirmed: 'Подтверждённое соответствие', needs_clarification: 'Нужно обсудить', needs_confirmation: 'Нужно подтвердить' };

export function AddressTasksPage({ role }: { role: Role }) {
  const [searchParams] = useSearchParams();
  const [tasks, setTasks] = useState<Task[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [needs, setNeeds] = useState<Need[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selectedId, setSelectedId] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [candidateId, setCandidateId] = useState(searchParams.get('candidate_id') ?? '');
  const [candidateLookupInput, setCandidateLookupInput] = useState(searchParams.get('candidate_id') ?? '');
  const [candidate, setCandidate] = useState<CandidateIdentity | null>(null);
  const [candidateLookupError, setCandidateLookupError] = useState('');
  const [lookingUpCandidate, setLookingUpCandidate] = useState(false);
  const [matchCandidates, setMatchCandidates] = useState<MatchCandidate[]>([]);
  const [matchesLoading, setMatchesLoading] = useState(false);
  const [matchesError, setMatchesError] = useState('');
  const [matchesNextCursor, setMatchesNextCursor] = useState<string | null>(null);
  const [loadingMoreMatches, setLoadingMoreMatches] = useState(false);
  const [needId, setNeedId] = useState(searchParams.get('need_id') ?? '');
  const [title, setTitle] = useState('');
  const [purpose, setPurpose] = useState('');
  const [instructions, setInstructions] = useState('');
  const [estimatedMinutes, setEstimatedMinutes] = useState('30');
  const [dueAt, setDueAt] = useState(defaultDeadline);
  const [evaluationMode, setEvaluationMode] = useState<'rubric' | 'exact'>('rubric');
  const [expectedAnswer, setExpectedAnswer] = useState('');
  const [rubric, setRubric] = useState<RubricCriterion[]>(initialRubric);
  const [solution, setSolution] = useState('');
  const [confirmReadyToSubmit, setConfirmReadyToSubmit] = useState(false);
  const [reviewScores, setReviewScores] = useState<Record<string, string>>({});
  const [feedback, setFeedback] = useState('');
  const [candidateFilter, setCandidateFilter] = useState('');
  const [editorOpen, setEditorOpen] = useState(false);
  const prefilledCandidateId = useRef(searchParams.get('candidate_id') ?? '');
  const matchRequestGeneration = useRef(0);
  const candidateRequestGeneration = useRef(0);
  const previousNeedId = useRef('');

  const activeNeeds = useMemo(() => needs.filter((need) => need.status === 'active' && Boolean(need.competencies_confirmed_at)), [needs]);
  const selectedNeed = activeNeeds.find((need) => need.id === needId);
  const selectedTask = tasks.find((task) => task.id === selectedId) ?? tasks[0];

  const requestMatchCandidates = useCallback(async (need: Need, cursor: string | null = null, append = false) => {
    const generation = ++matchRequestGeneration.current;
    if (append) setLoadingMoreMatches(true); else { setMatchesLoading(true); setMatchCandidates([]); }
    setMatchesError('');
    try {
      const result = await api.post<Schemas['MatchResults']>('/matches/search', {
        need_id: need.id, limit: 20, cursor, strict_confirmed_only: false,
      });
      if (generation !== matchRequestGeneration.current) return;
      const candidates = flattenMatchCandidates(result.display_order, result.groups);
      setMatchCandidates((current) => append ? [...current, ...candidates.filter((item) => !current.some((existing) => existing.candidate.id === item.candidate.id))] : candidates);
      setMatchesNextCursor(result.next_cursor);
    } catch (reason) {
      if (generation === matchRequestGeneration.current) setMatchesError(errorMessage(reason));
    } finally {
      if (generation === matchRequestGeneration.current) { setMatchesLoading(false); setLoadingMoreMatches(false); }
    }
  }, []);

  const loadTasks = useCallback(async (cursor?: string, append = false) => {
    if (append) setLoadingMore(true); else setLoading(true);
    try {
      const result = await api.get<Schemas['ShortTaskPage']>(`/short-tasks?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
      setTasks((current) => append ? [...current, ...result.items.filter((item) => !current.some((existing) => existing.id === item.id))] : result.items);
      setNextCursor(result.next_cursor);
      setSelectedId((current) => current && (append ? true : result.items.some((item) => item.id === current)) ? current : result.items[0]?.id ?? '');
      setError('');
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setLoading(false); setLoadingMore(false); }
  }, []);

  useEffect(() => { void loadTasks(); }, [loadTasks, role]);
  useEffect(() => {
    if (role !== 'employer') return;
    let active = true;
    api.get<NeedPage>('/needs?limit=100').then((result) => { if (active) { setNeeds(result.items); setNeedId((current) => result.items.some((need) => need.id === current && need.status === 'active' && need.competencies_confirmed_at) ? current : result.items.find((need) => need.status === 'active' && need.competencies_confirmed_at)?.id || ''); } }).catch((reason) => { if (active) setError(errorMessage(reason)); });
    return () => { active = false; };
  }, [role]);
  useEffect(() => {
    if (role !== 'employer' || !selectedNeed) {
      matchRequestGeneration.current += 1; setMatchCandidates([]); setMatchesNextCursor(null); setMatchesLoading(false); setLoadingMoreMatches(false);
      return;
    }
    void requestMatchCandidates(selectedNeed);
    return () => { matchRequestGeneration.current += 1; };
  }, [role, selectedNeed?.id, selectedNeed?.version, requestMatchCandidates]);
  useEffect(() => {
    const currentNeedId = selectedNeed?.id ?? '';
    if (previousNeedId.current && currentNeedId && previousNeedId.current !== currentNeedId) {
      candidateRequestGeneration.current += 1;
      setCandidateId(''); setCandidateLookupInput(''); setCandidate(null); setCandidateLookupError(''); setLookingUpCandidate(false);
    }
    if (currentNeedId) previousNeedId.current = currentNeedId;
  }, [selectedNeed?.id]);
  useEffect(() => {
    setSolution(''); setConfirmReadyToSubmit(false); setReviewScores({}); setFeedback('');
  }, [selectedId]);

  const verifyCandidate = useCallback(async (candidateInput: string) => {
    const value = candidateInput.trim().replace(/.*\/candidates\//, '').replace(/\/$/, '');
    const generation = ++candidateRequestGeneration.current;
    setCandidate(null); setCandidateLookupError(''); setCandidateId('');
    if (!uuidPattern.test(value)) { setCandidateLookupError('Выберите кандидата из списка или проверьте ссылку на публичный профиль.'); setLookingUpCandidate(false); return; }
    setCandidateId(value); setCandidateLookupInput(value); setLookingUpCandidate(true);
    try {
      const profile = await api.get<CandidateIdentity>(`/candidates/${encodeURIComponent(value)}`);
      if (generation === candidateRequestGeneration.current) setCandidate(profile);
    } catch (reason) {
      if (generation === candidateRequestGeneration.current) setCandidateLookupError(errorMessage(reason));
    } finally {
      if (generation === candidateRequestGeneration.current) setLookingUpCandidate(false);
    }
  }, []);

  useEffect(() => {
    if (role === 'employer' && prefilledCandidateId.current) void verifyCandidate(prefilledCandidateId.current);
  }, [role, verifyCandidate]);

  const lookupCandidate = () => void verifyCandidate(candidateLookupInput);

  const changeNeed = (value: string) => {
    if (value !== needId) {
      candidateRequestGeneration.current += 1; matchRequestGeneration.current += 1;
      setCandidateId(''); setCandidateLookupInput(''); setCandidate(null); setCandidateLookupError(''); setLookingUpCandidate(false);
      setMatchCandidates([]); setMatchesNextCursor(null); setMatchesError(''); setMatchesLoading(false); setLoadingMoreMatches(false);
    }
    setNeedId(value);
  };

  const resetCreateForm = () => {
    setTitle(''); setPurpose(''); setInstructions(''); setEstimatedMinutes('30'); setDueAt(defaultDeadline());
    setEvaluationMode('rubric'); setExpectedAnswer(''); setRubric(initialRubric());
  };

  const createTask = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError(''); setNotice('');
    if (!selectedNeed || !candidate) { setError('Выберите активную потребность и проверьте публичный профиль кандидата.'); return; }
    if (!dueAt || Number.isNaN(Date.parse(dueAt))) { setError('Укажите корректный срок ответа.'); return; }
    const payload: TaskCreateBody = {
      need_id: selectedNeed.id, candidate_id: candidate.id, expected_need_version: selectedNeed.version,
      title: title.trim(), purpose: purpose.trim(), instructions: instructions.trim(), estimated_minutes: Number(estimatedMinutes),
      due_at: new Date(Date.parse(dueAt)).toISOString(), evaluation_mode: evaluationMode,
      expected_answer: evaluationMode === 'exact' ? expectedAnswer.trim() : null,
      rubric: evaluationMode === 'rubric' ? rubric.map((item) => ({ ...item, label: item.label.trim() })) : [],
    };
    const invalid = taskValidationErrors(payload);
    if (invalid.length) { setError(`Проверьте поля: ${invalid.map((field) => ({ due_at: 'срок', expected_answer: 'закрытый ответ', estimated_minutes: 'объём', rubric: 'критерии рубрики' }[field] ?? field)).join(', ')}.`); return; }
    const pending = createPendingRequest(payload, () => crypto.randomUUID(), createPending.current);
    createPending.current = pending; setSaving(true);
    try {
      const created = await api.post<Task>('/short-tasks', payload as ApiPayload, pending.idempotencyKey);
      createPending.current = undefined;
      setTasks((current) => [created, ...current.filter((item) => item.id !== created.id)]); setSelectedId(created.id); resetCreateForm();
      setNotice('Задача отправлена кандидату. Её условия сохранены и не меняются задним числом.');
    } catch (reason) {
      if (reason instanceof RequestError && reason.status === 409) {
        await Promise.all([loadTasks(), api.get<NeedPage>('/needs?limit=100').then((result) => setNeeds(result.items)).catch(() => {})]);
        setNotice('Версия потребности изменилась. Список обновлён, введённые условия сохранены; проверьте их перед повторной отправкой.');
      }
      setError(errorMessage(reason));
    } finally { setSaving(false); }
  };

  const createPending = useRef<{ payload: TaskCreateBody; idempotencyKey: string } | undefined>(undefined);
  const pendingActions = useRef<Record<string, { payload: unknown; idempotencyKey: string }>>({});

  const runAction = async (task: Task, suffix: string, payload: unknown, success: string) => {
    const key = task.id + ':' + suffix;
    const pending = createPendingRequest(payload, () => crypto.randomUUID(), pendingActions.current[key]);
    pendingActions.current[key] = pending;
    setSaving(true); setError(''); setNotice('');
    try {
      const updated = await api.post<Task>('/short-tasks/' + encodeURIComponent(task.id) + '/' + suffix, payload as ApiPayload, pending.idempotencyKey);
      delete pendingActions.current[key];
      setTasks((current) => current.map((item) => item.id === updated.id ? updated : item));
      setNotice(success);
      if (suffix === 'submit') { setSolution(''); setConfirmReadyToSubmit(false); }
    } catch (reason) {
      if (reason instanceof RequestError && reason.status === 409) {
        await loadTasks();
        setNotice('Состояние задачи изменилось на сервере. Список обновлён; ваш текст сохранён, проверьте актуальные условия перед повтором.');
      }
      setError(errorMessage(reason));
    } finally { setSaving(false); }
  };

  const accept = (task: Task) => void runAction(task, 'accept', { expected_version: task.version, sharing_notice_version: task.sharing_notice.version }, 'Разрешение компании просматривать решение и обратную связь записано для этой версии уведомления. Отправить решение можно отдельно.');
  const decline = (task: Task) => void runAction(task, 'decline', { expected_version: task.version }, 'Вы отказались от задачи. Это не меняет подтверждённые компетенции или доступ к контактам.');
  const withdraw = (task: Task) => void runAction(task, 'withdraw', { expected_version: task.version }, 'Предложение отозвано.');
  const revokeSharing = (task: Task) => void runAction(task, 'revoke-sharing', { expected_version: task.version }, 'Доступ компании к вашему решению отозван.');

  const submitSolution = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedTask || !confirmReadyToSubmit || !solution.trim()) return;
    void runAction(selectedTask, 'submit', { expected_version: selectedTask.version, solution: solution.trim() }, 'Решение отправлено компании. Оно остаётся отдельным от зачётной оценки и грейда.');
  };

  const submitReview = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedTask) return;
    const criteria = selectedTask.evaluation_mode === 'rubric'
      ? selectedTask.rubric.map((item) => ({ id: item.id, score: Number(reviewScores[item.id] ?? 0) }))
      : [];
    if (criteria.some((item) => !Number.isInteger(item.score) || item.score < 0 || item.score > (selectedTask.rubric.find((criterion) => criterion.id === item.id)?.max_points ?? 100))) {
      setError('Проверьте баллы: значение не должно превышать максимум критерия.'); return;
    }
    if (!feedback.trim()) { setError('Добавьте предметную обратную связь перед сохранением оценки.'); return; }
    void runAction(selectedTask, 'review', { expected_version: selectedTask.version, criteria, feedback: feedback.trim(), source: 'employer' }, 'Оценка работодателя сохранена отдельно от автоматической проверки.');
  };

  const dueLabel = (value: string) => new Date(value).toLocaleString('ru-RU', { dateStyle: 'medium', timeStyle: 'short' });
  const statusTone = (status: Task['status']) => status === 'reviewed' ? 'good' : status === 'offered' ? 'warm' : 'neutral';
  const visibleTasks = role === 'candidate' && candidateFilter
    ? tasks.filter((task) => task.title.toLowerCase().includes(candidateFilter.toLowerCase()) || task.need_title.toLowerCase().includes(candidateFilter.toLowerCase()))
    : tasks;

  return <section className="extension-page address-tasks-page" aria-labelledby="short-tasks-title">
    <div className="extension-header"><div className="eyebrow">АДРЕСНАЯ ПРОВЕРКА ПОДХОДА</div><h1 id="short-tasks-title">Короткие задачи</h1><p>Отдельный рабочий материал по потребности. Отказ или результат не меняют зачётную оценку, грейд, рейтинг или доступ к контактам.</p></div>
    {error && <div className="extension-alert" role="alert">{error}<button type="button" className="text-button" onClick={() => void loadTasks()}>Обновить список</button></div>}
    {notice && <p className="extension-status" role="status">{notice}</p>}

    {role === 'employer' && <form className="extension-card address-task-create" onSubmit={(event) => void createTask(event)}>
      <div className="extension-card-head"><div><span className="eyebrow">ДЛЯ РАБОТОДАТЕЛЯ</span><h2>Предложить задачу кандидату</h2></div><span className="extension-step">Условия зафиксируются при отправке</span></div>
      <label className="field"><span>Активная подтверждённая потребность</span><select value={needId} onChange={(event) => changeNeed(event.target.value)} required><option value="">Выберите потребность</option>{activeNeeds.map((need) => <option value={need.id} key={need.id}>{need.title} · {need.target_grade}</option>)}</select><small>{selectedNeed ? 'Текущая версия потребности: ' + selectedNeed.version + '.' : 'Доступны только активные потребности с подтверждёнными компетенциями.'}</small></label>
      <div className="address-task-match-picker"><label className="field"><span>Кандидат из подборки по потребности</span><select value={candidateId} onChange={(event) => { if (event.target.value) void verifyCandidate(event.target.value); else { candidateRequestGeneration.current += 1; setCandidateId(''); setCandidate(null); setCandidateLookupError(''); setLookingUpCandidate(false); } }}><option value="">Выберите публичный профиль</option>{candidateId && !matchCandidates.some((match) => match.candidate.id === candidateId) && <option value={candidateId}>{candidate ? 'Выбрано по ссылке: ' + candidate.display_name : 'Проверяем профиль по ссылке…'}</option>}{matchCandidates.map((match) => <option key={match.candidate.id} value={match.candidate.id}>{match.candidate.display_name} · {matchStateLabel[match.state]}</option>)}</select><small>{matchesLoading ? 'Загружаем текущую публичную подборку…' : 'Профили сгруппированы в серверном порядке по этой потребности. Выбор повторно проверяется через API.'}</small></label>{matchesError && <div className="extension-inline-error" role="alert">{matchesError}<button type="button" className="text-button" onClick={() => selectedNeed && void requestMatchCandidates(selectedNeed)}>Повторить загрузку подборки</button></div>}{matchesNextCursor && <button type="button" className="button button-secondary button-small" disabled={loadingMoreMatches} onClick={() => selectedNeed && void requestMatchCandidates(selectedNeed, matchesNextCursor, true)}>{loadingMoreMatches ? 'Загружаем…' : 'Показать ещё кандидатов'}</button>}</div>
      <details className="address-task-manual-lookup"><summary>Указать ссылку на публичный профиль вручную</summary><div className="address-task-candidate-lookup"><label className="field"><span>Ссылка на профиль или UUID</span><input value={candidateLookupInput} onChange={(event) => { setCandidateLookupInput(event.target.value); candidateRequestGeneration.current += 1; setCandidateId(''); setCandidate(null); setCandidateLookupError(''); setLookingUpCandidate(false); }} onKeyDown={(event) => { if (event.key === 'Enter') { event.preventDefault(); lookupCandidate(); } }} placeholder="UUID или /candidates/…" aria-describedby="candidate-lookup-hint" /></label><button type="button" className="button button-secondary" disabled={lookingUpCandidate || !candidateLookupInput.trim()} onClick={lookupCandidate}>{lookingUpCandidate ? 'Проверяем…' : 'Проверить публичный профиль'}</button><small id="candidate-lookup-hint">Для отправки сервер ещё раз проверит актуальную публичность. Контакты не открываются.</small></div></details>
      {candidateLookupError && <p className="extension-inline-error" role="alert">{candidateLookupError}</p>}
      {candidate && <div className="address-task-candidate-preview"><span className="extension-avatar" aria-hidden="true">{candidate.display_name.slice(0, 1)}</span><div><strong>{candidate.display_name}</strong><small>{candidate.headline ?? 'Публичный профиль'} · {candidate.verified_grade ?? candidate.declared_grade ?? 'грейд не заявлен'}{candidate.demo ? ' · demo' : ''}</small></div><span className="extension-public-label">Профиль доступен</span></div>}
      <div className="extension-form-grid">
        <label className="field"><span>Название задачи</span><input value={title} maxLength={160} onChange={(event) => setTitle(event.target.value)} required /></label>
        <label className="field"><span>Цель проверки</span><input value={purpose} maxLength={500} onChange={(event) => setPurpose(event.target.value)} required /></label>
        <label className="field extension-span-all"><span>Инструкция кандидату</span><textarea value={instructions} maxLength={8000} rows={5} onChange={(event) => setInstructions(event.target.value)} required /></label>
        <label className="field"><span>Ожидаемый объём, минут</span><input type="number" min="1" max="480" step="1" value={estimatedMinutes} onChange={(event) => setEstimatedMinutes(event.target.value)} required /></label>
        <label className="field"><span>Срок ответа</span><input type="datetime-local" value={dueAt} onChange={(event) => setDueAt(event.target.value)} required /></label>
      </div>
      <fieldset className="extension-mode-picker"><legend>Способ оценки</legend><label><input type="radio" name="evaluation_mode" checked={evaluationMode === 'rubric'} onChange={() => setEvaluationMode('rubric')} /> По объявленной рубрике</label><label><input type="radio" name="evaluation_mode" checked={evaluationMode === 'exact'} onChange={() => setEvaluationMode('exact')} /> Однозначный закрытый ответ</label></fieldset>
      {evaluationMode === 'exact' ? <label className="field"><span>Закрытый ожидаемый ответ</span><input type="password" autoComplete="new-password" value={expectedAnswer} maxLength={1000} onChange={(event) => setExpectedAnswer(event.target.value)} required /><small>Ответ хранится только на сервере и не показывается кандидату.</small></label> : <fieldset className="extension-rubric-editor"><legend>Критерии оценки</legend>{rubric.map((item, index) => <div className="extension-rubric-row" key={item.id}><label className="field"><span>Критерий {index + 1}</span><input value={item.label} maxLength={200} onChange={(event) => setRubric((current) => current.map((criterion) => criterion.id === item.id ? { ...criterion, label: event.target.value } : criterion))} required /></label><label className="field"><span>Максимум баллов</span><input type="number" min="1" max="100" value={item.max_points} onChange={(event) => setRubric((current) => current.map((criterion) => criterion.id === item.id ? { ...criterion, max_points: Number(event.target.value) } : criterion))} required /></label>{rubric.length > 1 && <button type="button" className="text-button" onClick={() => setRubric((current) => current.filter((criterion) => criterion.id !== item.id))}>Удалить</button>}</div>)}{rubric.length < 20 && <button type="button" className="button button-secondary button-small" onClick={() => setRubric((current) => [...current, { id: 'criterion_' + crypto.randomUUID().slice(0, 8), label: '', max_points: 5 }])}>＋ Добавить критерий</button>}</fieldset>}
      <div className="extension-create-footer"><p>Задание зафиксирует версию потребности, цель, объём и критерии. Решение кандидата станет доступно только после отдельного согласия на передачу.</p><button className="button button-primary" disabled={saving || !selectedNeed || !candidate}>{saving ? 'Отправляем…' : 'Отправить задачу кандидату'}</button></div>
    </form>}

    {role === 'candidate' && <div className="extension-task-filter"><label className="field"><span>Найти по задаче или потребности</span><input value={candidateFilter} onChange={(event) => setCandidateFilter(event.target.value)} placeholder="Название задачи" /></label><Link className="button button-secondary" to="/invitations">К приглашениям</Link></div>}
    {loading && <p className="field-hint" role="status">Загружаем задачи…</p>}
    {!loading && <div className="address-task-layout">
      <aside className="extension-task-list" aria-label="Список коротких задач">{visibleTasks.length ? visibleTasks.map((task) => <button type="button" key={task.id} className={'extension-task-list-item ' + (selectedTask?.id === task.id ? 'is-selected' : '')} aria-pressed={selectedTask?.id === task.id} onClick={() => { setSelectedId(task.id); setError(''); setNotice(''); }}><span className="extension-task-list-meta">{role === 'candidate' ? task.need_title : 'Кандидат · ' + task.candidate_id.slice(0, 8) + '…'}</span><strong>{task.title}</strong><span className={'extension-status-badge tone-' + statusTone(task.status)}>{taskStatus[task.status]}</span></button>) : <div className="extension-list-empty">{role === 'candidate' ? 'Вам пока не предложили короткие задачи.' : 'Отправленные задачи появятся здесь.'}</div>}{nextCursor && <button type="button" className="button button-secondary button-small" disabled={loadingMore} onClick={() => void loadTasks(nextCursor, true)}>{loadingMore ? 'Загружаем…' : 'Загрузить ещё'}</button>}</aside>
      <div className="extension-task-detail">{selectedTask ? <article className="extension-card short-task-detail">
        <div className="extension-card-head"><div><span className="eyebrow">{selectedTask.need_title} · версия потребности {selectedTask.need_version}</span><h2>{selectedTask.title}</h2></div><span className={'extension-status-badge tone-' + statusTone(selectedTask.status)}>{taskStatus[selectedTask.status]}</span></div>
        <dl className="extension-task-facts"><div><dt>Цель</dt><dd>{selectedTask.purpose}</dd></div><div><dt>Объём</dt><dd>Около {selectedTask.estimated_minutes} минут</dd></div><div><dt>Срок</dt><dd>{dueLabel(selectedTask.due_at)}</dd></div><div><dt>Оценка</dt><dd>{selectedTask.evaluation_mode === 'exact' ? 'Закрытый ответ с автоматической проверкой' : 'Объявленная рубрика работодателя'}</dd></div></dl>
        <section className="extension-task-instructions"><h3>Условие</h3><p>{selectedTask.instructions}</p></section>
        {selectedTask.evaluation_mode === 'rubric' && <section className="extension-task-rubric"><h3>Критерии оценки</h3><ul>{selectedTask.rubric.map((item) => <li key={item.id}><span>{item.label}</span><strong>0–{item.max_points} баллов</strong></li>)}</ul></section>}
        {role === 'candidate' && <div className="extension-sharing-notice"><strong>Передача решения компании</strong><p>{selectedTask.sharing_notice.text}</p><small>Принимая эту версию уведомления, вы разрешаете компании просматривать решение и обратную связь. Контакты не раскрываются. Отправка решения — отдельное действие.</small></div>}
        {selectedTask.sharing_revoked_at && <p className="extension-closed" role="status">{role === 'candidate' ? 'Вы отозвали разрешение на передачу решения этой компании.' : 'Кандидат отозвал доступ к решению. Его текст и контакты скрыты.'}</p>}
        <p className="extension-no-grade">Эта задача не является зачётным тестом и не меняет подтверждённые компетенции, грейд, рейтинг или разрешение на контакт.</p>
        {canShowTaskResults(role, selectedTask.sharing_revoked_at) && selectedTask.automatic_result && <section className="extension-result-block extension-auto-result"><h3>Автоматическая проверка сервера</h3><p>{selectedTask.automatic_result.matched ? 'Ответ совпал с ожидаемым значением.' : 'Ответ не совпал с ожидаемым значением.'}</p><small>Источник: точное сравнение на сервере. Ключ ответа не раскрывается.</small></section>}
        {canShowTaskResults(role, selectedTask.sharing_revoked_at) && selectedTask.employer_review && <section className="extension-result-block extension-employer-review"><h3>Оценка работодателя</h3>{selectedTask.employer_review.criteria.length > 0 && <ul>{selectedTask.employer_review.criteria.map((criterion) => <li key={criterion.id}><span>{selectedTask.rubric.find((item) => item.id === criterion.id)?.label ?? criterion.id}</span><strong>{criterion.score} баллов</strong></li>)}</ul>}<p>{selectedTask.employer_review.feedback}</p><small>Оценка работодателя · {dueLabel(selectedTask.employer_review.reviewed_at)}</small></section>}
        {role === 'employer' && selectedTask.solution && <section className="extension-task-solution"><h3>Решение кандидата</h3><p>{selectedTask.solution}</p><small>Текст отображается с серверной фильтрацией контактных данных.</small></section>}
        {role === 'candidate' && selectedTask.solution && <section className="extension-task-solution"><h3>Ваше отправленное решение</h3><p>{selectedTask.solution}</p></section>}
        {role === 'candidate' && selectedTask.status === 'offered' && <div className="extension-task-actions"><button type="button" className="button button-secondary" disabled={saving} onClick={() => decline(selectedTask)}>Отказаться</button><button type="button" className="button button-primary" disabled={saving} onClick={() => accept(selectedTask)}>Принимаю задачу и уведомление</button></div>}
        {role === 'candidate' && selectedTask.status === 'accepted' && !selectedTask.sharing_revoked_at && <>
          <div style={{ marginBottom: '1rem', padding: '0.85rem', background: 'var(--surface-subtle, rgba(255,255,255,0.03))', borderRadius: '8px', border: '1px solid var(--border-subtle, rgba(255,255,255,0.08))', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap' }}>
            <div>
              <strong style={{ display: 'block', fontSize: '0.95rem' }}>Редактор черновика</strong>
              <small style={{ color: 'var(--muted, #888)' }}>Подготовьте или отредактируйте текст решения. Здесь он не запускается и не проверяется.</small>
            </div>
            <button type="button" className="button button-secondary" onClick={() => setEditorOpen(true)}>
              Открыть редактор
            </button>
          </div>
          {editorOpen && <SolutionEditorModal
            key={selectedTask.id}
            taskTitle={selectedTask.title}
            taskDescription={selectedTask.instructions}
            initialCode={solution}
            onClose={() => setEditorOpen(false)}
            onApplyToDraft={(code) => {
              setSolution(code);
              setNotice('Текст перенесён в черновик ответа. Проверьте его и подтвердите отправку отдельно.');
            }}
          />}
          <form className="extension-submit-solution" onSubmit={submitSolution}><label className="field"><span>Ваш ответ</span><textarea value={solution} onChange={(event) => setSolution(event.target.value)} maxLength={20000} rows={7} required aria-describedby={'solution-hint-' + selectedTask.id} /><small id={'solution-hint-' + selectedTask.id}>До 20&nbsp;000 символов. Не включайте контакты или персональные данные.</small></label><label className="extension-consent"><input type="checkbox" checked={confirmReadyToSubmit} onChange={(event) => setConfirmReadyToSubmit(event.target.checked)} /><span>Я проверил решение и готов отправить его по ранее принятым условиям передачи</span></label><button type="submit" className="button button-primary" disabled={saving || !confirmReadyToSubmit || !solution.trim()}>{saving ? 'Отправляем…' : 'Отправить решение'}</button></form>
        </>}
        {role === 'candidate' && selectedTask.status === 'accepted' && <p className="extension-sharing-hint">Согласие на доступ к решению уже записано при принятии этой версии уведомления. Отправка решения — отдельное действие.</p>}
        {role === 'candidate' && canRevokeTaskSharing(selectedTask.status, selectedTask.accepted_at, selectedTask.sharing_revoked_at) && <button type="button" className="button button-secondary extension-revoke-button" disabled={saving} onClick={() => revokeSharing(selectedTask)}>Отозвать доступ компании к решению</button>}
        {role === 'employer' && ['offered', 'accepted'].includes(selectedTask.status) && <button type="button" className="button button-secondary extension-revoke-button" disabled={saving} onClick={() => withdraw(selectedTask)}>Отозвать предложение</button>}
        {role === 'employer' && canReviewShortTask({ status: selectedTask.status, evaluation_mode: selectedTask.evaluation_mode, solution: selectedTask.solution, sharing_revoked_at: selectedTask.sharing_revoked_at, has_review: Boolean(selectedTask.employer_review) }) && <form className="extension-review-form" onSubmit={submitReview}><h3>Оценка работодателя</h3>{selectedTask.rubric.map((item) => <label className="field extension-score-row" key={item.id}><span>{item.label} · до {item.max_points} баллов</span><input type="number" min="0" max={item.max_points} step="1" value={reviewScores[item.id] ?? '0'} onChange={(event) => setReviewScores((current) => ({ ...current, [item.id]: event.target.value }))} required /></label>)}<label className="field"><span>Обратная связь</span><textarea value={feedback} onChange={(event) => setFeedback(event.target.value)} maxLength={4000} rows={4} required placeholder="Что выполнено, что нужно уточнить и какие вопросы обсудить" /></label><button type="submit" className="button button-primary" disabled={saving}>{saving ? 'Сохраняем…' : 'Сохранить оценку'}</button></form>}
      </article> : <div className="extension-list-empty">Выберите задачу слева.</div>}</div>
    </div>}
  </section>;
}
