import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api, errorMessage, skillLabels, type Attempt, type CandidateProfile, type Schemas } from '../../api';
import './candidate.css';

type ExperienceInput = Schemas['ExperienceEntryInput'];
type ProjectInput = Schemas['ProjectEntryInput'];
type Skill = ExperienceInput['skill_ids'][number];

const skills = Object.keys(skillLabels) as Skill[];
const grades: Record<string, string> = { junior: 'Junior', middle: 'Middle' };
const dateLabel = (value: string) => new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
const workModeLabels: Record<string, string> = { remote: 'Удалённо', hybrid: 'Гибрид', office: 'Офис' };
const toExperienceInput = (entry: Schemas['ExperienceEntry']): ExperienceInput => ({ id: entry.id, company: entry.company, role: entry.role, description: entry.description, start_month: entry.start_month, end_month: entry.end_month, skill_ids: entry.skill_ids });
const toProjectInput = (entry: Schemas['ProjectEntry']): ProjectInput => ({ id: entry.id, title: entry.title, description: entry.description, url: entry.url, skill_ids: entry.skill_ids });
const safeHttpsUrl = (value: string | null) => {
  if (!value) return null;
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password ? url.href : null; }
  catch { return null; }
};

const practiceQuestions = [
  {
    id: 'practice-http-timeout',
    block: 'HTTP и API',
    prompt: 'Сервис получает от соседнего API ответ дольше обычного. Как безопаснее всего ограничить ожидание?',
    options: ['Бесконечно ждать, чтобы не потерять ответ', 'Установить конечный timeout и обработать его как отдельную ошибку', 'Повторять запрос каждую миллисекунду', 'Увеличить число потоков без ограничения'],
    correct: 1,
    explanation: 'Конечный timeout ограничивает расход ресурсов. Повторы, если они нужны, задают отдельно: с лимитом, задержкой и только для безопасных операций.',
  },
  {
    id: 'practice-sql-transaction',
    block: 'SQL и транзакции',
    prompt: 'Перевод состоит из списания и зачисления. Как сохранить целостность, если второе действие завершится ошибкой?',
    options: ['Сохранить списание, а зачисление повторить вручную позже', 'Скрыть ошибку и вернуть успешный ответ', 'Выполнить обе операции в транзакции и откатить её при ошибке', 'Выполнить два независимых запроса без проверки результата'],
    correct: 2,
    explanation: 'Транзакция атомарно фиксирует обе операции или откатывает обе. Для распределённых систем потребуются дополнительные гарантии и компенсационные действия.',
  },
  {
    id: 'practice-python-immutability',
    block: 'Основы Python',
    prompt: 'Функция с изменяемым значением по умолчанию вызывается несколько раз. Какое поведение важно проверить?',
    options: ['Python автоматически создаёт новый список при каждом вызове', 'Изменяемые аргументы запрещены', 'Объект по умолчанию может сохранять изменения между вызовами', 'Значение по умолчанию всегда становится локальной копией'],
    correct: 2,
    explanation: 'Значение по умолчанию создаётся один раз при определении функции. Частый безопасный шаблон — None и создание нового списка внутри функции.',
  },
  {
    id: 'practice-reliability-idempotency',
    block: 'Надёжность',
    prompt: 'Клиент не получил ответ после создания заказа и повторил запрос. Какая мера помогает избежать второго заказа?',
    options: ['Повторять запрос с новым случайным телом', 'Обработать стабильный ключ идемпотентности вместе с операцией', 'Отключить журналирование запросов', 'Всегда отвечать 200 до сохранения заказа'],
    correct: 1,
    explanation: 'Сервер связывает ключ повтора с результатом операции и возвращает тот же результат при безопасном повторе. Ключ должен быть ограничен областью пользователя и операции.',
  },
] as const;

export function PracticePage() {
  const [answers, setAnswers] = useState<Record<string, number>>({});
  return <main className="candidate-practice">
    <header className="candidate-feature-heading">
      <span className="candidate-eyebrow">ПОДГОТОВКА К ФОРМАТУ ОЦЕНКИ</span>
      <h1>Попробуйте тренировочные вопросы</h1>
      <p>Это отдельная демонстрация интерфейса. Ответы не оцениваются платформой, не сохраняются и не влияют на профиль, грейд или право на повторную оценку.</p>
    </header>
    <div className="candidate-practice-banner" role="note"><strong>Тренировка интерфейса · без присвоения грейда</strong><span>Выберите вариант, чтобы увидеть разбор. Вы увидите статус сохранения выбора в этой тренировке, но ответы останутся только в памяти открытой страницы.</span></div>
    {practiceQuestions.map((item, index) => {
      const selected = answers[item.id];
      return <section className="card candidate-practice-card" key={item.id}>
        <div className="candidate-practice-meta"><span>{String(index + 1).padStart(2, '0')} · {item.block}</span><span>Тренировка</span></div>
        <h2>{item.prompt}</h2>
        <fieldset className="candidate-practice-options">
          <legend className="candidate-visually-hidden">Варианты ответа: {item.prompt}</legend>
          {item.options.map((option, optionIndex) => <label className={`candidate-practice-option ${selected === optionIndex ? 'is-selected' : ''}`} key={option}>
            <input type="radio" name={item.id} value={optionIndex} checked={selected === optionIndex} onChange={() => setAnswers((current) => ({ ...current, [item.id]: optionIndex }))} />
            <span className="candidate-option-key" aria-hidden="true">{String.fromCharCode(65 + optionIndex)}</span><span>{option}</span>
          </label>)}
        </fieldset>
        {selected !== undefined && <div className={`candidate-practice-explanation ${selected === item.correct ? 'is-correct' : 'is-review'}`} aria-live="polite"><strong>{selected === item.correct ? 'Верно для этого тренировочного вопроса' : 'Посмотрите разбор'}</strong><p>{item.explanation}</p></div>}
        {selected !== undefined && <p className="candidate-practice-save-state" role="status">✓ Выбор сохранён в этой тренировке; сервер его не получал</p>}
      </section>;
    })}
    <p className="candidate-practice-footnote">После закрытия страницы выборы и разборы исчезнут. Эта тренировка не использует вопросы зачётного банка.</p>
  </main>;
}

export function ProfileEvidenceEditor({ profile, onChanged }: { profile: CandidateProfile; onChanged: (profile: CandidateProfile) => void }) {
  const [experience, setExperience] = useState<ExperienceInput[]>(() => profile.experience_entries.map(toExperienceInput));
  const [projects, setProjects] = useState<ProjectInput[]>(() => profile.project_entries.map(toProjectInput));
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    setExperience(profile.experience_entries.map(toExperienceInput));
    setProjects(profile.project_entries.map(toProjectInput));
    setDirty(false);
  }, [profile.id, profile.version]);

  const updateExperience = (id: string, update: Partial<ExperienceInput>) => { setExperience((current) => current.map((entry) => entry.id === id ? { ...entry, ...update } : entry)); setDirty(true); setMessage(''); };
  const updateProject = (id: string, update: Partial<ProjectInput>) => { setProjects((current) => current.map((entry) => entry.id === id ? { ...entry, ...update } : entry)); setDirty(true); setMessage(''); };
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError(''); setMessage('');
    const invalidDates = experience.some((entry) => entry.end_month && entry.start_month && entry.start_month > entry.end_month);
    const unsafeProjectUrl = projects.some((entry) => {
      if (!entry.url) return false;
      try { const url = new URL(entry.url); return url.protocol !== 'https:' || Boolean(url.username || url.password); }
      catch { return true; }
    });
    if (invalidDates) { setError('Дата окончания опыта не может быть раньше даты начала.'); return; }
    if (unsafeProjectUrl) { setError('Для ссылки на проект используйте HTTPS без логина и пароля в адресе.'); return; }
    setSaving(true);
    try {
      const updated = await api.patch<CandidateProfile>('/me/profile', {
        expected_version: profile.version,
        experience_entries: experience,
        project_entries: projects,
      });
      onChanged(updated); setDirty(false); setMessage('Опыт и проекты сохранены. Они отмечены как заявленные вами сведения.');
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setSaving(false); }
  };

  return <section className="card candidate-evidence-editor" aria-labelledby="candidate-evidence-title">
    <div className="candidate-section-heading"><div><span className="candidate-eyebrow">ДОПОЛНИТЕЛЬНЫЕ СВЕДЕНИЯ</span><h2 id="candidate-evidence-title">Опыт и проекты</h2></div><span className="candidate-provenance-chip">Заявлено кандидатом</span></div>
    <p className="candidate-section-intro">Эти сведения помогают понять контекст вашего опыта. Они не меняют результат оценки и не считаются независимо проверенными навыками.</p>
    {error && <p className="candidate-inline-error" role="alert">{error}</p>}{message && <p className="candidate-inline-success" role="status">{message}</p>}
    <form onSubmit={save}>
      <div className="candidate-evidence-list">
        {experience.map((entry, index) => <fieldset className="candidate-evidence-entry" key={entry.id}>
          <legend>Опыт {index + 1}</legend><button className="candidate-remove-button" type="button" onClick={() => { setExperience((current) => current.filter((value) => value.id !== entry.id)); setDirty(true); }}>Удалить</button>
          <div className="candidate-evidence-grid">
            <label>Роль<input required maxLength={160} value={entry.role} onChange={(event) => updateExperience(entry.id, { role: event.target.value })} /></label>
            <label>Компания <span className="candidate-optional">Необязательно</span><input maxLength={160} value={entry.company ?? ''} onChange={(event) => updateExperience(entry.id, { company: event.target.value || null })} /></label>
            <label>Начало<input required type="month" value={entry.start_month} onChange={(event) => updateExperience(entry.id, { start_month: event.target.value })} /></label>
            <label>Окончание <span className="candidate-optional">Пусто, если продолжается</span><input type="month" value={entry.end_month ?? ''} onChange={(event) => updateExperience(entry.id, { end_month: event.target.value || null })} /></label>
            <label className="candidate-span-all">Описание<textarea maxLength={2000} rows={3} value={entry.description} onChange={(event) => updateExperience(entry.id, { description: event.target.value })} /></label>
            <SkillPicker label="Навыки по вашему описанию" value={entry.skill_ids} onChange={(skill_ids) => updateExperience(entry.id, { skill_ids })} />
          </div>
        </fieldset>)}
        {projects.map((entry, index) => <fieldset className="candidate-evidence-entry" key={entry.id}>
          <legend>Проект {index + 1}</legend><button className="candidate-remove-button" type="button" onClick={() => { setProjects((current) => current.filter((value) => value.id !== entry.id)); setDirty(true); }}>Удалить</button>
          <div className="candidate-evidence-grid">
            <label>Название проекта<input required maxLength={160} value={entry.title} onChange={(event) => updateProject(entry.id, { title: event.target.value })} /></label>
            <label>Ссылка на проект <span className="candidate-optional">Только HTTPS, необязательно</span><input type="url" inputMode="url" pattern="https://.*" title="Укажите публичную ссылку, начинающуюся с https://" maxLength={2048} placeholder="https://…" value={entry.url ?? ''} onChange={(event) => updateProject(entry.id, { url: event.target.value || null })} /></label>
            <label className="candidate-span-all">Описание<textarea maxLength={2000} rows={3} value={entry.description} onChange={(event) => updateProject(entry.id, { description: event.target.value })} /></label>
            <SkillPicker label="Навыки по вашему описанию" value={entry.skill_ids} onChange={(skill_ids) => updateProject(entry.id, { skill_ids })} />
          </div>
        </fieldset>)}
      </div>
      <div className="candidate-evidence-actions">
        <button className="button button-secondary" type="button" disabled={experience.length >= 10} onClick={() => { setExperience((current) => [...current, { id: crypto.randomUUID(), company: null, role: '', description: '', start_month: '', end_month: null, skill_ids: [] }]); setDirty(true); setMessage(''); }}>Добавить опыт {experience.length}/10</button>
        <button className="button button-secondary" type="button" disabled={projects.length >= 10} onClick={() => { setProjects((current) => [...current, { id: crypto.randomUUID(), title: '', description: '', url: null, skill_ids: [] }]); setDirty(true); setMessage(''); }}>Добавить проект {projects.length}/10</button>
        <button className="button button-primary" type="submit" disabled={saving || !dirty}>{saving ? 'Сохраняем…' : 'Сохранить опыт и проекты'}</button>
      </div>
      <p className="candidate-privacy-copy">Ссылку на публичный проект вы добровольно открываете за пределами платформы. Не размещайте здесь личные контакты.</p>
    </form>
  </section>;
}

function SkillPicker({ label, value, onChange }: { label: string; value: Skill[]; onChange: (value: Skill[]) => void }) {
  return <fieldset className="candidate-skill-picker candidate-span-all"><legend>{label}</legend><div>{skills.map((skill) => <label key={skill}><input type="checkbox" checked={value.includes(skill)} onChange={(event) => onChange(event.target.checked ? [...value, skill] : value.filter((item) => item !== skill))} />{skillLabels[skill]}</label>)}</div></fieldset>;
}

export function PublicProfilePreview() {
  const preview = useQuery({ queryKey: ['candidatePublicPreview'], queryFn: () => api.get<Schemas['PublicProfilePreview']>('/me/public-preview'), staleTime: 0 });
  if (preview.isPending) return <section className="card candidate-preview"><p role="status">Загружаем превью профиля…</p></section>;
  if (preview.isError || !preview.data) return <section className="card candidate-preview"><h2>Предпросмотр профиля</h2><p className="candidate-inline-error" role="alert">{errorMessage(preview.error)}</p><button className="button button-secondary" type="button" onClick={() => void preview.refetch()}>Повторить</button></section>;
  const { candidate } = preview.data;
  return <section className="card candidate-preview" aria-labelledby="candidate-preview-title">
    <div className="candidate-section-heading"><div><span className="candidate-eyebrow">ВИД СО СТОРОНЫ КОМПАНИИ</span><h2 id="candidate-preview-title">Предпросмотр вашего профиля</h2></div><span className={`candidate-visibility-chip ${preview.data.visibility === 'public' ? 'is-public' : ''}`}>{preview.data.visibility === 'public' ? 'Сейчас открыт' : 'Сейчас скрыт'}</span></div>
    <p className="candidate-preview-notice" role="note">Это превью вашей будущей публичной карточки. Просмотр ничего не публикует и не меняет видимость.</p>
    <article className="candidate-public-card">
      <div className="candidate-public-head"><span className="candidate-avatar" aria-hidden="true">{candidate.display_name.slice(0, 1) || 'К'}</span><div><h3>{candidate.display_name || 'Имя не указано'}</h3><p>{candidate.headline || 'Заголовок пока не указан'}</p></div></div>
      {candidate.summary && <p className="candidate-public-summary">{candidate.summary}</p>}
      <div className="candidate-public-meta"><span>Заявленный уровень: {candidate.declared_grade ? grades[candidate.declared_grade] : "не указан"}</span>{candidate.verified_grade && <span>Оценка платформы: {grades[candidate.verified_grade]}</span>}<span>{candidate.allowed_work_modes.map((mode) => workModeLabels[mode] ?? mode).join(' · ')}</span><span>{candidate.max_weekly_hours} ч/нед.</span></div>
      <div className="candidate-preview-skills"><strong>Навыки из профиля · заявлены кандидатом</strong><div>{candidate.skills.map((skill) => <span key={skill.skill_id}>{skillLabels[skill.skill_id as Skill] ?? skill.skill_id}</span>)}</div></div>
      {candidate.experience_entries.length > 0 && <div className="candidate-preview-list"><h4>Опыт</h4>{candidate.experience_entries.map((entry) => <div key={entry.id}><strong>{entry.role}{entry.company ? ` · ${entry.company}` : ''}</strong><p>{entry.description}</p></div>)}</div>}
      {candidate.project_entries.length > 0 && <div className="candidate-preview-list"><h4>Проекты · заявленные сведения</h4>{candidate.project_entries.map((entry) => { const url = safeHttpsUrl(entry.url); return <div key={entry.id}><strong>{entry.title}</strong><p>{entry.description}</p>{url && <a href={url} target="_blank" rel="noopener noreferrer">Открыть ссылку в новой вкладке ↗</a>}</div>; })}</div>}
      <p className="candidate-preview-contact-note">Телефон и email не входят в публичную карточку.</p>
    </article>
  </section>;
}

export function ContactAccessJournal() {
  const [cursor, setCursor] = useState<string | null>(null);
  const [pages, setPages] = useState<Schemas['ContactAccessEventPage'][]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const sequence = useRef(0);
  const load = async (next: string | null, reset = false) => {
    const requestId = ++sequence.current; setLoading(true); setError('');
    try {
      const suffix = next ? `&cursor=${encodeURIComponent(next)}` : '';
      const page = await api.get<Schemas['ContactAccessEventPage']>(`/me/contact-access-events?limit=20${suffix}`);
      if (sequence.current !== requestId) return;
      setPages((current) => reset ? [page] : [...current, page]); setCursor(page.next_cursor);
    } catch (cause) { if (sequence.current === requestId) setError(errorMessage(cause)); }
    finally { if (sequence.current === requestId) setLoading(false); }
  };
  useEffect(() => { void load(null, true); return () => { sequence.current += 1; }; }, []);
  const events = pages.flatMap((page) => page.items);
  return <section className="card candidate-access-journal" aria-labelledby="candidate-access-title">
    <div className="candidate-section-heading"><div><span className="candidate-eyebrow">ПРИВАТНОСТЬ</span><h2 id="candidate-access-title">История доступа к контактам</h2></div></div>
    <p className="candidate-section-intro">Здесь показаны успешные открытия разрешённых контактов и выгрузки PDF компаниями. Запись не содержит ваши контактные значения и не означает, что доступ активен сейчас.</p>
    {error && <div className="candidate-journal-error"><p className="candidate-inline-error" role="alert">{error}</p><button className="button button-secondary button-small" type="button" disabled={loading} onClick={() => void load(null, true)}>Повторить</button></div>}
    {events.length === 0 && !loading && !error ? <p className="candidate-empty-state">Событий доступа пока нет.</p> : <ul className="candidate-access-list">{events.map((event) => <li key={event.id}><span className="candidate-access-icon" aria-hidden="true">{event.kind === 'contact_view' ? '⌁' : '↓'}</span><div><strong>{event.company_name}</strong><small>{event.kind === 'contact_view' ? 'Открыла разрешённые контакты' : 'Скачала PDF профиля'} · {dateLabel(event.occurred_at)}</small></div><span className="candidate-access-status">Событие доступа</span></li>)}</ul>}
    <div className="candidate-access-footer"><span>Отзыв разрешения остановит будущий доступ, но не удалит уже скопированные сведения.</span><button className="button button-secondary button-small" type="button" disabled={loading || !cursor} onClick={() => void load(cursor)}>{loading ? 'Загружаем…' : cursor ? 'Показать ещё' : 'Все события загружены'}</button></div>
    {loading && <p role="status" className="candidate-visually-hidden">Загружаем историю доступа</p>}
  </section>;
}

export function AssessmentReviewDialog({ open, answered, total = 24, savedCount, saving, error, onCancel, onConfirm, deadline, hasUnsavedChanges = false }: {
  open: boolean;
  answered: number;
  total?: number;
  savedCount: number;
  saving: boolean;
  error: string;
  onCancel: () => void;
  onConfirm: () => void;
  deadline: string;
  hasUnsavedChanges?: boolean;
}) {
  const dialog = useRef<HTMLDivElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);
  const confirmedAllowed = !saving && !error && !hasUnsavedChanges && savedCount >= answered;
  useEffect(() => {
    if (!open) {
      previouslyFocused.current?.focus(); previouslyFocused.current = null;
      return;
    }
    previouslyFocused.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const first = dialog.current?.querySelector<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
    first?.focus();
    return () => previouslyFocused.current?.focus();
  }, [open]);
  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') { event.preventDefault(); onCancel(); return; }
    if (event.key !== 'Tab') return;
    const focusable = dialog.current?.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
    if (!focusable?.length) return;
    const first = focusable[0]; const last = focusable[focusable.length - 1];
    if (!dialog.current?.contains(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
    else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  };
  if (!open) return null;
  return <div className="candidate-dialog-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onCancel(); }}>
    <div ref={dialog} className="candidate-review-dialog" role="dialog" aria-modal="true" aria-labelledby="candidate-review-title" aria-describedby="candidate-review-description" onKeyDown={onKeyDown}>
      <span className="candidate-eyebrow">ПЕРЕД ОТПРАВКОЙ</span><h2 id="candidate-review-title">Проверить ответы</h2>
      <p id="candidate-review-description">После отправки изменить эту попытку нельзя. Оценка будет рассчитана по сохранённым ответам.</p>
      <dl className="candidate-review-counts"><div><dt>Ответов дано</dt><dd>{answered} из {total}</dd></div><div><dt>Сохранено на сервере</dt><dd>{savedCount} из {total}</dd></div><div><dt>Срок попытки</dt><dd>{dateLabel(deadline)}</dd></div></dl>
      {total - answered > 0 && <p className="candidate-review-warning" role="note">Без ответа: {total - answered}. Такие пункты получат 0 баллов.</p>}
      {saving && <p className="candidate-inline-info" role="status">Сохраняем последний черновик перед отправкой…</p>}
      {error && <p className="candidate-inline-error" role="alert">{error}</p>}
      <div className="candidate-dialog-actions"><button className="button button-secondary" type="button" onClick={onCancel}>Вернуться к ответам</button><button className="button button-primary" type="button" disabled={!confirmedAllowed} onClick={onConfirm}>{saving ? 'Сохраняем…' : 'Отправить ответы'}</button></div>
    </div>
  </div>;
}

const blockThresholds: Record<string, number> = { 'python-basics': 3, 'http-api': 3, 'sql-transactions': 3, reliability: 3 };

export function ResultCompetencyFeedback({ attempt, eligibility }: { attempt: Attempt; eligibility?: Schemas['AssessmentEligibility'] }) {
  const result = attempt.result;
  if (!result) return <section className="card candidate-result-feedback"><h2>Результат пока недоступен</h2><p>Сервер ещё не вернул итог по этой попытке.</p></section>;
  const passedTotal = result.total_score >= 18;
  return <section className="card candidate-result-feedback" aria-labelledby="candidate-result-title">
    <div className="candidate-section-heading"><div><span className="candidate-eyebrow">ПРОФИЛЬ СВИДЕТЕЛЬСТВ · {attempt.policy_version}</span><h2 id="candidate-result-title">Что показывает результат</h2></div>{attempt.demo && <span className="candidate-demo-marker">Демо-данные</span>}</div>
    <p className="candidate-section-intro">Один результат относится только к форме и версии этой попытки. Баллы из разных форм не сравниваются; это не вероятность успеха в работе.</p>
    <div className="candidate-result-total"><span>Общий порог</span><strong>{result.total_score} / 24</strong><span className={passedTotal ? 'candidate-threshold-met' : 'candidate-threshold-missed'}>{passedTotal ? 'Достигнут · нужно 18' : 'Не достигнут · нужно 18'}</span></div>
    <div className="candidate-result-blocks">{result.block_scores.map((block) => {
      const label = skillLabels[block.block_id as keyof typeof skillLabels] ?? block.block_id;
      const threshold = blockThresholds[block.block_id] ?? 3;
      const met = block.score >= threshold;
      const summary = result.competency_summary.find((item) => item.skill_id === block.block_id);
      return <article key={block.block_id} className="candidate-result-block"><div><strong>{label}</strong><span>{block.score} / 6</span></div><p className={met ? 'candidate-threshold-met' : 'candidate-threshold-missed'}>{met ? `Порог достигнут · минимум ${threshold} из 6` : `Порог не достигнут · минимум ${threshold} из 6`}</p><small>{summary?.summary ?? (summary?.status === 'verified' ? 'Подтверждено в рамках этой оценки.' : 'Порог по блоку не пройден.')}</small></article>;
    })}</div>
    <div className="candidate-next-step"><strong>{result.passed ? 'Следующий шаг' : 'Повторная оценка'}</strong><p>{result.passed ? 'Порог оценки выполнен. Дальнейший уровень и срок определяются сервером по правилам допуска.' : 'До следующей попытки нужно дождаться срока допуска, который определяет сервер.'}</p>{eligibility && <ul>{(['junior', 'middle'] as const).map((grade) => { const item = eligibility[grade]; return <li key={grade}><span>{grades[grade]}:</span> {item.allowed ? 'доступна' : item.eligible_at ? `с ${dateLabel(item.eligible_at)}` : 'пока недоступна'}</li>; })}</ul>}</div>
    <p className="candidate-method-footnote">Специализация Backend Python · уровень для этой попытки: {grades[attempt.requested_grade]} · форма {attempt.form_version} · методика {attempt.rubric_version}. Итог не оценивает интервью или рабочую эффективность.</p>
  </section>;
}
