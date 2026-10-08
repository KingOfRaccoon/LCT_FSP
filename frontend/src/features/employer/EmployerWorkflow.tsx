import { useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, errorMessage, RequestError, skillLabels, type ApiPayload, type Schemas, type SkillId } from '../../api';
import './employer.css';

type Need = Schemas['Need'];
type Candidate = Schemas['CandidatePublic'];
type Review = Schemas['CandidateReview'];
type Kit = Schemas['InterviewKit'];
type RecruitingRecord = Schemas['RecruitingRecord'];
type Stage = Schemas['RecruitingStage'];
type Shortlist = Schemas['Shortlist'];
type RoleTask = Schemas['RoleTask'];
type NeedPage = { items: Need[]; page: { next_cursor: string | null; limit: number } };

const stages: { id: Stage; label: string }[] = [
  { id: 'reviewing', label: 'На рассмотрении' }, { id: 'shortlisted', label: 'В шорт-листе' },
  { id: 'invited', label: 'Приглашение отправлено' }, { id: 'discussion', label: 'Обсуждение' }, { id: 'closed', label: 'Закрыто' },
];
const statusLabel: Record<Schemas['FitCheck']['status'], string> = {
  met: 'Подтверждено', declared: 'Заявлено кандидатом', unknown: 'Пока неизвестно', mismatch: 'Условие не совпадает',
};
const stateLabel: Record<Schemas['CandidateFit']['state'], string> = {
  confirmed: 'Подтверждённые требования', needs_clarification: 'Нужно уточнить', needs_confirmation: 'Нужно подтверждение', excluded: 'Не подходит по условиям',
};
const fitCheckLabel = (type: string) => type === 'skill' ? 'Навык' : type === 'grade' ? 'Уровень' : type === 'specialization' ? 'Специализация' : type === 'work_mode' ? 'Формат' : type === 'workload' ? 'Занятость' : type === 'city' ? 'Город' : type;
const workMode: Record<Need['work_mode'], string> = { remote: 'Удалённо', hybrid: 'Гибрид', office: 'Офис' };
const skillName = (id: string) => skillLabels[id as SkillId] ?? id;
const fitTone = (status: Schemas['FitCheck']['status']) => status === 'met' ? 'good' : status === 'mismatch' ? 'danger' : status === 'unknown' ? 'muted' : 'warm';
const encodeQuery = (values: Record<string, string | undefined>) => {
  const query = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => { if (value) query.set(key, value); });
  return query.toString();
};
const needPath = (needId: string, candidateId: string) => `/needs/${encodeURIComponent(needId)}/candidate-review?candidate_id=${encodeURIComponent(candidateId)}`;
const comparisonPath = (needId: string, candidateIds: string[]) => `/compare?${encodeQuery({ need_id: needId, candidate_ids: candidateIds.join(',') })}`;

function PageHeader({ title, description, eyebrow = 'РАБОТА С КАНДИДАТАМИ' }: { title: string; description: string; eyebrow?: string }) {
  return <header className="page-header"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1><p>{description}</p></div></header>;
}
function Loading({ label }: { label: string }) { return <div className="loading"><span className="spinner" aria-hidden="true" />{label}…</div>; }
function ErrorMessage({ message, retry }: { message: string; retry?: () => void }) { return <div className="error-box" role="alert"><span>{message}</span>{retry && <button className="button button-secondary button-small" onClick={retry}>Повторить</button>}</div>; }
function Notice({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'success' | 'danger' }) { return <div className={`notice${tone === 'info' ? '' : ` notice-${tone}`}`}>{children}</div>; }
function Badge({ children, tone = 'muted' }: { children: ReactNode; tone?: string }) { return <span className={`badge employer-badge employer-badge-${tone}`}>{children}</span>; }

/** Comparison uses only the public profiles returned for this fresh server request. Query: need_id and candidate_ids (comma separated). */
export function CandidateComparisonPage() {
  const location = useLocation();
  const query = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const needId = query.get('need_id') ?? query.get('need') ?? '';
  const rawIds = query.get('candidate_ids')?.split(',') ?? query.getAll('candidate_id');
  const candidateIds = [...new Set(rawIds.map((id) => id.trim()).filter(Boolean))];
  const [comparison, setComparison] = useState<Schemas['CandidateComparison']>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const requestSeq = useRef(0);

  useEffect(() => {
    const sequence = ++requestSeq.current;
    setComparison(undefined); setError('');
    if (!needId || candidateIds.length < 2 || candidateIds.length > 3) return;
    setLoading(true);
    api.post<Schemas['CandidateComparison']>('/candidates/compare', { need_id: needId, candidate_ids: candidateIds } as ApiPayload)
      .then((data) => { if (requestSeq.current === sequence) setComparison(data); })
      .catch((reason: unknown) => { if (requestSeq.current === sequence) setError(errorMessage(reason)); })
      .finally(() => { if (requestSeq.current === sequence) setLoading(false); });
    return () => { requestSeq.current += 1; };
  // candidateIds is derived from a stable serialized URL value.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [needId, candidateIds.join(','), location.key, refreshKey]);

  if (!needId || candidateIds.length < 2 || candidateIds.length > 3) {
    return <div className="employer-page"><PageHeader title="Сравнение кандидатов" description="Выберите действующую потребность и от двух до трёх кандидатов в подборке." />
      <div className="empty"><div className="empty-icon">⇄</div><h3>Нужен контекст сравнения</h3><p>Откройте сравнение из списка кандидатов или шорт-листа. В адресе должны быть need_id и 2–3 candidate_ids.</p><Link className="button button-secondary" to="/matches">Перейти к подбору</Link></div>
    </div>;
  }

  const retry = () => setRefreshKey((value) => value + 1);
  const items = comparison?.items ?? [];
  const rows = comparison ? comparisonRows(comparison.need, items) : [];
  return <div className="employer-page">
    <PageHeader title="Сравнение кандидатов" description="Одинаковые требования показаны в одних и тех же строках. Категории и неизвестные сведения объясняют соответствие, но не являются числовым рейтингом людей." />
    {error && <ErrorMessage message={error} retry={retry} />}
    {loading && <Loading label="Проверяем доступ и загружаем свежие профили" />}
    {comparison && <>
      <section className="card employer-need-context"><div><div className="eyebrow">ПОТРЕБНОСТЬ · {comparison.need.status === 'active' ? 'АКТИВНА' : comparison.need.status}</div><h2>{comparison.need.title}</h2><p>{comparison.need.description}</p></div><dl><div><dt>Формат</dt><dd>{workMode[comparison.need.work_mode]} · {comparison.need.workload_hours_per_week} ч/нед.</dd></div><div><dt>Метод подбора</dt><dd>{comparison.policy_version}</dd></div><div><dt>Сформировано</dt><dd>{new Date(comparison.generated_at).toLocaleString('ru-RU')}</dd></div></dl></section>
      <div className="employer-comparison-scroll" role="region" aria-label="Сравнительная таблица кандидатов" tabIndex={0}>
        <table className="employer-comparison-table"><thead><tr><th scope="col">Критерий</th>{items.map(({ candidate }) => <th scope="col" key={candidate.id}><div className="comparison-person"><strong>{candidate.display_name}</strong><small>{candidate.headline || 'Профиль специалиста'}</small><span>{candidate.demo || candidate.qualification?.demo ? <Badge tone="warm">DEMO</Badge> : <Badge tone="good">Данные профиля</Badge>}</span><small>Профиль v{candidate.profile_version ?? '—'} · оценка {candidate.qualification ? `v${candidate.qualification.policy_version} · ${new Date(candidate.qualification.assessed_at).toLocaleDateString('ru-RU')}` : 'не сдавалась'}</small></div></th>)}</tr></thead><tbody>
          <tr className="employer-table-section"><th scope="row" colSpan={items.length + 1}>Требования роли</th></tr>
          {rows.map((row) => <tr key={row.id}><th scope="row">{row.label}<small>{row.detail}</small></th>{items.map(({ candidate, fit }) => {
            const check = fit.checks.find((entry) => row.matches(entry));
            return <td key={candidate.id}>{check ? <><Badge tone={fitTone(check.status)}>{statusLabel[check.status]}</Badge><small>{check.reason}</small></> : <Badge tone="muted">Не указано</Badge>}</td>;
          })}</tr>)}
          <tr className="employer-table-section"><th scope="row" colSpan={items.length + 1}>Итоговая группа</th></tr>
          <tr><th scope="row">Соответствие условиям<small>Группа текущего matching-policy</small></th>{items.map(({ candidate, fit }) => <td key={candidate.id}><Badge tone={fit.state === 'confirmed' ? 'good' : fit.state === 'excluded' ? 'danger' : 'warm'}>{stateLabel[fit.state]}</Badge><small>{fit.summary}</small></td>)}</tr>
        </tbody></table>
      </div>
      <Notice>Сравнение построено по текущим данным. Категория не подтверждает качество найма, общая оценка теста между разными формами не сравнивается. Контакты в этом представлении не раскрываются.</Notice>
      <div className="employer-candidate-review-grid">{items.map((item) => <CandidateReviewPanel key={item.candidate.id} needId={comparison.need.id} candidateId={item.candidate.id} />)}</div>
    </>}
  </div>;
}

function comparisonRows(need: Need, items: Schemas['CandidateComparisonItem'][]) {
  const rows: { id: string; label: string; detail: string; matches: (check: Schemas['FitCheck']) => boolean }[] = need.requirements.map((requirement) => ({
    id: `skill-${requirement.skill_id}`, label: skillName(requirement.skill_id), detail: requirement.importance === 'required' ? 'Обязательный навык' : 'Желательный навык',
    matches: (check) => check.type === 'skill' && check.skill_id === requirement.skill_id,
  }));
  rows.push(...[
    { id: 'grade', label: `Уровень ${need.target_grade}`, detail: 'Заявленный и подтверждённый уровень', type: 'grade' },
    { id: 'work_mode', label: workMode[need.work_mode], detail: 'Предпочитаемый формат', type: 'work_mode' },
    { id: 'city', label: need.city ? `Город: ${need.city}` : 'Город', detail: 'При необходимости', type: 'city' },
    { id: 'workload', label: `${need.workload_hours_per_week} ч/нед.`, detail: 'Рабочая нагрузка', type: 'workload' },
  ].map((entry) => ({ ...entry, matches: (check: Schemas['FitCheck']) => check.type === entry.type })));
  for (const item of items) for (const check of item.fit.checks) {
    const id = check.type === 'skill' ? `skill-${check.skill_id}` : check.type;
    if (rows.some((row) => row.id === id)) continue;
    rows.push({ id, label: check.skill_id ? skillName(check.skill_id) : fitCheckLabel(check.type), detail: 'Условие соответствия', matches: (candidateCheck) => candidateCheck.type === check.type && candidateCheck.skill_id === check.skill_id });
  }
  return rows;
}

export function InterviewKitView({ kit, roleTasks = [] }: { kit: Kit; roleTasks?: RoleTask[] }) {
  return <section className="employer-interview-kit">
    <div className="employer-kit-heading"><div><div className="eyebrow">ИНТЕРВЬЮ · {kit.template_version}</div><h3>Единые вопросы по роли</h3></div><Badge tone="muted">без оценки платформой</Badge></div>
    <p className="employer-kit-disclaimer">{kit.disclaimer}</p>
    {kit.common_questions.length === 0 ? <p className="employer-muted">Для потребности пока нет подтверждённых требований, по которым можно подготовить общие вопросы.</p> : <ol className="employer-question-list">{kit.common_questions.map((question) => <li key={question.id}>
      <div><strong>{question.question}</strong><small>{skillName(question.skill_id)} · {question.importance === 'required' ? 'обязательный навык' : 'желательный навык'}</small>{question.task_ids.map((taskId) => { const task = roleTasks.find((entry) => entry.id === taskId); return task && <p className="employer-task-context" key={taskId}>Контекст задачи роли: {task.description}</p>; })}</div>
      <details><summary>Описательные ориентиры 1–3</summary><div className="employer-anchor-list">{question.criteria.map((anchor) => <p key={anchor.level}><Badge>{anchor.level}</Badge>{anchor.description}</p>)}<p><Badge tone="muted">—</Badge>{question.not_assessed_label}</p></div></details>
    </li>)}</ol>}
    <h4>Индивидуальные уточнения</h4>
    {kit.personal_followups.length === 0 ? <p className="employer-muted">Дополнительных уточнений нет.</p> : <ol className="employer-followup-list">{kit.personal_followups.slice(0, 3).map((topic) => <li key={topic.id}><strong>{topic.prompt}</strong><small>{topic.skill_id ? skillName(topic.skill_id) : 'Условия работы'} · {topic.evidence_status === 'unknown' ? 'сведения неизвестны' : 'заявлено кандидатом'}</small></li>)}</ol>}
    <p className="employer-muted">Рубрика одинаковая для кандидатов на одну потребность и не превращает интервью в грейд.</p>
  </section>;
}

export function CandidateReviewPanel({ needId, candidateId, onTopicsSelected }: { needId: string; candidateId: string; onTopicsSelected?: (topics: string[]) => void }) {
  const [review, setReview] = useState<Review>();
  const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let current = true;
    setError('');
    setLoading(true); setReview(undefined);
    api.get<Review>(`/needs/${encodeURIComponent(needId)}/candidate-review?candidate_id=${encodeURIComponent(candidateId)}`)
      .then((result) => { if (current) setReview(result); })
      .catch((reason: unknown) => { if (current) setError(errorMessage(reason)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [needId, candidateId, refresh]);
  const topics = review?.interview_kit.personal_followups.slice(0, 3).map((item) => item.prompt) ?? [];
  return <section className="card employer-review-panel"><div className="card-head"><div><div className="eyebrow">КАРТА РЕШЕНИЯ</div><h2>Проверка профиля</h2></div><Link className="quiet-link" to={needPath(needId, candidateId)}>Открыть карточку →</Link></div>
    {error && <ErrorMessage message={error} retry={() => setRefresh((value) => value + 1)} />}{loading && <Loading label="Проверяем текущую видимость профиля" />}
    {review && <><div className="employer-review-summary"><strong>{review.candidate.display_name}</strong><Badge tone={review.fit.state === 'confirmed' ? 'good' : 'warm'}>{stateLabel[review.fit.state]}</Badge><p>{review.fit.summary}</p></div><div className="employer-fit-list">{review.fit.checks.map((check, index) => <div key={`${check.type}-${check.skill_id ?? ''}-${index}`}><span>{check.skill_id ? skillName(check.skill_id) : fitCheckLabel(check.type)}</span><Badge tone={fitTone(check.status)}>{statusLabel[check.status]}</Badge><small>{check.reason}</small></div>)}</div>
      <InterviewKitView kit={review.interview_kit} roleTasks={review.need.role_tasks} />
      {onTopicsSelected && <button className="button button-secondary button-small" disabled={!topics.length} onClick={() => onTopicsSelected(topics)}>Скопировать темы в приглашение ({topics.length})</button>}
    </>}
  </section>;
}

/** Company-private pipeline. Server is the source of truth; filters and cursor are sent on every list request. */
export function RecruitingWorkspacePage() {
  const [records, setRecords] = useState<RecruitingRecord[]>([]); const [cursor, setCursor] = useState<string | null>(null); const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [candidateFilter, setCandidateFilter] = useState(''); const [needFilter, setNeedFilter] = useState(''); const [applied, setApplied] = useState({ candidate: '', need: '' });
  const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [revision, setRevision] = useState(0);
  const [editing, setEditing] = useState<{ candidateId: string; needId: string | null }>();
  useEffect(() => {
    let current = true; setLoading(true); setError('');
    const query = new URLSearchParams({ limit: '30' }); if (cursor) query.set('cursor', cursor); if (applied.candidate) query.set('candidate_id', applied.candidate); if (applied.need) query.set('need_id', applied.need);
    if (!cursor) setRecords([]);
    api.get<Schemas['RecruitingRecordPage']>(`/recruiting/records?${query.toString()}`)
      .then((page) => { if (!current) return; setRecords((existing) => cursor ? [...existing, ...page.items] : page.items); setNextCursor(page.next_cursor); })
      .catch((reason: unknown) => { if (current) setError(errorMessage(reason)); })
      .finally(() => { if (current) setLoading(false); });
    return () => { current = false; };
  }, [cursor, applied, revision]);
  const submitFilter = (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); setCursor(null); setApplied({ candidate: candidateFilter.trim(), need: needFilter.trim() }); };
  return <div className="employer-page"><PageHeader title="Рабочая воронка" description="Приватные заметки и этапы вашей компании. Они не влияют на оценку кандидата и не означают, что приглашение принято или контакт открыт." />
    <form className="card employer-record-filters" onSubmit={submitFilter}><label className="field"><span>ID кандидата</span><input value={candidateFilter} onChange={(event) => setCandidateFilter(event.target.value)} placeholder="UUID кандидата" /></label><label className="field"><span>ID потребности</span><input value={needFilter} onChange={(event) => setNeedFilter(event.target.value)} placeholder="UUID потребности" /></label><button className="button button-secondary">Применить фильтры</button><button type="button" className="button button-primary" onClick={() => setEditing({ candidateId: '', needId: applied.need || null })}>＋ Новая запись</button></form>
    {error && <ErrorMessage message={error} retry={() => setRevision((value) => value + 1)} />}
    {loading ? <Loading label="Загружаем только записи вашей компании" /> : records.length === 0 ? <div className="empty"><div className="empty-icon">▤</div><h3>Записей пока нет</h3><p>Записи появятся после сохранения рабочего этапа по конкретному кандидату.</p></div> : <div className="employer-record-list">{records.map((record) => <article className="card employer-record-card" key={record.id}><div className="employer-record-heading"><div><div className="eyebrow">{record.need_title ?? 'Без привязки к потребности'} · {new Date(record.updated_at).toLocaleString('ru-RU')}</div><h2>{record.candidate_display_name ?? 'Профиль скрыт'}</h2><code>{record.candidate_id}</code></div><Badge tone={record.candidate_visibility === 'public' ? 'good' : 'muted'}>{record.candidate_visibility === 'public' ? 'Публичный профиль' : 'Профиль скрыт'}</Badge></div><div className="employer-record-body"><Badge>{stages.find((stage) => stage.id === record.stage)?.label ?? record.stage}</Badge><p>{record.note || 'Личной заметки нет.'}</p></div><button className="button button-secondary button-small" onClick={() => setEditing({ candidateId: record.candidate_id, needId: record.need_id })}>Открыть свою запись</button></article>)}</div>}
    {nextCursor && !loading && <button className="button button-secondary employer-next-page" onClick={() => setCursor(nextCursor)}>Загрузить следующую страницу</button>}
    {editing && <RecruitingRecordEditor candidateId={editing.candidateId} needId={editing.needId} onSaved={() => { setEditing(undefined); setRevision((value) => value + 1); }} onCancel={() => setEditing(undefined)} />}
  </div>;
}

export function RecruitingRecordEditor({ candidateId, needId, onSaved, onCancel }: { candidateId: string; needId?: string | null; onSaved?: (record: RecruitingRecord) => void; onCancel?: () => void }) {
  const [current, setCurrent] = useState<RecruitingRecord>(); const [stage, setStage] = useState<Stage>('reviewing'); const [note, setNote] = useState(''); const [loading, setLoading] = useState(true); const [saving, setSaving] = useState(false); const [error, setError] = useState(''); const [saved, setSaved] = useState('');
  const [candidateInput, setCandidateInput] = useState(candidateId); const [needInput, setNeedInput] = useState(needId ?? ''); const location = useLocation();
  const dialog = useRef<HTMLElement>(null); const previouslyFocused = useRef<HTMLElement | null>(null);
  useEffect(() => {
    previouslyFocused.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const first = dialog.current?.querySelector<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
    first?.focus();
    return () => { if (previouslyFocused.current?.isConnected) previouslyFocused.current.focus(); previouslyFocused.current = null; };
  }, []);
  useEffect(() => {
    let active = true;
    if (!candidateId) { setCurrent(undefined); setNeedInput(needId ?? ''); setStage('reviewing'); setNote(''); setLoading(false); return () => { active = false; }; }
    setLoading(true); setError('');
    const query = new URLSearchParams({ candidate_id: candidateId });
    api.get<Schemas['RecruitingRecordPage']>(`/recruiting/records?${query.toString()}`)
      .then((page) => { if (!active) return; const own = page.items.find((record) => record.candidate_id === candidateId); setCurrent(own); setNeedInput(own ? own.need_id ?? '' : needId ?? ''); setStage(own?.stage ?? 'reviewing'); setNote(own?.note ?? ''); })
      .catch((reason: unknown) => { if (active) setError(errorMessage(reason)); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [candidateId, needId, location.key]);
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError(''); setSaved('');
    const id = candidateId || candidateInput.trim(); if (!id) { setError('Укажите UUID кандидата.'); return; }
    setSaving(true);
    try {
      let existing = current;
      if (!existing) {
        const lookup = new URLSearchParams({ candidate_id: id, limit: '100' });
        const page = await api.get<Schemas['RecruitingRecordPage']>(`/recruiting/records?${lookup.toString()}`);
        existing = page.items.find((record) => record.candidate_id === id);
        if (existing) { setCurrent(existing); setNeedInput(existing.need_id ?? ''); setStage(existing.stage); setNote(existing.note ?? ''); }
      }
      const payload: Schemas['RecruitingRecordPutRequest'] = { expected_version: existing?.version ?? 0, need_id: needInput.trim() || null, stage, note: note.trim() || null };
      const record = await api.put<RecruitingRecord>(`/recruiting/records/${encodeURIComponent(id)}`, payload as ApiPayload);
      setCurrent(record); setStage(record.stage); setNote(record.note ?? ''); setSaved('Запись сохранена для вашей компании.'); onSaved?.(record);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setSaving(false); }
  };
  const onDialogKeyDown = (event: ReactKeyboardEvent<HTMLElement>) => {
    if (event.key === 'Escape') { event.preventDefault(); onCancel?.(); return; }
    if (event.key !== 'Tab') return;
    const focusable = dialog.current?.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
    if (!focusable?.length) return;
    const first = focusable[0]; const last = focusable[focusable.length - 1];
    if (!dialog.current?.contains(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
    else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  };
  return <div className="employer-modal-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel?.(); }}><section ref={dialog} className="card employer-record-editor" role="dialog" aria-modal="true" aria-labelledby="record-editor-title" aria-describedby="record-editor-description" onKeyDown={onDialogKeyDown}><div className="card-head"><div><div className="eyebrow">ТОЛЬКО ВАША КОМПАНИЯ</div><h2 id="record-editor-title">Рабочая запись</h2></div><button className="text-button" type="button" onClick={onCancel} aria-label="Закрыть">Закрыть ×</button></div>
    <p id="record-editor-description" className="employer-muted">Заметка и этап видны только вашей компании. Закрытие окна не отменяет уже отправленное сохранение.</p>
    {error && <ErrorMessage message={error} />}{saved && <Notice tone="success">{saved}</Notice>}{loading ? <Loading label="Проверяем текущую версию записи" /> : <form className="form-stack" onSubmit={(event) => void submit(event)}>
      <label className="field"><span>UUID кандидата</span><input value={candidateInput} onChange={(event) => setCandidateInput(event.target.value)} readOnly={Boolean(candidateId)} required /></label>
      <label className="field"><span>UUID потребности (необязательно)</span><input value={needInput} onChange={(event) => setNeedInput(event.target.value)} placeholder="Можно оставить без привязки" /><small>Связь с потребностью можно изменить; запись остаётся одной на кандидата в вашей компании.</small></label>
      {current?.candidate_visibility === 'hidden' && <Notice tone="danger">Кандидат скрыл публичный профиль. Сохранённая заметка вашей компании остаётся доступна; публичные сведения и контакты не загружаются.</Notice>}
      <label className="field"><span>Этап вашей команды</span><select value={stage} onChange={(event) => setStage(event.target.value as Stage)}>{stages.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}</select></label>
      <label className="field"><span>Приватная заметка <small>до 2000 символов</small></span><textarea value={note} onChange={(event) => setNote(event.target.value)} rows={6} maxLength={2000} placeholder="Факты и следующие шаги команды" /><small>{note.length}/2000</small></label>
      <Notice>Этап и заметка не создают приглашение, не открывают контакты и не подтверждают факт найма.</Notice>
      <div className="employer-editor-actions"><button type="button" className="button button-secondary" onClick={onCancel}>Отмена</button><button className="button button-primary" disabled={saving}>{saving ? 'Сохраняем…' : current ? `Сохранить · версия ${current.version}` : 'Создать запись'}</button></div>
    </form>}</section></div>;
}

export function RoleTaskEditor({ requirements, value, onChange }: { requirements: Need['requirements']; value: RoleTask[]; onChange: (tasks: RoleTask[]) => void }) {
  const requirementIds = requirements.map((requirement) => requirement.skill_id);
  const update = (index: number, task: RoleTask) => onChange(value.map((item, itemIndex) => {
    const next = itemIndex === index ? task : item;
    return { ...next, skill_ids: next.skill_ids.filter((id) => requirementIds.includes(id)) };
  }));
  const add = () => { if (value.length >= 10 || requirementIds.length === 0) return; onChange([...value, { id: crypto.randomUUID(), description: '', skill_ids: [requirementIds[0]] }]); };
  return <section className="employer-role-tasks"><div className="employer-kit-heading"><div><h3>Задачи на роли</h3><p>Описывайте конкретную работу и связывайте её только с навыками требований.</p></div><button type="button" className="button button-secondary button-small" onClick={add} disabled={value.length >= 10 || requirementIds.length === 0}>Добавить задачу</button></div>
    {requirementIds.length === 0 && <Notice tone="danger">Сначала добавьте хотя бы один навык в требования роли.</Notice>}
    {value.map((task, index) => <article className="employer-role-task" key={task.id}><div className="employer-role-task-heading"><strong>Задача {index + 1}</strong><button type="button" className="text-button" onClick={() => onChange(value.filter((_, taskIndex) => taskIndex !== index))}>Удалить</button></div><label className="field"><span>Что предстоит делать</span><textarea value={task.description} onChange={(event) => update(index, { ...task, description: event.target.value.slice(0, 1000) })} rows={3} maxLength={1000} placeholder="Например: проектировать HTTP API для сервиса заказов" /><small>{task.description.length}/1000</small></label><fieldset className="employer-task-skills"><legend>Связанные требования роли</legend>{requirements.map((requirement) => <label key={requirement.skill_id}><input type="checkbox" checked={task.skill_ids.includes(requirement.skill_id)} disabled={task.skill_ids.includes(requirement.skill_id) && task.skill_ids.length <= 1} onChange={(event) => update(index, { ...task, skill_ids: event.target.checked ? [...new Set([...task.skill_ids, requirement.skill_id])] : task.skill_ids.filter((id) => id !== requirement.skill_id) })} />{skillName(requirement.skill_id)} <small>{requirement.importance === 'required' ? 'обязательный' : 'желательный'}</small></label>)}</fieldset></article>)}
  </section>;
}

export function ShortlistManager({ shortlist, onChanged }: { shortlist: Shortlist; onChanged: (shortlist: Shortlist) => void }) {
  const navigate = useNavigate(); const [needs, setNeeds] = useState<Need[]>([]); const [selectedNeed, setSelectedNeed] = useState(shortlist.need_id ?? ''); const [name, setName] = useState(shortlist.name); const [editingName, setEditingName] = useState(false); const [loadingNeeds, setLoadingNeeds] = useState(false); const [error, setError] = useState(''); const [pending, setPending] = useState(false);
  const [profiles, setProfiles] = useState<Record<string, Candidate | null | 'error'>>({}); const [profileSnapshotKey, setProfileSnapshotKey] = useState(''); const [loadingProfiles, setLoadingProfiles] = useState(false); const [profileErrorCount, setProfileErrorCount] = useState(0); const [profileRefresh, setProfileRefresh] = useState(0);
  const idsKey = shortlist.candidate_ids.join(',');
  const profileKey = `${idsKey}:${profileRefresh}`;
  const currentProfiles = profileSnapshotKey === profileKey ? profiles : {};
  const [selectedIds, setSelectedIds] = useState<string[]>(shortlist.candidate_ids.length < 4 ? shortlist.candidate_ids : []);
  useEffect(() => { setSelectedIds(shortlist.candidate_ids.length < 4 ? shortlist.candidate_ids : []); }, [shortlist.id, idsKey]);
  useEffect(() => { setName(shortlist.name); }, [shortlist.id, shortlist.version, shortlist.name]);
  useEffect(() => {
    let active = true;
    setProfiles({}); setProfileErrorCount(0);
    const ids = [...shortlist.candidate_ids];
    if (ids.length === 0) { setLoadingProfiles(false); return () => { active = false; }; }
    setLoadingProfiles(true);
    const loadCurrentPublicProfiles = async () => {
      let unavailableCount = 0;
      for (let index = 0; index < ids.length; index += 8) {
        const batch = ids.slice(index, index + 8);
        const results = await Promise.all(batch.map(async (id): Promise<[string, Candidate | null | 'error', boolean]> => {
          try { return [id, await api.get<Candidate>(`/candidates/${encodeURIComponent(id)}`), false]; }
          catch (reason) {
            if (reason instanceof RequestError && (reason.status === 403 || reason.status === 404)) return [id, null, false];
            return [id, 'error', true];
          }
        }));
        if (!active) return;
        setProfiles((current) => ({ ...current, ...Object.fromEntries(results.map(([id, candidate]) => [id, candidate])) }));
        setProfileSnapshotKey(profileKey);
        unavailableCount += results.filter(([, , failed]) => failed).length;
        setProfileErrorCount(unavailableCount);
      }
    };
    void loadCurrentPublicProfiles().finally(() => { if (active) setLoadingProfiles(false); });
    return () => { active = false; };
  }, [shortlist.id, idsKey, profileRefresh, profileKey]);
  useEffect(() => {
    if (shortlist.need_id) return;
    let current = true; setLoadingNeeds(true);
    api.get<NeedPage>('/needs?limit=100').then((page) => { if (current) { const activeNeeds = page.items.filter((need) => need.status === 'active'); setNeeds(activeNeeds); if (!selectedNeed && activeNeeds.length === 1) setSelectedNeed(activeNeeds[0].id); } })
      .catch((reason: unknown) => { if (current) setError(errorMessage(reason)); }).finally(() => { if (current) setLoadingNeeds(false); });
    return () => { current = false; };
  }, [shortlist.need_id]);
  const patch = async (payload: Schemas['ShortlistPatchRequest']): Promise<boolean> => {
    setPending(true); setError('');
    try { const changed = await api.patch<Shortlist>(`/shortlists/${encodeURIComponent(shortlist.id)}`, payload as ApiPayload); onChanged(changed); setName(changed.name); return true; }
    catch (reason) { setError(errorMessage(reason)); return false; }
    finally { setPending(false); }
  };
  const remove = (candidateId: string) => void patch({ expected_version: shortlist.version, candidate_ids: shortlist.candidate_ids.filter((id) => id !== candidateId) });
  const saveName = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); if (await patch({ expected_version: shortlist.version, name: name.trim() })) setEditingName(false); };
  const toggleSelection = (candidateId: string, checked: boolean) => setSelectedIds((selected) => checked ? [...new Set([...selected, candidateId])] : selected.filter((id) => id !== candidateId));
  const currentNeedId = shortlist.need_id ?? selectedNeed;
  const compare = () => {
    if (!currentNeedId) { setError('Выберите активную потребность для сравнения.'); return; }
    if (selectedIds.length < 2 || selectedIds.length > 3) { setError('Отметьте от двух до трёх кандидатов для сравнения.'); return; }
    navigate(comparisonPath(currentNeedId, selectedIds));
  };
  return <section className="employer-shortlist-manager"><div className="employer-kit-heading"><div><h3>{shortlist.name}</h3><p>{shortlist.candidate_ids.length} кандидатов · версия {shortlist.version}</p></div><div className="employer-shortlist-actions"><button className="button button-secondary button-small" onClick={() => setEditingName((value) => !value)}>Изменить название</button><button className="button button-primary button-small" onClick={compare} disabled={pending || selectedIds.length < 2 || selectedIds.length > 3}>Сравнить {selectedIds.length} выбранных →</button></div></div>
    {error && <ErrorMessage message={error} />}
    {editingName && <form className="employer-rename-form" onSubmit={saveName}><label className="field"><span>Название подборки</span><input value={name} onChange={(event) => setName(event.target.value)} maxLength={120} required /></label><button className="button button-secondary button-small" disabled={pending}>Сохранить · версия {shortlist.version}</button></form>}
    {!shortlist.need_id && <label className="field employer-need-select"><span>Потребность для сравнения</span>{loadingNeeds ? <small>Загружаем активные потребности…</small> : <select value={selectedNeed} onChange={(event) => setSelectedNeed(event.target.value)}><option value="">Выберите потребность</option>{needs.map((need) => <option key={need.id} value={need.id}>{need.title} · {need.target_grade}</option>)}</select>}</label>}
    <div className="employer-shortlist-select-heading"><strong>Отметьте кандидатов для сравнения</strong><span>{selectedIds.length} из 2–3</span><button type="button" className="text-button" disabled={loadingProfiles || shortlist.candidate_ids.length === 0} onClick={() => setProfileRefresh((value) => value + 1)}>Обновить доступность</button></div>
    {profileErrorCount > 0 && <ErrorMessage message={`Не удалось проверить публичность ${profileErrorCount} профилей. Обновите данные и повторите.`} retry={() => setProfileRefresh((value) => value + 1)} />}
    {shortlist.candidate_ids.length === 0 ? <p className="employer-muted">Подборка пуста. Добавьте кандидатов из результатов подбора.</p> : loadingProfiles && Object.keys(currentProfiles).length === 0 ? <Loading label={`Проверяем текущую доступность ${shortlist.candidate_ids.length} публичных профилей`} /> : <ul className="employer-shortlist-ids">{shortlist.candidate_ids.map((id) => {
      const profile = currentProfiles[id]; const candidate = typeof profile === 'object' ? profile : undefined; const unavailable = profile === null || profile === 'error';
      return <li key={id} className="employer-shortlist-candidate"><label className="employer-shortlist-choice"><input type="checkbox" name="candidate_ids" value={id} checked={selectedIds.includes(id)} disabled={pending || profile === undefined && !selectedIds.includes(id) || unavailable && !selectedIds.includes(id) || selectedIds.length >= 3 && !selectedIds.includes(id)} onChange={(event) => toggleSelection(id, event.target.checked)} aria-label={`Сравнить ${candidate?.display_name ?? 'кандидата'}`} /><span className="employer-shortlist-copy"><strong>{candidate?.display_name ?? (profile === null ? 'Профиль скрыт или недоступен' : profile === 'error' ? 'Не удалось проверить профиль' : loadingProfiles ? 'Проверяем профиль…' : 'Не удалось загрузить профиль')}</strong><small>{candidate ? `${candidate.headline ?? 'Профиль специалиста'} · ${candidate.verified_grade ?? candidate.declared_grade}${candidate.demo || candidate.qualification?.demo ? ' · DEMO' : ''}` : id}</small></span></label><button type="button" className="text-button" disabled={pending} onClick={() => remove(id)} aria-label={`Убрать ${candidate?.display_name ?? 'кандидата'} из подборки`}>Убрать</button></li>;
    })}</ul>}
    {selectedIds.length < 2 || selectedIds.length > 3 ? <Notice>Для сравнения выберите от двух до трёх доступных кандидатов. Остальные останутся в шорт-листе.</Notice> : <Notice tone="success">Выбраны {selectedIds.length} кандидата. Остальные участники подборки сохраняются.</Notice>}
    <p className="employer-muted">Убрать кандидата из списка можно без удаления профиля и без изменения его приглашений.</p>
  </section>;
}
