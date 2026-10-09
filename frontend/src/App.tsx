import Brand from './Brand';
import NavIcon from './NavIcon';
import * as React from 'react';
import { useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, NavLink, Route, Routes, useLocation, useNavigate, useParams } from 'react-router';
import { useInfiniteQuery, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, RequestError, rotateCsrf, skillLabels, type Attempt, type Candidate, type CandidateProfile, type Company, type ContactBundle, type Grade, type Invitation, type MatchCandidate, type Need, type OwnContacts, type Schemas, type Shortlist, type SkillId, type Taxonomy, type User } from './api';
import layoutStyles from './App.module.css';
import { ContactAccessJournal, PracticePage, ProfileEvidenceEditor, PublicProfilePreview, ResultCompetencyFeedback, AssessmentReviewDialog } from './features/candidate';
import { CandidateComparisonPage, CandidateReviewPanel, RecruitingRecordEditor, RecruitingWorkspacePage, RoleTaskEditor, ShortlistManager } from './features/employer/EmployerWorkflow';
import { NeedRoleTasksPanel } from './features/employer/NeedRoleTasksPanel';
import { CaseBankPage, ConsentPanel, type ConsentState } from './features/case/CaseBank';
import { QualificationPortfolio } from './features/candidate/QualificationPortfolio';
import { AddressTasksPage } from './features/extensions/AddressTasksPage';
import { ConversationPage } from './features/extensions/ConversationPage';
import { HiringPlanEditor, HiringPlanView } from './features/hiring/HiringPlan';
import { readHiringPlan } from './features/hiring/hiring-plan';
import { isContactResponseCurrent, readContactIfAuthorized, reconcileAnswerDraft, runBoundedSave, selectedAnswerCount, snapshotMatches } from './features/recovery';
import './features/case/case.css';
import { candidateNextStep, isProfileDiscoverable, pendingInvitationCount } from './features/journey';

type Group<T> = { items: T[]; page?: { next_cursor: string | null; limit: number } };
type MatchResult = Schemas['MatchResults'];
const special = 'backend-python-v1';
const policyId = 'backend-python-policy-v1';
const grades: Grade[] = ['junior', 'middle'];
const gradeName = (g?: string | null) => g === 'middle' ? 'Middle' : g === 'junior' ? 'Junior' : 'Не указан';
const workModeName = (mode: string) => mode === 'remote' ? 'Удалённо' : mode === 'hybrid' ? 'Гибрид' : mode === 'office' ? 'Офис' : mode;
const money = (kopecks: number) => new Intl.NumberFormat('ru-RU').format(Math.round(kopecks / 100));
const date = (value?: string | null) => value ? new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
const titleCase = (value: string) => value.replaceAll('_', ' ');
const formValue = (form: FormData, key: string) => String(form.get(key) ?? '');
const csvValues = (value: string) => value.split(',').map((item) => item.trim()).filter(Boolean).slice(0, 10);
const currentSkillIds = new Set<string>(['python-basics', 'http-api', 'sql-transactions', 'reliability', 'postgres', 'docker', 'fastapi'] satisfies SkillId[]);
const isCurrentSkillId = (value: string): value is SkillId => currentSkillIds.has(value);

function useAttemptHistory() {
  return useInfiniteQuery({
    queryKey: ['attempts'],
    queryFn: ({ pageParam }) => api.get<Schemas['AttemptHistory']>(`/me/attempts?limit=20${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.next_cursor ?? undefined,
  });
}

function useCandidateInvitations() {
  return useInfiniteQuery({
    queryKey: ['candidateInvitations', 'pages'],
    queryFn: ({ pageParam }) => api.get<Group<Invitation>>(`/me/invitations?limit=20${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage) => lastPage.page?.next_cursor ?? undefined,
  });
}

function Notice({ children, tone = 'info' }: { children: ReactNode; tone?: 'info' | 'success' | 'danger' }) {
  return <div className={`notice notice-${tone}`} role="status">{children}</div>;
}

function ErrorBlock({ error, retry }: { error: unknown; retry?: () => void }) {
  return <div className="error-box" role="alert"><span>{errorMessage(error)}</span>{retry && <button className="button button-secondary button-small" onClick={retry}>Повторить</button>}</div>;
}

function Loading({ label = 'Загружаем данные' }: { label?: string }) { return <div className="loading"><span className="spinner" aria-hidden="true" />{label}…</div>; }

function Empty({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return <div className="empty"><div className="empty-icon">✳</div><h3>{title}</h3><p>{detail}</p>{action}</div>;
}

function App() {
  const location = useLocation();
  const isPractice = location.pathname === '/practice';
  const me = useQuery({ queryKey: ['me'], queryFn: () => api.get<User>('/auth/me'), enabled: !isPractice });
  if (isPractice) return <PracticePage />;
  if (me.isPending) return <div className="center-screen"><Loading /></div>;
  if (location.pathname === '/confirm-email' && (!me.isError || (me.error instanceof RequestError && me.error.status === 401))) {
    return <ConfirmTokenPage authenticated={me.isSuccess} />;
  }
  const unauthenticated = me.isError;
  if (unauthenticated) {
    if (!(me.error instanceof RequestError) || me.error.status !== 401) return <div className="center-screen"><div className="auth-card"><PageHeader eyebrow="СВЯЗЬ С СЕРВЕРОМ" title="Кабинет временно недоступен" description={errorMessage(me.error)} /><button className="button button-primary" onClick={() => void me.refetch()}>Повторить запрос</button></div></div>;
    return <AuthPage />;
  }
  if (!me.data.email_verified) return <VerifyPage user={me.data} />;
  return <Workspace user={me.data} />;
}

function AuthPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const query = useQueryClient();
  const [mode, setMode] = useState<'login' | 'register'>(location.pathname === '/register' ? 'register' : 'login');
  const [role, setRole] = useState<'candidate' | 'employer'>('candidate');
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError(''); setSuccess('');
    const form = new FormData(event.currentTarget);
    try {
      if (mode === 'login') {
        await api.post('/auth/login', { email: formValue(form, 'email'), password: formValue(form, 'password') });
        rotateCsrf(); await query.invalidateQueries({ queryKey: ['me'] });
        navigate('/');
      } else {
        const displayName = String(form.get('display_name') ?? '').trim();
        await api.post('/auth/register', { email: formValue(form, 'email'), password: formValue(form, 'password'), account_type: role, display_name: displayName, company_name: role === 'employer' ? formValue(form, 'company_name') : null });
        rotateCsrf(); await query.invalidateQueries({ queryKey: ['me'] });
        setSuccess('Аккаунт создан. Подтвердите email по ссылке из письма, затем войдите.');
        setMode('login');
      }
    } catch (e) { setError(errorMessage(e)); }
  };
  return <main className="auth-page">
    <section className="auth-aside"><Brand variant="full" tone="light" /><div className="auth-pitch"><div className="eyebrow">ПЛАТФОРМА ПРОФЕССИОНАЛЬНОГО ПОДБОРА</div><h1>Работа начинается<br />с ясного совпадения.</h1><p>Профиль, проверка компетенций и понятные условия предложения — в одном рабочем пространстве.</p><div className="trust-row"><span>01 · Данные с происхождением</span><span>02 · Оценка по методике v1</span></div></div><div className="auth-foot">Демо использует синтетические данные</div></section>
    <section className="auth-main"><div className="auth-card"><div className="auth-card-top"><span className="eyebrow">{mode === 'login' ? 'С ВОЗВРАЩЕНИЕМ' : 'НОВЫЙ АККАУНТ'}</span><h2>{mode === 'login' ? 'Войти в кабинет' : 'Создать аккаунт'}</h2><p>{mode === 'login' ? 'Продолжите работу с подбором или профилем.' : 'Выберите роль. Её нельзя изменить после регистрации.'}</p></div>
      {error && <ErrorBlock error={error} />}{success && <Notice tone="success">{success}</Notice>}
      <form onSubmit={submit} className="form-stack">
        {mode === 'register' && <><fieldset className="role-picker"><legend>Я регистрируюсь как</legend><label className={role === 'candidate' ? 'selected' : ''}><input type="radio" checked={role === 'candidate'} onChange={() => setRole('candidate')} />Кандидат</label><label className={role === 'employer' ? 'selected' : ''}><input type="radio" checked={role === 'employer'} onChange={() => setRole('employer')} />Работодатель</label></fieldset><Field label="Имя" name="display_name" required autoComplete="name" />{role === 'employer' && <Field label="Компания" name="company_name" required />}</>}
        <Field label="Email" name="email" type="email" required autoComplete="email" />
        <Field label="Пароль" name="password" type="password" required autoComplete={mode === 'login' ? 'current-password' : 'new-password'} minLength={10} />
        {mode === 'register' && <p className="field-hint">Не менее 10 символов. После регистрации нужно подтвердить адрес.</p>}
        <button className="button button-primary button-wide" type="submit">{mode === 'login' ? 'Войти' : 'Зарегистрироваться'} <span>→</span></button>
      </form>
      <div className="auth-switch">{mode === 'login' ? 'Ещё нет аккаунта?' : 'Уже зарегистрированы?'} <button className="text-button" onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(''); setSuccess(''); }}>{mode === 'login' ? 'Создать аккаунт' : 'Войти'}</button></div>
      <p className="field-hint"><Link to="/practice">Попробовать тренировочные вопросы без регистрации →</Link></p>
      <p className="demo-hint">Демо-кандидат и работодатель доступны по выданным команде тестовым учётным данным.</p>
    </div></section>
  </main>;
}

function ConfirmTokenPage({ authenticated }: { authenticated: boolean }) {
  const query = useQueryClient(); const location = useLocation(); const navigate = useNavigate();
  const initialToken = new URLSearchParams(location.search).get('token') ?? '';
  const [error, setError] = useState(''); const [success, setSuccess] = useState('');
  const submit = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); setError(''); setSuccess(''); const token = String(new FormData(event.currentTarget).get('token') ?? '').trim();
    try {
      await api.post('/auth/confirm-email', { token });
      rotateCsrf();
      await query.invalidateQueries({ queryKey: ['me'] });
      if (authenticated) navigate('/');
      else setSuccess('Email подтверждён. Теперь можно войти.');
    }
    catch (e) { setError(errorMessage(e)); }
  };
  return <main className="auth-page verify-layout"><section className="verify-card"><Brand to={authenticated ? '/' : '/login'} /><div className="auth-card-top"><span className="eyebrow">ПОДТВЕРЖДЕНИЕ EMAIL</span><h1>Подтвердите адрес</h1><p>Введите токен из письма или откройте ссылку подтверждения, чтобы завершить регистрацию.</p></div>{error && <ErrorBlock error={error} />}{success && <Notice tone="success">{success}</Notice>}<form className="form-stack" onSubmit={submit}><Field label="Токен подтверждения" name="token" required maxLength={256} defaultValue={initialToken} /><button className="button button-primary">Подтвердить email</button></form><Link className="inline-link resend" to={authenticated ? '/' : '/login'}>{authenticated ? 'Перейти в кабинет →' : 'Перейти ко входу →'}</Link></section></main>;
}

function VerifyPage({ user }: { user: User }) {
  const query = useQueryClient(); const navigate = useNavigate(); const [notice, setNotice] = useState(''); const [error, setError] = useState('');
  const resend = async () => { setError(''); try { await api.post('/auth/resend-confirmation', { email: user.email }); setNotice('Если адрес зарегистрирован, письмо отправлено.'); } catch (e) { setError(errorMessage(e)); } };
  const confirm = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); const token = String(new FormData(event.currentTarget).get('token')); try { await api.post('/auth/confirm-email', { token }); rotateCsrf(); await query.invalidateQueries({ queryKey: ['me'] }); navigate('/'); } catch (e) { setError(errorMessage(e)); } };
  return <main className="auth-page verify-layout"><section className="verify-card"><Brand /><div className="auth-card-top"><span className="eyebrow">ПОДТВЕРЖДЕНИЕ EMAIL</span><h1>Проверьте почту</h1><p>Для {user.email} ещё не подтверждён адрес. Откройте ссылку из письма или введите токен.</p></div>{error && <ErrorBlock error={error} />}{notice && <Notice tone="success">{notice}</Notice>}<form className="form-stack" onSubmit={confirm}><Field label="Токен подтверждения" name="token" required maxLength={256} /><button className="button button-primary">Подтвердить email</button></form><button className="text-button resend" onClick={resend}>Отправить письмо ещё раз</button><button className="text-button resend" onClick={async () => { await api.post('/auth/logout'); rotateCsrf(); await query.invalidateQueries({ queryKey: ['me'] }); }}>Выйти</button></section></main>;
}

function Field({ label, ...props }: React.InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  const id = props.id ?? props.name;
  return <label className="field" htmlFor={id}><span>{label}</span><input {...props} id={id} /></label>;
}

function TextArea({ label, ...props }: React.TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string }) {
  const id = props.id ?? props.name;
  return <label className="field" htmlFor={id}><span>{label}</span><textarea {...props} id={id} /></label>;
}

type WorkspaceIconName = 'overview' | 'profile' | 'visibility' | 'assessment' | 'credentials' | 'tasks' | 'invitations' | 'contacts' | 'company' | 'needs' | 'matches' | 'bank' | 'shortlists' | 'recruiting';
type WorkspaceLink = { to: string; label: string; icon: WorkspaceIconName };

function WorkspaceLinks({ links, onNavigate }: { links: WorkspaceLink[]; onNavigate?: () => void }) {
  return <nav aria-label="Основная навигация" className="side-nav">{links.map(({ to, label, icon }) => <NavLink end={to === '/'} key={to} to={to} aria-label={label} title={label} onClick={onNavigate} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}><span className="nav-icon" aria-hidden="true"><NavIcon name={icon} /></span><span className="nav-label">{label}</span></NavLink>)}</nav>;
}

function Workspace({ user }: { user: User }) {
  const candidate = user.account_type === 'candidate';
  const links: WorkspaceLink[] = candidate ? [
    { to: '/', label: 'Обзор', icon: 'overview' }, { to: '/profile', label: 'Профиль', icon: 'profile' }, { to: '/consents', label: 'Согласия и видимость', icon: 'visibility' }, { to: '/assessment', label: 'Оценка', icon: 'assessment' }, { to: '/qualifications', label: 'Подтверждения', icon: 'credentials' }, { to: '/short-tasks', label: 'Задачи компаний', icon: 'tasks' }, { to: '/invitations', label: 'Приглашения', icon: 'invitations' }, { to: '/contacts', label: 'Доступ к контактам', icon: 'contacts' },
  ] : [
    { to: '/', label: 'Обзор', icon: 'overview' }, { to: '/company', label: 'Компания', icon: 'company' }, { to: '/needs', label: 'Потребности', icon: 'needs' }, { to: '/matches', label: 'Подборка', icon: 'matches' }, { to: '/bank', label: 'Банк кандидатов', icon: 'bank' }, { to: '/shortlists', label: 'Шорт-листы', icon: 'shortlists' }, { to: '/recruiting', label: 'Работа с кандидатами', icon: 'recruiting' }, { to: '/short-tasks', label: 'Адресные задачи', icon: 'tasks' }, { to: '/invitations', label: 'Приглашения', icon: 'invitations' },
  ];
  const query = useQueryClient(); const navigate = useNavigate();
  const logout = async () => { try { await api.post('/auth/logout'); } finally { rotateCsrf(); await query.clear(); navigate('/login'); window.location.reload(); } };
  const mobileMenuRef = useRef<HTMLDetailsElement>(null);
  const closeMobileMenu = () => { if (mobileMenuRef.current) { mobileMenuRef.current.open = false; mobileMenuRef.current.querySelector('summary')?.focus(); } };
  return <div className={`${layoutStyles.shell} workspace`}><aside className="sidebar"><Brand /><div className="sidebar-role">{candidate ? 'КАБИНЕТ КАНДИДАТА' : 'КАБИНЕТ РАБОТОДАТЕЛЯ'}</div><WorkspaceLinks links={links} /><div className="sidebar-bottom"><div className="profile-mini"><span className="avatar">{(candidate ? user.candidate_profile?.display_name : user.company?.name)?.slice(0, 1) ?? 'Ф'}</span><span className="profile-mini-copy"><strong>{candidate ? user.candidate_profile?.display_name : user.company?.name ?? user.email}</strong><small>{user.email}</small></span></div><button className="logout-link" onClick={logout}>Выйти <span aria-hidden="true">↗</span></button></div></aside>
    <main className="main-area"><header className="topbar"><div className="mobile-brand"><Brand /></div><div className="topbar-context"><span className="eyebrow">РАБОЧЕЕ ПРОСТРАНСТВО</span><div className="topbar-title">{candidate ? 'Кандидат' : 'Работодатель'} <span className="topbar-separator">/</span> {new Date().toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })}</div></div><div className="topbar-right"><span className="status-dot" />Аккаунт активен</div></header><details className="mobile-navigation" ref={mobileMenuRef}><summary><span className="nav-icon" aria-hidden="true"><NavIcon name="menu" /></span><span>Разделы кабинета</span></summary><WorkspaceLinks links={links} onNavigate={closeMobileMenu} /><button className="mobile-logout" onClick={() => { closeMobileMenu(); void logout(); }}><span className="nav-icon" aria-hidden="true"><NavIcon name="logout" /></span><span className="nav-label">Выйти из кабинета</span></button></details><div className="content"><Routes>
      {candidate ? <>
        <Route path="/" element={<CandidateHome user={user} />} />
        <Route path="/profile" element={<CandidateProfilePage user={user} />} />
        <Route path="/consents" element={<ConsentSettingsPage />} />
        <Route path="/profile/preview" element={<PublicProfilePreview />} />
        <Route path="/assessment" element={<AssessmentHome user={user} />} />
        <Route path="/assessment/:attemptId" element={<AssessmentRun />} />
        <Route path="/qualifications" element={<QualificationPortfolio />} />
        <Route path="/short-tasks" element={<AddressTasksPage role="candidate" />} />
        <Route path="/invitations" element={<CandidateInvitations />} />
        <Route path="/contacts" element={<CandidateContacts />} />
      </> : <>
        <Route path="/" element={<EmployerHome user={user} />} />
        <Route path="/company" element={<CompanyPage company={user.company} />} />
        <Route path="/needs" element={<NeedsPage />} />
        <Route path="/matches" element={<MatchesPage />} />
        <Route path="/bank" element={<CaseBankPage />} />
        <Route path="/shortlists" element={<ShortlistsPage />} />
        <Route path="/compare" element={<CandidateComparisonPage />} />
        <Route path="/needs/:needId/candidate-review" element={<CandidateReviewRoute />} />
        <Route path="/recruiting" element={<RecruitingWorkspacePage />} />
        <Route path="/invitations" element={<EmployerInvitations user={user} />} />
        <Route path="/short-tasks" element={<AddressTasksPage role="employer" />} />
      </>}
      <Route path="/messages/:invitationId" element={<ConversationPage role={user.account_type} />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes></div></main></div>;
}

function PageHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description?: string; action?: ReactNode }) {
  return <div className="page-header"><div><div className="eyebrow">{eyebrow}</div><h1>{title}</h1>{description && <p>{description}</p>}</div>{action && <div className="page-header-action">{action}</div>}</div>;
}

function Card({ title, meta, children, className = '' }: { title?: string; meta?: ReactNode; children: ReactNode; className?: string }) {
  return <section className={`card ${className}`}>{(title || meta) && <div className="card-head">{title && <h2>{title}</h2>}{meta}</div>}{children}</section>;
}

function Badge({ children, tone = '' }: { children: ReactNode; tone?: string }) { return <span className={`badge ${tone ? `badge-${tone}` : ''}`}>{children}</span>; }

function CandidateHome({ user }: { user: User }) {
  const profileQuery = useQuery({ queryKey: ['profile'], queryFn: () => api.get<CandidateProfile>('/me/profile') });
  const consentsQuery = useQuery({ queryKey: ['consents'], queryFn: () => api.get<ConsentState>('/me/consents') });
  const invitesQuery = useCandidateInvitations();
  const attemptsQuery = useAttemptHistory();
  const profile = profileQuery.data ?? user.candidate_profile;
  if (profileQuery.isPending || consentsQuery.isPending) return <Loading />;
  if (profileQuery.isError || consentsQuery.isError) return <ErrorBlock error={profileQuery.error ?? consentsQuery.error} retry={() => { void profileQuery.refetch(); void consentsQuery.refetch(); }} />;
  const processingAccepted = consentsQuery.data.states.some((consent) => consent.scope === 'processing' && consent.accepted);
  const pending = invitesQuery.isSuccess ? pendingInvitationCount(invitesQuery.data.pages) : null;
  const pendingLabel = pending === null ? '—' : `${invitesQuery.hasNextPage ? '≥' : ''}${pending}`;
  const publicationAccepted = consentsQuery.data.states.some((consent) => consent.scope === 'publication' && consent.accepted);
  const discoverable = isProfileDiscoverable({ visibility: profile?.visibility ?? 'hidden', profileComplete: profile?.profile_complete ?? false, processingAccepted, publicationAccepted });
  const next = candidateNextStep({ activeAttemptId: profile?.active_attempt_id ?? null, pendingInvitations: pending, processingAccepted, profileComplete: profile?.profile_complete ?? false, verifiedGrade: profile?.verified_grade ?? null, discoverable });
  const recent = attemptsQuery.data?.pages.flatMap((page) => page.items)[0];
  return <><PageHeader eyebrow="ВАШ ПРОФИЛЬ НА ПЛАТФОРМЕ" title={`Здравствуйте, ${profile?.display_name || 'коллега'}`} description="Управляйте профилем, подтверждением навыков и предложениями компаний." action={<Link className="button button-primary" to={next.href}>{next.label} <span>→</span></Link>} />
    <div className="stat-grid"><Stat label="Профиль" value={profile?.profile_complete ? 'Заполнен' : 'Нужны данные'} detail={profile?.profile_complete ? 'Основные данные сохранены' : `${profile?.missing_required_fields.length ?? 0} обязательных полей`} tone={profile?.profile_complete ? 'good' : 'warm'} /><Stat label="Подтверждённый уровень" value={gradeName(profile?.verified_grade)} detail="По методике платформы v1" /><Stat label="Приглашения" value={pendingLabel} detail={invitesQuery.hasNextPage ? 'Есть ещё страницы приглашений' : pending === null ? 'Не удалось проверить число приглашений' : 'Ожидают вашего решения'} tone={pending ? 'highlight' : ''} /><Stat label="Видимость" value={discoverable ? 'Открытый профиль' : 'Скрытый профиль'} detail={discoverable ? 'Виден при подборе' : 'Видимость под вашим контролем'} /></div>
    <div className="dashboard-grid"><Card title="Следующий шаг" meta={<span className="card-index">01</span>}><div className="next-action"><span className="next-icon" aria-hidden="true">→</span><div><h3>{next.title}</h3><p>{next.description}</p><Link to={next.href} className="inline-link">{next.label} <span>→</span></Link>{pending !== null && pending > 0 && next.href !== '/invitations' && <p><Link to="/invitations" className="inline-link">Приглашения ждут решения · {pendingLabel} →</Link></p>}</div></div></Card>
      <Card title="Ваши предпочтения" meta={<Link className="quiet-link" to="/profile">Изменить →</Link>}><div className="preference-list"><Preference label="Специализация" value="Backend Python" /><Preference label="Заявленный уровень" value={gradeName(profile?.declared_grade)} /><Preference label="Формат" value={profile?.allowed_work_modes.map(workModeName).join(', ') || 'Не указан'} /><Preference label="Занятость" value={profile?.max_weekly_hours ? `${profile.max_weekly_hours} ч / неделю` : 'Не указана'} /></div></Card>
      <Card title="Последняя оценка" meta={<Link className="quiet-link" to="/assessment">История →</Link>} className="card-full"><div className="recent-assessment">{recent ? <><div className={`grade-token ${recent.passed ? 'grade-success' : ''}`}>{recent.total_score ?? '—'}<small>/24</small></div><div><h3>{gradeName(recent.requested_grade)} по методике платформы v1</h3><p>{recent.passed ? 'Порог выполнен' : recent.passed === false ? 'Порог не достигнут' : 'Оценка не завершена'} · {date(recent.evaluated_at ?? recent.deadline_at)}</p></div><Badge tone={recent.status === 'evaluated' ? 'neutral' : 'warm'}>{titleCase(recent.status)}</Badge></> : <><div className="empty-inline-icon">▤</div><div><h3>Пока нет результатов</h3><p>Можно пройти оценку после заполнения обязательных полей профиля.</p></div><Link className="button button-secondary button-small push-right" to="/assessment">К оценке</Link></>}</div></Card>
    </div>
    {invitesQuery.isError && <ErrorBlock error={invitesQuery.error} retry={() => void invitesQuery.refetch()} />}{attemptsQuery.isError && <ErrorBlock error={attemptsQuery.error} retry={() => void attemptsQuery.refetch()} />}
  </>;
}

function Stat({ label, value, detail, tone = '' }: { label: string; value: string; detail: string; tone?: string }) {
  return <div className="stat-card"><div className="stat-top"><span>{label}</span><span className={`stat-dot ${tone}`} /></div><strong>{value}</strong><small>{detail}</small></div>;
}
function Preference({ label, value }: { label: string; value: string }) { return <div className="preference-row"><span>{label}</span><strong>{value}</strong></div>; }

function ConsentSettingsPage() {
  return <><PageHeader eyebrow="ПРОФИЛЬ И ДАННЫЕ" title="Согласия и видимость" description="Каждое согласие принимается отдельно после просмотра его текста. Здесь можно проверить историю и отозвать согласие." /><Card title="Согласия кандидата"><ConsentPanel /></Card></>;
}

function CandidateProfilePage({ user }: { user: User }) {
  const query = useQueryClient(); const profileQuery = useQuery({ queryKey: ['profile'], queryFn: () => api.get<CandidateProfile>('/me/profile') });
  const contactsQuery = useQuery({ queryKey: ['ownContacts'], queryFn: () => api.get<OwnContacts>('/me/contacts') });
  const consentsQuery = useQuery({ queryKey: ['consents'], queryFn: () => api.get<ConsentState>('/me/consents') });
  const [error, setError] = useState(''); const [saved, setSaved] = useState('');
  const profile = profileQuery.data ?? user.candidate_profile;
  const processingAccepted = consentsQuery.data?.states.some((consent) => consent.scope === 'processing' && consent.accepted) ?? false;
  const publicationAccepted = consentsQuery.data?.states.some((consent) => consent.scope === 'publication' && consent.accepted) ?? false;
  const extendedProfile = profile as (CandidateProfile & { industry?: string | null; role_preferences?: string[]; soft_skills?: string[] }) | null;
  const saveProfile = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!profile) return; setError(''); setSaved('');
    if (!processingAccepted) { setError('Сначала примите согласие на обработку данных.'); return; }
    const form = new FormData(event.currentTarget);
    const workModes = ['remote', 'hybrid', 'office'].filter((v) => form.get(`mode_${v}`) === 'on') as CandidateProfile['allowed_work_modes'];
    const skills = (['python-basics', 'http-api', 'sql-transactions', 'reliability', 'postgres', 'docker', 'fastapi'] as const).filter((v) => form.get(`skill_${v}`) === 'on');
    try {
    await api.patch('/me/profile', { expected_version: profile.version, display_name: formValue(form, 'display_name'), headline: formValue(form, 'headline') || null, summary: formValue(form, 'summary') || null, declared_grade: (formValue(form, 'declared_grade') || null) as CandidateProfile['declared_grade'], allowed_work_modes: workModes, cities: formValue(form, 'cities').split(',').map((x) => x.trim()).filter(Boolean), desired_hours_per_week: formValue(form, 'desired_hours_per_week') ? Number(formValue(form, 'desired_hours_per_week')) : null, max_weekly_hours: Number(formValue(form, 'max_weekly_hours')), skills, industry: formValue(form, 'industry').trim() || null, role_preferences: csvValues(formValue(form, 'role_preferences')), soft_skills: csvValues(formValue(form, 'soft_skills')) });
      await query.invalidateQueries({ queryKey: ['profile'] }); await query.invalidateQueries({ queryKey: ['me'] }); setSaved('Изменения сохранены на сервере.');
    } catch (e) { setError(errorMessage(e)); }
  };
  const visibility = async () => { if (!profile) return; if (profile.visibility !== 'public' && (!processingAccepted || !publicationAccepted)) { setError('Примите согласия на обработку и публикацию перед открытием профиля.'); return; } setError(''); try { await api.patch('/me/visibility', { expected_version: profile.version, visibility: profile.visibility === 'public' ? 'hidden' : 'public' }); await query.invalidateQueries({ queryKey: ['profile'] }); await query.invalidateQueries({ queryKey: ['me'] }); } catch (e) { setError(errorMessage(e)); } };
  const saveContacts = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); if (!contactsQuery.data) return; setError(''); const fd = new FormData(event.currentTarget); const channels = (['email', 'phone', 'website'] as const).map((type) => ({ type, value: String(fd.get(type) ?? '').trim(), enabled: fd.get(`${type}_enabled`) === 'on' })).filter((c) => c.value); try { await api.patch('/me/contacts', { expected_version: contactsQuery.data.version, channels }); await query.invalidateQueries({ queryKey: ['ownContacts'] }); setSaved('Контакты сохранены. Они останутся закрыты до вашего согласия на контакт.'); } catch (e) { setError(errorMessage(e)); } };
  if (profileQuery.isPending || contactsQuery.isPending) return <Loading />;
  if (!profile) return <ErrorBlock error={profileQuery.error ?? 'Профиль не найден'} retry={() => void profileQuery.refetch()} />;
  const contacts = contactsQuery.data;
  return <><PageHeader eyebrow="ПРОФИЛЬ КАНДИДАТА" title="Ваш профессиональный профиль" description="Заявленные навыки и результат оценки показываются раздельно. Контакты открываются только после принятия приглашения." action={<button className="button button-secondary" onClick={() => void api.download('/me/profile.pdf')}>Скачать PDF ↓</button>} />
    {error && <ErrorBlock error={error} />}{saved && <Notice tone="success">{saved}</Notice>}
    <Card title="Согласия и управление видимостью"><ConsentPanel onChanged={() => { void profileQuery.refetch(); }} /></Card>
    <div className="profile-layout"><div className="profile-column"><Card title="Основная информация"><form className="form-grid" onSubmit={saveProfile}><fieldset className="case-gated-fields" disabled={!processingAccepted}>
      <Field label="Имя" name="display_name" required defaultValue={profile.display_name} maxLength={120} /><Field label="Заголовок профиля" name="headline" defaultValue={profile.headline ?? ''} maxLength={160} placeholder="Например, Python-разработчик" />
      <Field label="Индустрия" name="industry" defaultValue={extendedProfile?.industry ?? ''} maxLength={120} placeholder="Например, финтех" />
      <Field className="span-all" label="Желаемые роли (через запятую, до 10)" name="role_preferences" defaultValue={extendedProfile?.role_preferences?.join(', ') ?? ''} maxLength={1000} placeholder="Backend-разработчик, инженер платформы" />
      <Field className="span-all" label="Гибкие навыки (через запятую, до 10)" name="soft_skills" defaultValue={extendedProfile?.soft_skills?.join(', ') ?? ''} maxLength={1000} placeholder="Коммуникация, наставничество" />
      <label className="field"><span>Заявленный уровень</span><select name="declared_grade" defaultValue={profile.declared_grade ?? ''}><option value="">Не указывать</option>{grades.map((g) => <option key={g} value={g}>{gradeName(g)}</option>)}</select></label>
      <TextArea className="span-all" label="О себе" name="summary" defaultValue={profile.summary ?? ''} rows={4} maxLength={2000} placeholder="Опишите опыт и интересные задачи" />
      <div className="field span-all"><span>Режим работы</span><div className="check-grid">{(['remote', 'hybrid', 'office'] as const).map((mode) => <label className="check-item" key={mode}><input type="checkbox" name={`mode_${mode}`} defaultChecked={profile.allowed_work_modes.includes(mode)} />{mode === 'remote' ? 'Удалённо' : mode === 'hybrid' ? 'Гибрид' : 'Офис'}</label>)}</div></div>
      <Field className="span-all" label="Города (через запятую)" name="cities" defaultValue={profile.cities.join(', ')} placeholder="Москва, Санкт-Петербург" />
      <Field label="Желаемые часов в неделю" name="desired_hours_per_week" type="number" min="1" max="40" defaultValue={profile.desired_hours_per_week ?? ''} /><Field label="Максимум часов в неделю" name="max_weekly_hours" type="number" min="1" max="40" required defaultValue={profile.max_weekly_hours ?? ''} />
      <div className="field span-all"><span>Навыки в профиле <small>Отмеченные навыки заявлены вами и не подтверждены тестом</small></span><div className="skill-checks">{Object.entries(skillLabels).map(([key, label]) => <label key={key}><input type="checkbox" name={`skill_${key}`} defaultChecked={profile.skills.some((s) => s.skill_id === key)} />{label}</label>)}</div></div>
      <div className="case-profile-save"><button className="button button-primary" type="submit" disabled={!processingAccepted}>Сохранить профиль</button>{!processingAccepted && <Link to="/consents" className="field-hint">Примите согласие на обработку →</Link>}</div>
      </fieldset>
    </form></Card>
      <Card title="Мои контакты" meta={<Badge tone="neutral">Закрытые по умолчанию</Badge>}>{contacts && <form className="form-stack" onSubmit={saveContacts}>{(['email', 'phone', 'website'] as const).map((type) => { const channel = contacts.channels.find((c) => c.type === type); return <div className="contact-field-row" key={type}><Field label={type === 'email' ? 'Email' : type === 'phone' ? 'Телефон' : 'Сайт'} name={type} type={type === 'email' ? 'email' : 'text'} defaultValue={channel?.value ?? (type === 'email' ? user.email : '')} /><label className="check-item contact-check"><input type="checkbox" name={`${type}_enabled`} defaultChecked={channel?.enabled ?? false} />Доступен</label></div>; })}<p className="field-hint">Включённые каналы становятся доступны компании только после принятия вами приглашения.</p><button className="button button-secondary" type="submit">Сохранить контакты</button></form>}</Card></div>
      <aside className="profile-aside"><Card title="Статус профиля"><div className="profile-status"><span className="status-ring">{profile.profile_complete ? '✓' : '!'}</span><h3>{profile.profile_complete ? 'Профиль заполнен' : 'Нужно завершить профиль'}</h3><p>{profile.profile_complete ? 'Можно включить видимость для компаний.' : `Проверьте обязательные поля: ${profile.missing_required_fields.map(titleCase).join(', ')}`}</p><button className={`button ${profile.visibility === 'public' ? 'button-secondary' : 'button-primary'} button-wide`} disabled={!profile.profile_complete || (profile.visibility !== 'public' && (!processingAccepted || !publicationAccepted))} onClick={() => void visibility()}>{profile.visibility === 'public' ? 'Скрыть профиль' : 'Открыть для подбора'}</button>{profile.visibility !== 'public' && (!processingAccepted || !publicationAccepted) && <Link className="inline-link" to="/consents">Сначала примите оба согласия →</Link>}<small className="privacy-note">Открытая видимость не раскрывает телефон и email.</small><Link className="inline-link" to="/profile/preview">Предпросмотр открытого профиля →</Link></div></Card>
      <Card title="Подтверждённые навыки"><div className="verified-level"><span className="grade-token grade-small">{profile.verified_grade ? gradeName(profile.verified_grade).slice(0, 1) : '—'}</span><div><strong>{gradeName(profile.verified_grade)}</strong><small>по методике платформы v1</small></div></div><p className="field-hint">Только результат обычной оценки подтверждает уровень. Заявленные навыки отдельно отмечены в профиле.</p><Link to={processingAccepted ? '/assessment' : '/consents'} className="inline-link">{processingAccepted ? 'Оценка компетенций →' : 'Примите согласие для оценки →'}</Link></Card>
      <Card title="Свидетельства опыта">{processingAccepted ? <ProfileEvidenceEditor profile={profile} onChanged={(updated) => { query.setQueryData(['profile'], updated); void query.invalidateQueries({ queryKey: ['profile'] }); void query.invalidateQueries({ queryKey: ['me'] }); setSaved('Свидетельства обновлены. Они помечены как заявленные кандидатом.'); }} /> : <p className="field-hint">Примите согласие на обработку, чтобы добавлять свидетельства опыта.</p>}</Card>
      <Card title="Демонстрационные данные"><p className="field-hint">Связь с ФСП в MVP является тестовым сценарием. Она не меняет оценку и не гарантирует точность внешних сведений.</p>{processingAccepted ? <FspLinkCard onChange={() => void query.invalidateQueries({ queryKey: ['profile'] })} /> : <p className="field-hint">Примите согласие на обработку, чтобы изменить связь.</p>}</Card></aside>
    </div>
  </>;
}

function FspLinkCard({ onChange }: { onChange: () => void }) {
  const evidence = useQuery({ queryKey: ['fspEvidence'], queryFn: () => api.get<Schemas['FspLink']>('/me/fsp-evidence') });
  const [message, setMessage] = useState(''); const [error, setError] = useState('');
  const unlink = async () => { try { await api.delete('/me/fsp-link'); setMessage('Тестовая связь отключена.'); onChange(); void evidence.refetch(); } catch (e) { setError(errorMessage(e)); } };
  const link = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); setError(''); try { const fd = new FormData(event.currentTarget); await api.post('/me/fsp-link', { external_id: formValue(fd, 'fsp_id'), binding_method: 'test_fixture' }); setMessage('Тестовая запись связана. Она будет помечена как demo.'); onChange(); void evidence.refetch(); } catch (e) { setError(errorMessage(e)); } };
  if (evidence.isPending) return <Loading />;
  if (evidence.isError) return <ErrorBlock error={evidence.error} retry={() => void evidence.refetch()} />;
  const history = evidence.data;
  return <div className="fsp-panel">
    {error && <ErrorBlock error={error} />}{message && <Notice tone="success">{message}</Notice>}
    {history.binding_status !== 'unlinked' ? <>
      <Badge tone="warm">DEMO · {history.binding_status === 'linked_confirmed' ? 'Связь с тестовой записью' : 'Связь не подтверждена'}</Badge>
      <p className="field-hint">Связанные достижения: {history.evidence.length}. Они не заменяют тест категории и не подтверждают профессиональный грейд.</p>
      {history.evidence.map((entry, index) => <article className="candidate-entry-card" key={`${entry.event_name}-${index}`}>
        <h4>{entry.event_name}</h4>
        <dl className="candidate-qualification-facts">
          <div><dt>Дисциплина</dt><dd>{entry.discipline || 'Не указана источником'}</dd></div>
          <div><dt>Результат</dt><dd>{entry.result}</dd></div>
          <div><dt>Формат</dt><dd>{entry.team_event ? 'Командное событие' : 'Индивидуальное событие'}</dd></div>
          <div><dt>Участие</dt><dd>{entry.participation_status === 'confirmed' ? 'Подтверждено только в demo-реестре' : 'Не подтверждено'}</dd></div>
          <div><dt>Роль</dt><dd>{entry.role || 'Не указана источником'}</dd></div>
          <div><dt>Получено</dt><dd>{date(entry.retrieved_at)}</dd></div>
        </dl>
        {entry.team_event && <p className="field-hint">Командный результат сам по себе не подтверждает личный вклад или конкретные навыки участника.</p>}
        <p className="field-hint">Источник: синтетический реестр ФСП. Это демонстрационные данные.</p>
      </article>)}
      {!history.evidence.length && <p className="field-hint">История пока пуста. Отсутствие достижений не означает низкую квалификацию.</p>}
      <button className="button button-secondary button-small" onClick={unlink}>Отключить связь</button>
    </> : <form className="form-stack" onSubmit={link}>
      <p className="field-hint">История ФСП не подключена. Вы можете пройти оценку и получать приглашения без неё.</p>
      <Field label="ID тестовой записи" name="fsp_id" required maxLength={120} />
      <button className="button button-secondary button-small">Связать demo-запись</button>
    </form>}
  </div>;
}

function AssessmentHome({ user }: { user: User }) {
  const query = useQueryClient(); const navigate = useNavigate();
  const profileQuery = useQuery({ queryKey: ['profile'], queryFn: () => api.get<CandidateProfile>('/me/profile') });
  const consentsQuery = useQuery({ queryKey: ['consents'], queryFn: () => api.get<ConsentState>('/me/consents') });
  const attemptsQuery = useAttemptHistory();
  const taxonomyQuery = useQuery({ queryKey: ['taxonomy'], queryFn: () => api.get<Taxonomy>('/taxonomy') });
  const [grade, setGrade] = useState<Grade>(user.candidate_profile?.verified_grade ? 'middle' : user.candidate_profile?.declared_grade ?? 'junior');
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const profile = profileQuery.data ?? user.candidate_profile;
  const eligibility = profile?.assessment_eligibility;
  const start = async () => {
    if (!profile) return; setBusy(true); setError('');
    try {
      const attempt = await api.post<Attempt>('/attempts', { specialization_id: special, requested_grade: grade, policy_id: policyId, policy_version: '1.0.0' });
      await query.invalidateQueries({ queryKey: ['profile'] }); await query.invalidateQueries({ queryKey: ['attempts'] }); navigate(`/assessment/${attempt.id}`);
    } catch (e) {
      setError(errorMessage(e));
      if (e instanceof Error && 'activeAttemptId' in e && e.activeAttemptId) navigate(`/assessment/${String(e.activeAttemptId)}`);
    } finally { setBusy(false); }
  };
  if (profileQuery.isPending || attemptsQuery.isPending || taxonomyQuery.isPending || consentsQuery.isPending) return <Loading />;
  if (profileQuery.isError || attemptsQuery.isError || taxonomyQuery.isError || consentsQuery.isError) return <ErrorBlock error={profileQuery.error ?? attemptsQuery.error ?? taxonomyQuery.error ?? consentsQuery.error} retry={() => { void profileQuery.refetch(); void attemptsQuery.refetch(); void taxonomyQuery.refetch(); void consentsQuery.refetch(); }} />;
  const processingAccepted = consentsQuery.data.states.some((consent) => consent.scope === 'processing' && consent.accepted);
  const history = attemptsQuery.data.pages.flatMap((page) => page.items);
  const assessmentRelease = taxonomyQuery.data.assessment_release;
  const demoReleaseNotice = assessmentRelease?.status === 'published_demo' || assessmentRelease?.real_grade_release_ready === false;
  return <><PageHeader eyebrow="ОЦЕНКА КОМПЕТЕНЦИЙ" title="Подтвердите уровень знаний" description="Обычная оценка состоит из 12 заданий по четырём блокам. Ответы сохраняются на сервере; срок — 30 минут." />
    {demoReleaseNotice && <Notice>Текущий банк предназначен для демонстрации. Человеческая проверка квалификации ещё не проведена; результат демо-оценки не подтверждает профессиональный грейд.</Notice>}
    {error && <ErrorBlock error={error} />}
    <div className="assessment-layout"><Card title="Новая оценка" className="assessment-start">{!processingAccepted && <div className="notice notice-warm">Для новой оценки примите согласие на обработку данных. <Link to="/consents">Открыть условия →</Link></div>}<div className="assessment-callout"><span>30</span><div><strong>минут на прохождение</strong><small>12 заданий · 24 ответа · 4 блока</small></div></div><label className="field"><span>Уровень оценки</span><select value={grade} onChange={(e) => setGrade(e.target.value as Grade)}>{grades.map((g) => <option key={g} value={g}>{gradeName(g)}</option>)}</select></label><div className="eligibility"><div><span className="eligibility-indicator" /> {eligibility?.[grade].allowed ? 'Можно начать сейчас' : eligibility?.[grade].reason === 'attempt_active' ? 'Уже есть активная попытка' : 'Пока недоступно'}</div>{!eligibility?.[grade].allowed && eligibility?.[grade].eligible_at && <small>Повтор доступен {date(eligibility[grade].eligible_at)}.</small>}{processingAccepted && !eligibility?.[grade].allowed && eligibility?.[grade].reason === 'attempt_active' && profile?.active_attempt_id && <Link to={`/assessment/${profile.active_attempt_id}`}>Продолжить попытку →</Link>}</div><button className="button button-primary button-wide" onClick={() => void start()} disabled={busy || !processingAccepted || !profile?.profile_complete || !eligibility?.[grade].allowed}>{busy ? 'Подготавливаем…' : 'Начать оценку'} <span>→</span></button>{!profile?.profile_complete && <p className="field-hint">Сначала заполните обязательные поля профиля.</p>}<p className="field-hint">Если профиль синтетический, сервер отметит результат как demo. Порог v1: 18/24 и не менее 3/6 в каждом блоке.</p><p className="field-hint"><Link to="/practice">Сначала посмотреть формат на тренировочных вопросах →</Link></p></Card>
      <div className="assessment-side"><Card title="Состав оценки"><div className="block-list">{(taxonomyQuery.data.skills.filter((s) => ['python-basics', 'http-api', 'sql-transactions', 'reliability'].includes(s.id))).map((skill, i) => <div className="block-row" key={skill.id}><span className="block-number">0{i + 1}</span><div><strong>{skillLabels[skill.id as keyof typeof skillLabels] ?? skill.id}</strong><small>3 задания · 6 баллов</small></div><Badge tone="neutral">Блок</Badge></div>)}</div></Card><Card title="История оценок" meta={<span className="card-index">{history.length}</span>}>{history.length ? <><div className="history-list">{history.map((item) => <div className="history-row" key={item.id}><div><strong>{gradeName(item.requested_grade)}</strong><small>{date(item.evaluated_at ?? item.deadline_at)}</small></div><span>{item.total_score ?? '—'} / 24</span><Badge tone={item.status === 'evaluated' ? (item.passed ? 'good' : 'warm') : item.status === 'in_progress' ? 'neutral' : ''}>{item.status === 'in_progress' ? 'Идёт' : item.status === 'evaluated' ? item.passed ? 'Пройдена' : 'Завершена' : item.status === 'submitted' ? 'На проверке' : 'Аннулирована'}</Badge></div>)}</div>{attemptsQuery.hasNextPage && <button className="button button-secondary button-small load-more" onClick={() => void attemptsQuery.fetchNextPage()} disabled={attemptsQuery.isFetchingNextPage}>{attemptsQuery.isFetchingNextPage ? 'Загружаем…' : 'Показать ещё'}</button>}</> : <Empty title="История пока пуста" detail="Завершённые попытки появятся здесь." />}</Card></div>
    </div>
  </>;
}

function AssessmentRun() {
  const { attemptId = '' } = useParams(); const query = useQueryClient();
  const attemptQuery = useQuery({ queryKey: ['attempt', attemptId], queryFn: () => api.get<Attempt>(`/attempts/${attemptId}`), staleTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false });
  const consentsQuery = useQuery({ queryKey: ['consents'], queryFn: () => api.get<ConsentState>('/me/consents') });
  const processingAccepted = consentsQuery.data?.states.some((consent) => consent.scope === 'processing' && consent.accepted) ?? false;
  const [attempt, setAttempt] = useState<Attempt | undefined>(); const [answers, setAnswers] = useState<Record<string, string>>({});
  const resultProfileQuery = useQuery({ queryKey: ['profile'], queryFn: () => api.get<CandidateProfile>('/me/profile'), enabled: Boolean(attempt && attempt.status !== 'in_progress') });
  const [saveStatus, setSaveStatus] = useState<'saved' | 'dirty' | 'saving' | 'error'>('saved'); const [saveError, setSaveError] = useState(''); const [saveConflict, setSaveConflict] = useState(false); const [submitError, setSubmitError] = useState(''); const [submitting, setSubmitting] = useState(false);
  const [savedAck, setSavedAck] = useState<{ count: number; savedAt: string | null }>({ count: 0, savedAt: null });
  const version = useRef(0); const savedSnapshot = useRef(''); const failedSnapshot = useRef(''); const hydratedAttemptId = useRef(''); const savePromise = useRef<Promise<Attempt | null> | null>(null); const submittingRef = useRef(false); const attemptIdRef = useRef(attemptId); const mountedRef = useRef(true);
  attemptIdRef.current = attemptId;
  useEffect(() => { mountedRef.current = true; return () => { mountedRef.current = false; }; }, []);
  const answerMap = (source: Attempt) => Object.fromEntries(source.saved_answers.filter((answer) => answer.selected_option_id).map((answer) => [`${answer.task_id}:${answer.subquestion_id}`, answer.selected_option_id as string]));
  const acknowledgement = (source: Attempt) => ({ count: selectedAnswerCount(source.saved_answers), savedAt: source.saved_answers.reduce<string | null>((latest, answer) => !latest || answer.saved_at > latest ? answer.saved_at : latest, null) });
  useEffect(() => {
    if (!attemptQuery.data) return;
    const serverAnswers = answerMap(attemptQuery.data);
    const serverSnapshot = JSON.stringify(serverAnswers);
    const reconciled = reconcileAnswerDraft({
      initialized: hydratedAttemptId.current === attemptId,
      draft: answers,
      acknowledged: JSON.parse(savedSnapshot.current || '{}') as Record<string, string>,
      server: serverAnswers,
    });
    hydratedAttemptId.current = attemptId;
    version.current = attemptQuery.data.version;
    savedSnapshot.current = serverSnapshot;
    setAttempt(attemptQuery.data);
    setSavedAck(acknowledgement(attemptQuery.data));
    if (reconciled.shouldAdoptServer) {
      setAnswers(serverAnswers);
      setSaveStatus('saved'); setSaveConflict(false); setSaveError('');
    } else if (reconciled.conflict) {
      setSaveStatus('error'); setSaveConflict(true); setSaveError('Серверный черновик изменился. Загрузите его перед продолжением.');
    } else if (saveStatus !== 'saving') {
      setSaveStatus('dirty');
    }
  }, [attemptQuery.dataUpdatedAt]);
  const fullAnswers = useMemo(() => (attempt?.tasks ?? []).flatMap((task) => task.subquestions.map((q) => ({ task_id: task.task_id, subquestion_id: q.subquestion_id, selected_option_id: answers[`${task.task_id}:${q.subquestion_id}`] ?? null }))), [attempt, answers]);
  const answerString = JSON.stringify(answers);
  const latestAnswerString = useRef(answerString); const latestFullAnswers = useRef(fullAnswers);
  latestAnswerString.current = answerString; latestFullAnswers.current = fullAnswers;
  const performSave = async () => {
    if (savePromise.current) return savePromise.current;
    if (!processingAccepted || !attempt || attempt.status !== 'in_progress' || latestAnswerString.current === savedSnapshot.current) return null;
    setSaveStatus('saving'); setSaveError('');
    const snapshot = latestAnswerString.current; const payload = latestFullAnswers.current; const requestAttemptId = attempt.id;
    const operation = (async () => {
      try {
        const updated = await runBoundedSave({
          save: () => api.put<Attempt>(`/attempts/${requestAttemptId}/answers`, { expected_version: version.current, answers: payload }),
          reconcile: () => api.get<Attempt>(`/attempts/${requestAttemptId}`),
          matches: (current) => current.status !== 'in_progress' || snapshotMatches(answerMap(current), JSON.parse(snapshot) as Record<string, string>),
          isTerminal: (current) => current.status !== 'in_progress',
          wait: (delayMs) => new Promise((resolve) => window.setTimeout(resolve, delayMs)),
        });
        if (!mountedRef.current || attemptIdRef.current !== requestAttemptId) return null;
        version.current = updated.version;
        const acknowledgedAnswers = answerMap(updated);
        savedSnapshot.current = JSON.stringify(acknowledgedAnswers);
        failedSnapshot.current = ''; setSavedAck(acknowledgement(updated)); setAttempt(updated);
        const isCurrentDraft = snapshotMatches(acknowledgedAnswers, JSON.parse(latestAnswerString.current) as Record<string, string>);
        setSaveStatus(isCurrentDraft ? 'saved' : 'dirty'); setSaveError(''); setSaveConflict(false); return updated;
      } catch (e) {
        if (!mountedRef.current || attemptIdRef.current !== requestAttemptId) return null;
        failedSnapshot.current = snapshot; setSaveStatus('error'); setSaveConflict(e instanceof RequestError && e.status === 409); setSaveError(e instanceof RequestError && e.status === 409 ? 'В другой вкладке черновик уже изменили. Вернитесь к вопросам и загрузите серверный черновик, чтобы продолжить.' : errorMessage(e)); return null;
      }
      finally { savePromise.current = null; }
    })();
    savePromise.current = operation;
    return operation;
  };
  const reloadServerDraft = async () => {
    if (!window.confirm('Заменить текущие ответы сохранённым серверным черновиком? Несохранённые изменения в этой вкладке будут потеряны.')) return;
    try { const current = await api.get<Attempt>(`/attempts/${attemptId}`); if (attemptIdRef.current !== attemptId) return; const next = answerMap(current); version.current = current.version; savedSnapshot.current = JSON.stringify(next); setSavedAck(acknowledgement(current)); setAnswers(next); setAttempt(current); setSaveStatus('saved'); setSaveConflict(false); setSaveError(''); }
    catch (e) { setSaveError(errorMessage(e)); }
  };
  useEffect(() => {
    if (!processingAccepted || !attempt || attempt.status !== 'in_progress' || saveStatus === 'saving' || (saveStatus === 'error' && (saveConflict || answerString === failedSnapshot.current)) || answerString === savedSnapshot.current) return;
    setSaveStatus('dirty');
    const timer = window.setTimeout(() => { void performSave(); }, 1000);
    return () => window.clearTimeout(timer);
  }, [answerString, attempt?.id, attempt?.status, processingAccepted, saveStatus, saveConflict]);
  useEffect(() => {
    const recoverWhenOnline = () => {
      if (saveStatus === 'error' && !saveConflict && latestAnswerString.current !== savedSnapshot.current) void performSave();
    };
    window.addEventListener('online', recoverWhenOnline);
    return () => window.removeEventListener('online', recoverWhenOnline);
  }, [saveStatus, saveConflict, attempt?.id, processingAccepted]);
  const [remaining, setRemaining] = useState<number | undefined>();
  useEffect(() => { if (!attempt) return; setRemaining(attempt.remaining_seconds); if (attempt.status !== 'in_progress') return; const end = Date.now() + attempt.remaining_seconds * 1000; const timer = window.setInterval(() => setRemaining(Math.max(0, Math.ceil((end - Date.now()) / 1000))), 1000); return () => window.clearInterval(timer); }, [attempt?.id, attempt?.version]);
  const [reviewOpen, setReviewOpen] = useState(false);
  const submit = async () => {
    if (!processingAccepted || !attempt || submittingRef.current || attempt.status !== 'in_progress') return;
    submittingRef.current = true; setSubmitting(true); setSubmitError('');
    try {
      while (savedSnapshot.current !== latestAnswerString.current) {
        const saved = await performSave();
        if (!saved || savedSnapshot.current !== latestAnswerString.current) return;
      }
      if (savedSnapshot.current !== latestAnswerString.current) return;
      const current = await api.get<Attempt>(`/attempts/${attempt.id}`);
      if (attemptIdRef.current !== attempt.id) return;
      version.current = current.version; setSavedAck(acknowledgement(current)); setAttempt(current);
      if (current.status !== 'in_progress') { setReviewOpen(false); await query.invalidateQueries({ queryKey: ['attempts'] }); await query.invalidateQueries({ queryKey: ['profile'] }); return; }
      if (!snapshotMatches(answerMap(current), JSON.parse(latestAnswerString.current) as Record<string, string>)) {
        setSaveStatus('error'); setSaveConflict(true); setSaveError('Серверный черновик изменился. Загрузите его перед отправкой оценки.'); return;
      }
      savedSnapshot.current = latestAnswerString.current;
      const result = await api.post<Attempt>(`/attempts/${attempt.id}/submit`, { expected_version: version.current }); setAttempt(result); setSavedAck(acknowledgement(result)); setReviewOpen(false); await query.invalidateQueries({ queryKey: ['attempts'] }); await query.invalidateQueries({ queryKey: ['profile'] });
    } catch (e) {
      setSubmitError(errorMessage(e));
      try {
        const current = await api.get<Attempt>(`/attempts/${attempt.id}`);
        if (attemptIdRef.current === attempt.id && current.status !== 'in_progress') { setAttempt(current); setSavedAck(acknowledgement(current)); setReviewOpen(false); setSubmitError(''); await query.invalidateQueries({ queryKey: ['attempts'] }); await query.invalidateQueries({ queryKey: ['profile'] }); }
      } catch { /* Keep the submission error visible until the server can be reached. */ }
    }
    finally { submittingRef.current = false; setSubmitting(false); }
  };
  if (attemptQuery.isPending || !attempt || consentsQuery.isPending) return attemptQuery.isError ? <div><Link className="back-link" to="/assessment">← К оценке</Link><ErrorBlock error={attemptQuery.error} retry={() => void attemptQuery.refetch()} /></div> : <Loading label="Открываем сохранённую попытку" />;
  if (consentsQuery.isError) return <ErrorBlock error={consentsQuery.error} retry={() => void consentsQuery.refetch()} />;
  if (attempt.status === 'in_progress' && !processingAccepted) return <><PageHeader eyebrow="СОГЛАСИЕ ТРЕБУЕТСЯ" title="Оценка приостановлена" description="Чтобы продолжить и сохранить ответы, сначала примите согласие на обработку данных." /><Link className="button button-primary" to="/consents">Открыть условия →</Link></>;
  const minutes = Math.floor((remaining ?? attempt.remaining_seconds) / 60); const seconds = (remaining ?? attempt.remaining_seconds) % 60;
  if (attempt.status !== 'in_progress') return <><PageHeader eyebrow="РЕЗУЛЬТАТ ОЦЕНКИ" title={attempt.result?.grade_awarded ? `Уровень ${gradeName(attempt.result.grade_awarded)}` : attempt.status === 'invalidated' ? 'Попытка аннулирована' : attempt.result ? 'Оценка завершена' : 'Оценка ожидает результата'} description={`Данные этой попытки · ${date(attempt.evaluated_at)}`} />{attempt.demo && <div className="demo-attempt-marker"><Badge tone="warm">DEMO · результат на тестовых данных</Badge></div>}<ResultCompetencyFeedback attempt={attempt} eligibility={resultProfileQuery.data?.assessment_eligibility} /><Link className="button button-primary" to="/assessment">История оценок →</Link></>;
  return <div className="assessment-run"><div className="run-top"><div><Link className="back-link" to="/assessment">← Выйти к оценкам</Link><h1>Оценка · {gradeName(attempt.requested_grade)}</h1><p>Выберите один ответ в каждом пункте. Ответы сохраняются автоматически.</p></div><div className={`timer ${minutes < 5 ? 'timer-warning' : ''}`} aria-label="Оставшееся время"><small>ОСТАЛОСЬ</small><strong>{String(minutes).padStart(2, '0')}:{String(seconds).padStart(2, '0')}</strong></div></div>
    {attempt.demo && <div className="demo-attempt-marker"><Badge tone="warm">DEMO · результат на тестовых данных</Badge></div>}
    <div className="save-line" aria-live="polite">{saveStatus === 'saved' ? '✓ Ответы сохранены' : saveStatus === 'saving' ? 'Сохраняем ответы…' : saveStatus === 'error' ? 'Не удалось сохранить ответы' : 'Есть несохранённые ответы'}{` · сервер подтвердил ${savedAck.count} из 24 · ${savedAck.savedAt ? date(savedAck.savedAt) : 'время пока не указано'}`}{saveStatus === 'error' && (saveConflict ? <button className="text-button" onClick={() => void reloadServerDraft()}>Загрузить серверный черновик</button> : <button className="text-button" onClick={() => void performSave()}>Повторить</button>)}</div>
    {saveError && <Notice tone="danger">{saveError}</Notice>}{submitError && <ErrorBlock error={submitError} />}
    <div className="question-list">{attempt.tasks.map((task, i) => <Card key={task.task_id} title={`Задание ${String(i + 1).padStart(2, '0')}`} meta={<Badge tone="neutral">{skillLabels[task.competency_block as keyof typeof skillLabels] ?? task.competency_block}</Badge>} className="question-card"><p className="task-prompt">{task.prompt}</p>{task.subquestions.map((q) => <fieldset className="question-set" key={q.subquestion_id}><legend>{q.prompt}</legend><div className="option-list">{q.options.map((option, oi) => { const key = `${task.task_id}:${q.subquestion_id}`; return <label className={`option ${answers[key] === option.id ? 'option-selected' : ''}`} key={option.id}><input type="radio" name={key} checked={answers[key] === option.id} onChange={() => setAnswers((old) => ({ ...old, [key]: option.id }))} /><span className="option-key">{String.fromCharCode(65 + oi)}</span><span>{option.text}</span></label>; })}</div></fieldset>)}</Card>)}</div>
    <div className="submit-bar"><span>{Object.keys(answers).length} ответов выбрано из 24</span><button className="button button-primary" disabled={submitting} onClick={() => { setSubmitError(''); setReviewOpen(true); }}>{submitting ? 'Отправляем…' : 'Завершить оценку'} <span>→</span></button></div>
    <AssessmentReviewDialog open={reviewOpen} answered={Object.keys(answers).length} savedCount={savedAck.count} saving={saveStatus === 'saving' || submitting} error={saveError || submitError} hasUnsavedChanges={answerString !== savedSnapshot.current} deadline={attempt.deadline_at} onCancel={() => setReviewOpen(false)} onConfirm={() => void submit()} />
  </div>;
}

function CandidateInvitations() {
  const query = useQueryClient(); const invitations = useCandidateInvitations();
  const [error, setError] = useState(''); const decide = async (invite: Invitation, action: 'accept' | 'decline') => { setError(''); try { await api.post(`/invitations/${invite.id}/decision`, { expected_version: invite.version, action }); await query.invalidateQueries({ queryKey: ['candidateInvitations'] }); await query.invalidateQueries({ queryKey: ['contactGrants'] }); } catch (e) { setError(errorMessage(e)); } };
  if (invitations.isPending) return <Loading />;
  if (invitations.isError && !invitations.data) return <ErrorBlock error={invitations.error} retry={() => void invitations.refetch()} />;
  const items = invitations.data?.pages.flatMap((page) => page.items) ?? [];
  return <><PageHeader eyebrow="ПРЕДЛОЖЕНИЯ КОМПАНИЙ" title="Приглашения" description="До согласия компания не получает ваши контактные данные. Принятие открывает выбранные вами каналы для этой компании." />{error && <ErrorBlock error={error} />}{items.length ? <div className="invitation-list">{items.map((invite) => <CandidateInvitationCard key={invite.id} invitation={invite} onDecision={(action) => void decide(invite, action)} />)}</div> : <Empty title="Приглашений пока нет" detail="Когда компания отправит предложение, вы увидите здесь условия и причины приглашения." />}{invitations.isError && <ErrorBlock error={invitations.error} retry={() => { if (invitations.isFetchNextPageError) void invitations.fetchNextPage(); else void invitations.refetch(); }} />}{invitations.hasNextPage && <button className="button button-secondary button-wide" disabled={invitations.isFetching} onClick={() => void invitations.fetchNextPage()}>{invitations.isFetchingNextPage ? 'Загружаем…' : 'Показать ещё приглашения'}</button>}</>;
}

function CandidateInvitationCard({ invitation, onDecision }: { invitation: Invitation; onDecision: (action: 'accept' | 'decline') => void }) {
  const [expanded, setExpanded] = useState(false);
  const hasCompanySnapshot = Boolean(invitation.company_industry || invitation.company_contact_email || invitation.company_contact_phone);
  return <Card className="invite-card"><div className="invite-top"><div><div className="eyebrow">ПРЕДЛОЖЕНИЕ · {date(invitation.updated_at)}</div><h2>{invitation.company_name}</h2></div><Badge tone={invitation.state === 'pending' ? 'warm' : invitation.state === 'accepted' ? 'good' : 'neutral'}>{invitation.state === 'pending' ? 'Ожидает решения' : invitation.state === 'accepted' ? 'Принято' : titleCase(invitation.state)}</Badge></div><div className="salary-highlight">{money(invitation.salary.min_kopecks)}–{money(invitation.salary.max_kopecks)} ₽ <small>/ месяц · {invitation.salary.basis === 'gross' ? 'до вычета налогов' : 'на руки'}</small></div><p>{invitation.description}</p><HiringPlanView plan={invitation.hiring_plan} />{invitation.state === 'accepted' && <div className="invite-actions"><Link className="button button-secondary button-small" to={`/messages/${invitation.id}`}>Диалог и следующий шаг</Link></div>}<div className="invite-meta"><span>Способ контакта: <strong>{invitation.contact_method === 'platform' ? 'Открытие выбранных контактов' : invitation.contact_method === 'email' ? 'По электронной почте' : 'По телефону'}</strong></span><span>Действует до {date(invitation.expires_at)}</span></div>{hasCompanySnapshot && <div className="invitation-company-snapshot"><strong>О компании на момент приглашения</strong><div className="invitation-company-fields">{invitation.company_industry && <div><small>Индустрия</small><span>{invitation.company_industry}</span></div>}{invitation.company_contact_email && <div><small>Контактный email</small><a href={`mailto:${invitation.company_contact_email}`}>{invitation.company_contact_email}</a></div>}{invitation.company_contact_phone && <div><small>Контактный телефон</small><a href={`tel:${invitation.company_contact_phone}`}>{invitation.company_contact_phone}</a></div>}</div><small className="field-hint">Эти сведения сохранены в предложении и не обновляются при изменении профиля компании.</small></div>}{invitation.company_comment && <div className="company-comment"><small>Комментарий компании</small><p>{invitation.company_comment}</p></div>}{invitation.discussion_topics.length > 0 && <div className="topic-row">{invitation.discussion_topics.map((topic) => <Badge key={topic} tone="neutral">{topic}</Badge>)}</div>}<button className="text-button reason-toggle" onClick={() => setExpanded(!expanded)}>{expanded ? 'Скрыть основания' : `Почему вас пригласили · ${invitation.system_reasons.length}`} {expanded ? '↑' : '↓'}</button>{expanded && <div className="reason-list">{invitation.system_reasons.length ? invitation.system_reasons.map((reason, i) => <div key={`${reason.kind}-${i}`}><span className="reason-check">•</span><span>{reason.text}{reason.demo && <Badge tone="warm">demo</Badge>}</span></div>) : <p className="field-hint">Системные основания не указаны.</p>}</div>}{invitation.state === 'pending' && <div className="invite-actions"><button className="button button-primary" onClick={() => onDecision('accept')}>Согласиться обсудить вакансию и открыть выбранные контакты</button><button className="button button-secondary" onClick={() => onDecision('decline')}>Отказаться</button></div>}{invitation.state === 'accepted' && <Notice tone="success">Вы согласились обсудить вакансию. Это не принятие предложения о работе и не подтверждение трудоустройства. Управляйте разрешениями в разделе «Доступ к контактам».</Notice>}</Card>;
}

function CandidateContacts() {
  const query = useQueryClient(); const grants = useQuery({ queryKey: ['contactGrants'], queryFn: () => api.get<Group<Schemas['ContactBundle']>>('/me/contact-grants') }); const invitations = useQuery({ queryKey: ['candidateInvitations'], queryFn: () => api.get<Group<Invitation>>('/me/invitations') }); const [error, setError] = useState('');
  const revoke = async (id: string) => { setError(''); try { await api.post(`/me/contact-grants/${id}/revoke`); await query.invalidateQueries({ queryKey: ['contactGrants'] }); } catch (e) { setError(errorMessage(e)); } };
  if (grants.isPending) return <Loading />;
  if (grants.isError) return <ErrorBlock error={grants.error} retry={() => void grants.refetch()} />;
  const items = grants.data.items;
  return <><PageHeader eyebrow="ПРИВАТНОСТЬ" title="Доступ к контактам" description="Контактные данные открываются компании после принятия приглашения. Отзыв блокирует новые чтения контактов этой компанией." />{error && <ErrorBlock error={error} />}{items.length ? <div className="grant-list">{items.map((grant) => { const companyName = invitations.data?.items.find((invite) => invite.company_id === grant.company_id)?.company_name; return <Card key={grant.grant_id}><div className="grant-head"><div><h2>{companyName ?? `Компания · ${grant.company_id.slice(0, 8)}`}</h2><p>Доступ открыт {date(grant.granted_at)}</p></div><Badge tone="good">Действует</Badge></div><div className="contact-channels">{grant.channels.map((channel) => <div key={channel.type}><small>{titleCase(channel.type)}</small><strong>{channel.value}</strong></div>)}</div><button className="button button-secondary button-small" onClick={() => void revoke(grant.grant_id)}>Отозвать доступ</button></Card>; })}</div> : <Empty title="Активных разрешений пока нет" detail="После принятия приглашения компания получит доступ только к включённым вами каналам." />}<ContactAccessJournal /></>;
}

function EmployerHome({ user }: { user: User }) {
  const needs = useQuery({ queryKey: ['needs'], queryFn: () => api.get<Group<Need>>('/needs') }); const sent = useQuery({ queryKey: ['sentInvitations'], queryFn: () => api.get<Group<Invitation>>('/invitations/sent') });
  if (needs.isPending || sent.isPending) return <Loading />;
  if (needs.isError || sent.isError) return <ErrorBlock error={needs.error ?? sent.error} retry={() => { void needs.refetch(); void sent.refetch(); }} />;
  const activeNeeds = needs.data.items.filter((n) => n.status === 'active').length; const pending = sent.data.items.filter((i) => i.state === 'pending').length;
  return <><PageHeader eyebrow="ПОДБОР В КОМПАНИИ" title={`Добро пожаловать, ${user.company?.name ?? 'команда'}`} description="Опишите потребность, подтвердите требования и изучайте объяснимую подборку специалистов." action={<Link to="/needs" className="button button-primary">Создать потребность <span>＋</span></Link>} /><div className="stat-grid"><Stat label="Активные потребности" value={String(activeNeeds)} detail={`${needs.data.items.length} всего`} tone="highlight" /><Stat label="В ожидании" value={String(pending)} detail="Приглашения без ответа" tone={pending ? 'warm' : ''} /><Stat label="Компетенции" value="Проверяемые" detail="Навыки по методике платформы v1" /><Stat label="Контакты" value="По согласию" detail="После принятого приглашения" /></div><div className="dashboard-grid"><Card title="Потребности в работе" meta={<Link className="quiet-link" to="/needs">Все потребности →</Link>}>{needs.data.items.filter((n) => n.status !== 'archived').length ? <div className="need-mini-list">{needs.data.items.filter((n) => n.status !== 'archived').slice(0, 4).map((need) => <div className="need-mini" key={need.id}><span className="need-icon">⌕</span><div><strong>{need.title}</strong><small>{gradeName(need.target_grade)} · {need.work_mode === 'remote' ? 'Удалённо' : need.work_mode === 'hybrid' ? 'Гибрид' : 'Офис'} · {need.status === 'draft' ? 'Черновик' : 'Активна'}</small></div><Link to={`/matches?need=${need.id}`} className="quiet-link">Подборка →</Link></div>)}</div> : <Empty title="Добавьте первую потребность" detail="Укажите уровень, формат, зарплатную вилку и обязательные компетенции." action={<Link className="button button-secondary button-small" to="/needs">Создать потребность</Link>} />}</Card><Card title="Быстрый переход"><div className="quick-links"><Link to="/company"><span>01</span><div><strong>Профиль компании</strong><small>Информация, видимая кандидатам</small></div><b>→</b></Link><Link to="/shortlists"><span>02</span><div><strong>Шорт-листы</strong><small>Сохранённые кандидаты на сервере</small></div><b>→</b></Link><Link to="/invitations"><span>03</span><div><strong>Приглашения</strong><small>Условия и ответы кандидатов</small></div><b>→</b></Link></div></Card></div></>;
}

function CompanyPage({ company: initial }: { company: Company | null }) {
  const query = useQueryClient(); const companyQuery = useQuery({ queryKey: ['company'], queryFn: () => api.get<Company>('/me/company') }); const company = companyQuery.data ?? initial;
  const [error, setError] = useState(''); const [saved, setSaved] = useState('');
  const submit = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); if (!company) return; const fd = new FormData(event.currentTarget); setError(''); setSaved(''); try { await api.patch('/me/company', { expected_version: company.version, name: formValue(fd, 'name'), description: formValue(fd, 'description') || null, website: formValue(fd, 'website') || null, industry: formValue(fd, 'industry').trim() || null, contact_email: formValue(fd, 'contact_email').trim() || null, contact_phone: formValue(fd, 'contact_phone').trim() || null }); await query.invalidateQueries({ queryKey: ['company'] }); await query.invalidateQueries({ queryKey: ['me'] }); setSaved('Профиль компании обновлён.'); } catch (e) { setError(errorMessage(e)); } };
  if (companyQuery.isPending) return <Loading />;
  if (companyQuery.isError) return <ErrorBlock error={companyQuery.error} retry={() => void companyQuery.refetch()} />;
  if (!company) return <Empty title="Компания не создана" detail="Для учётной записи работодателя должен быть создан профиль компании." />;
  return <><PageHeader eyebrow="ПРОФИЛЬ РАБОТОДАТЕЛЯ" title="Компания" description="Информация о компании сопровождает приглашения и помогает кандидатам понять контекст предложения." />{error && <ErrorBlock error={error} />}{saved && <Notice tone="success">{saved}</Notice>}<Card title="Согласие на обработку"><ConsentPanel includePublication={false} onChanged={() => { void companyQuery.refetch(); }} /></Card><div className="company-layout"><Card title="Информация компании"><form className="form-stack" onSubmit={submit}><Field label="Название" name="name" defaultValue={company.name} required maxLength={160} /><TextArea label="О компании" name="description" defaultValue={company.description ?? ''} rows={6} maxLength={3000} placeholder="Напишите о команде и задачах" /><Field label="Индустрия" name="industry" defaultValue={company.industry ?? ''} maxLength={120} placeholder="Например, финтех" /><Field label="Сайт" name="website" type="url" defaultValue={company.website ?? ''} placeholder="https://example.ru" /><Field label="Контактный email" name="contact_email" type="email" defaultValue={company.contact_email ?? ''} maxLength={254} /><Field label="Контактный телефон" name="contact_phone" type="tel" defaultValue={company.contact_phone ?? ''} maxLength={80} /><button className="button button-primary">Сохранить изменения</button></form></Card><Card title="Ответственный владелец"><div className="owner-card"><span className="avatar avatar-large">{company.name.slice(0, 1)}</span><div><h3>{company.name}</h3><p>Владелец компании · полный доступ к P0</p><Badge tone="neutral">Компания подтверждена аккаунтом</Badge></div></div><div className="privacy-note-box">Контакты кандидатов выдаются только после их согласия на предложение. Профиль компании не даёт обходного доступа.</div></Card></div></>;
}

function NeedsPage() {
  const query = useQueryClient(); const needs = useQuery({ queryKey: ['needs'], queryFn: () => api.get<Group<Need>>('/needs') }); const taxonomy = useQuery({ queryKey: ['taxonomy'], queryFn: () => api.get<Taxonomy>('/taxonomy') });
  const [showForm, setShowForm] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [confirming, setConfirming] = useState<string | null>(null); const [workMode, setWorkMode] = useState<'remote' | 'hybrid' | 'office'>('remote'); const [requirementSelection, setRequirementSelection] = useState<SkillId[]>([]); const [importanceBySkill, setImportanceBySkill] = useState<Record<string, Schemas['SkillRequirement']['importance']>>({}); const [newRoleTasks, setNewRoleTasks] = useState<Schemas['RoleTask'][]>([]);
  const create = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); const fd = new FormData(event.currentTarget); setError(''); setNotice(''); const hours = Number(fd.get('hours')); const requirementIds = (['python-basics','http-api','sql-transactions','reliability','postgres','docker','fastapi'] as const).filter((id) => fd.get(`req_${id}`) === 'on'); if (!requirementIds.length) { setError('Выберите хотя бы одну компетенцию для потребности.'); return; } const requiredIds = requirementIds.filter((id) => fd.get(`importance_${id}`) === 'required'); if (!requiredIds.length) { setError('Выберите хотя бы одну обязательную компетенцию.'); return; }
    if (newRoleTasks.some((task) => !task.description.trim())) { setError('Заполните описание каждой добавленной задачи или удалите пустую.'); return; }
    const need: Schemas['NeedCreateRequest'] = { title: formValue(fd, 'title'), description: formValue(fd, 'description'), specialization_id: special, target_grade: formValue(fd, 'grade') as Grade, work_mode: formValue(fd, 'mode') as Need['work_mode'], city: formValue(fd, 'city') || null, workload_hours_per_week: hours, salary: { min_kopecks: Math.round(Number(fd.get('salary_min')) * 100), max_kopecks: Math.round(Number(fd.get('salary_max')) * 100), currency: 'RUB', period: 'month', basis: formValue(fd, 'basis') as Schemas['MoneyRange']['basis'], workload_type: hours === 40 ? 'full_time' : 'part_time', hours_per_week: hours }, requirements: requirementIds.map((id) => ({ skill_id: id, importance: formValue(fd, `importance_${id}`) as Schemas['SkillRequirement']['importance'] })), role_tasks: newRoleTasks };
    try { const created = await api.post<Need>('/needs', need); await query.invalidateQueries({ queryKey: ['needs'] }); setShowForm(false); setNotice(`Потребность «${created.title}» создана как черновик. Подтвердите компетенции перед подбором.`); } catch (e) { setError(errorMessage(e)); }
  };
  const confirm = async (need: Need) => { setError(''); try { await api.post(`/needs/${need.id}/competencies/confirm`, { expected_version: need.version, confirmed_skill_ids: need.requirements.map((r) => r.skill_id) }); await query.invalidateQueries({ queryKey: ['needs'] }); setNotice('Компетенции подтверждены. Теперь можно искать кандидатов.'); } catch (e) { setError(errorMessage(e)); } };
  const archive = async (need: Need) => { setError(''); try { await api.patch(`/needs/${need.id}`, { expected_version: need.version, status: 'archived' }); await query.invalidateQueries({ queryKey: ['needs'] }); } catch (e) { setError(errorMessage(e)); } };
  if (needs.isPending || taxonomy.isPending) return <Loading />;
  if (needs.isError || taxonomy.isError) return <ErrorBlock error={needs.error ?? taxonomy.error} retry={() => { void needs.refetch(); void taxonomy.refetch(); }} />;
  const skillOptions = taxonomy.data.skills.filter((skill): skill is { id: SkillId; test_assessed: boolean } => isCurrentSkillId(skill.id));
  return <><PageHeader eyebrow="ОПИСАНИЕ РОЛИ" title="Потребности" description="Подбор начинается после подтверждения обязательных и желательных компетенций. Сервер покажет отдельные группы и причины." action={<button className="button button-primary" onClick={() => setShowForm(!showForm)}>{showForm ? 'Закрыть форму' : '＋ Новая потребность'}</button>} />{error && <ErrorBlock error={error} />}{notice && <Notice tone="success">{notice}</Notice>}{showForm && <Card title="Новая потребность" className="create-form-card"><form onSubmit={create} className="form-grid"><Field className="span-all" label="Название роли" name="title" required maxLength={160} placeholder="Backend Python разработчик" /><TextArea className="span-all" label="Описание задач" name="description" required rows={4} maxLength={5000} /><label className="field"><span>Целевой уровень</span><select name="grade"><option value="junior">Junior</option><option value="middle">Middle</option></select></label><label className="field"><span>Режим работы</span><select name="mode" value={workMode} onChange={(e) => setWorkMode(e.target.value as typeof workMode)}><option value="remote">Удалённо</option><option value="hybrid">Гибрид</option><option value="office">Офис</option></select></label><Field label="Город" name="city" placeholder="Москва" required={workMode !== 'remote'} /><Field label="Часов в неделю" name="hours" type="number" defaultValue="40" min="1" max="40" required /><Field label="Зарплата от, ₽ / месяц" name="salary_min" type="number" min="1" required /><Field label="Зарплата до, ₽ / месяц" name="salary_max" type="number" min="1" required /><label className="field"><span>Указание зарплаты</span><select name="basis"><option value="gross">До вычета налогов</option><option value="net">На руки</option></select></label><div className="field span-all"><span>Компетенции <small>Для поиска нужно минимум одно обязательное требование</small></span><div className="requirement-options">{skillOptions.map((skill) => <div className="requirement-row" key={skill.id}><label><input name={`req_${skill.id}`} type="checkbox" checked={requirementSelection.includes(skill.id)} onChange={(event) => { setRequirementSelection((current) => event.target.checked ? [...new Set([...current, skill.id])] : current.filter((id) => id !== skill.id)); if (!event.target.checked) setNewRoleTasks((current) => current.map((task) => ({ ...task, skill_ids: task.skill_ids.filter((id) => id !== skill.id) })).filter((task) => task.skill_ids.length > 0)); }} />{skillLabels[skill.id as keyof typeof skillLabels] ?? skill.id}</label><select aria-label={`Важность: ${skillLabels[skill.id as keyof typeof skillLabels] ?? skill.id}`} name={`importance_${skill.id}`} value={importanceBySkill[skill.id] ?? 'required'} onChange={(event) => setImportanceBySkill((current) => ({ ...current, [skill.id]: event.target.value as Schemas['SkillRequirement']['importance'] }))}><option value="required">Обязательный</option><option value="preferred">Желательный</option></select>{skill.test_assessed ? <Badge tone="neutral">Оценивается тестом</Badge> : <Badge tone="warm">Самоописание</Badge>}</div>)}</div></div><RoleTaskEditor requirements={requirementSelection.map((skill_id) => ({ skill_id, importance: importanceBySkill[skill_id] ?? 'required' }))} value={newRoleTasks} onChange={setNewRoleTasks} /><div className="form-footer span-all"><p className="field-hint">Заявленное кандидатом не считается подтверждением обязательного навыка. При создании сервер сохранит черновик.</p><button className="button button-primary">Создать черновик</button></div></form></Card>}
    {needs.data.items.length ? <div className="needs-list">{needs.data.items.map((need) => <Card key={need.id} className="need-card"><div className="need-title-row"><div><div className="eyebrow">{gradeName(need.target_grade)} · {need.work_mode === 'remote' ? 'УДАЛЁННО' : need.work_mode === 'hybrid' ? 'ГИБРИД' : 'ОФИС'}</div><h2>{need.title}</h2></div><Badge tone={need.status === 'active' ? 'good' : need.status === 'draft' ? 'warm' : 'neutral'}>{need.status === 'draft' ? 'Черновик' : need.status === 'active' ? 'Активна' : 'В архиве'}</Badge></div><p>{need.description}</p><div className="need-summary"><span>{money(need.salary.min_kopecks)}–{money(need.salary.max_kopecks)} ₽ / месяц · {need.salary.basis === 'gross' ? 'до налогов' : 'на руки'}</span><span>{need.workload_hours_per_week} ч/нед</span><span>{need.city || 'Город не указан'}</span></div><div className="need-skills">{need.requirements.map((r) => <Badge key={r.skill_id} tone={r.importance === 'required' ? 'neutral' : 'warm'}>{skillLabels[r.skill_id as keyof typeof skillLabels] ?? r.skill_id} · {r.importance === 'required' ? 'обязательно' : 'желательно'}</Badge>)}</div><NeedRoleTasksPanel key={`${need.id}:${need.version}`} need={need} onSaved={(updated) => { query.setQueryData<Group<Need>>(['needs'], (current) => current ? { ...current, items: current.items.map((entry) => entry.id === updated.id ? updated : entry) } : current); void query.invalidateQueries({ queryKey: ['needs'] }); void query.invalidateQueries({ queryKey: ['need', need.id] }); setNotice('Задачи обновлены. Потребность переведена в черновик до повторного подтверждения компетенций.'); }} />{!need.competencies_confirmed_at && need.status === 'draft' && <Notice>Подтвердите требования: вы проверили уровень и отметили обязательные компетенции.</Notice>}<div className="card-actions">{!need.competencies_confirmed_at && need.status === 'draft' && <button className="button button-primary button-small" onClick={() => { setConfirming(need.id); void confirm(need).finally(() => setConfirming(null)); }} disabled={confirming === need.id}>{confirming === need.id ? 'Подтверждаем…' : 'Подтвердить компетенции'}</button>}{need.status === 'active' && <Link className="button button-primary button-small" to={`/matches?need=${need.id}`}>Открыть подборку →</Link>}{need.status !== 'archived' && <button className="button button-secondary button-small" onClick={() => void archive(need)}>В архив</button>}</div></Card>)}</div> : <Empty title="Потребностей пока нет" detail="Создайте первую роль, для которой хотите найти специалиста." />}</>;
}

function MatchesPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const needs = useQuery({ queryKey: ['needs'], queryFn: () => api.get<Group<Need>>('/needs') });
  const [result, setResult] = useState<MatchResult | undefined>(); const [selected, setSelected] = useState<string[]>([]); const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [strict, setStrict] = useState(searchParams.get('strict') === 'true');
  const needId = searchParams.get('need') ?? '';
  const search = async (id = needId, strictOnly = strict, cursor?: string | null) => { if (!id) { setError('Сначала выберите потребность.'); return; } setError(''); setNotice(''); try { const response = await api.post<MatchResult>('/matches/search', { need_id: id, limit: 20, cursor: cursor ?? null, strict_confirmed_only: strictOnly }); setResult(response); if (!cursor) setSelected([]); } catch (e) { setError(errorMessage(e)); } };
  const pickNeed = (id: string) => { setSearchParams((old) => { const next = new URLSearchParams(old); if (id) next.set('need', id); else next.delete('need'); return next; }); };
  const toggleStrict = (on: boolean) => { setStrict(on); setSearchParams((old) => { const next = new URLSearchParams(old); if (on) next.set('strict', 'true'); else next.delete('strict'); return next; }); };
  useEffect(() => { if (needId && needs.data?.items.some((n) => n.id === needId && n.status === 'active')) void search(needId, strict); }, [needId, strict, needs.dataUpdatedAt]);
  const createShortlist = async () => { if (!result || selected.length === 0) return; const need = needs.data?.items.find((n) => n.id === result.need_id); if (!need) return; setError(''); try { const list = await api.post<Shortlist>('/shortlists', { name: `Подборка · ${need.title}`, need_id: need.id, query_snapshot: { specialization_id: special, target_grade: need.target_grade, strict_confirmed_only: result.strict_confirmed_only }, candidate_ids: selected }); setNotice(`Сохранено на сервере в «${list.name}».`); setSelected([]); } catch (e) { setError(errorMessage(e)); } };
  const toggleSelected = (candidateId: string) => setSelected((old) => {
    if (old.includes(candidateId)) return old.filter((id) => id !== candidateId);
    if (old.length >= 3) { setError('Для сравнения выберите не более трёх кандидатов. Сохранить можно любое число.'); return old; }
    setError(''); return [...old, candidateId];
  });
  if (needs.isPending) return <Loading />;
  if (needs.isError) return <ErrorBlock error={needs.error} retry={() => void needs.refetch()} />;
  const activeNeeds = needs.data.items.filter((n) => n.status === 'active');
  return <><PageHeader eyebrow="ОБЪЯСНИМЫЙ ПОДБОР" title="Подборка специалистов" description="Порядок групп и основания рассчитывает сервер. Совпадение ФСП не заменяет требуемые подтверждённые навыки." />{error && <ErrorBlock error={error} />}{notice && <Notice tone="success">{notice}</Notice>}<Card title="Параметры поиска" className="search-controls"><div className="search-control-row"><label className="field"><span>Активная потребность</span><select value={needId} onChange={(e) => pickNeed(e.target.value)}><option value="">Выбрать потребность</option>{activeNeeds.map((need) => <option key={need.id} value={need.id}>{need.title} · {gradeName(need.target_grade)}</option>)}</select></label><label className="check-item strict-toggle"><input type="checkbox" checked={strict} onChange={(e) => toggleStrict(e.target.checked)} />Только подтверждённая группа</label><button className="button button-primary" onClick={() => void search()}>Найти кандидатов <span>→</span></button></div><p className="field-hint">Если обязательная компетенция не подтверждена, кандидат попадёт в группу уточнения или подтверждения — сервер не ослабляет требование.</p></Card>{!activeNeeds.length && <Empty title="Нет активных потребностей" detail="Создайте потребность и подтвердите компетенции, чтобы открыть поиск." action={<Link className="button button-secondary button-small" to="/needs">К потребностям</Link>} />}{result && <><div className="match-summary"><div><span className="eyebrow">РЕЗУЛЬТАТ ПОИСКА</span><p>{result.group_counts.confirmed} подтверждённых · {result.group_counts.needs_clarification} требуют уточнения · {result.group_counts.needs_confirmation} требуют подтверждения</p></div><div className="match-summary-actions"><button className="button button-secondary" disabled={!selected.length} onClick={() => void createShortlist()}>Сохранить выбранных · {selected.length}</button>{selected.length >= 2 && selected.length <= 3 && <Link className="button button-primary" to={`/compare?need_id=${encodeURIComponent(result.need_id)}&candidate_ids=${encodeURIComponent(selected.join(','))}`}>Сравнить · {selected.length} →</Link>}</div></div><div className="match-groups">{result.display_order.map((group) => { const title = group === 'confirmed' ? 'Подтверждённое соответствие' : group === 'needs_clarification' ? 'Нужно уточнение' : 'Нужно подтверждение'; const items = result.groups[group] as MatchCandidate[]; const count = result.group_counts[group]; return <section className="match-group" key={group}><div className="group-heading"><div><h2>{title}</h2><p>{group === 'confirmed' ? 'Обязательные компетенции подтверждены оценкой.' : group === 'needs_clarification' ? 'Есть данные, которые требуют обсуждения или проверки.' : 'Заявленных навыков недостаточно, чтобы считать требования подтверждёнными.'}</p></div><span>{count}</span></div>{items.length ? <div className="candidate-cards">{items.map((match) => <MatchCard key={match.candidate.id} match={match} selected={selected.includes(match.candidate.id)} onToggle={() => toggleSelected(match.candidate.id)} needId={result.need_id} />)}</div> : <div className="group-empty">В этой группе сейчас нет подходящих профилей.</div>}</section>; })}</div>{result.next_cursor && <button className="button button-secondary button-wide load-more" onClick={() => void search(result.need_id, result.strict_confirmed_only, result.next_cursor)}>Загрузить ещё</button>}</>}</>;
}

function MatchCard({ match, selected, onToggle, needId }: { match: MatchCandidate; selected: boolean; onToggle: () => void; needId: string }) {
  const candidate = match.candidate;
  const needQuery = useQuery({ queryKey: ['need', needId], queryFn: () => api.get<Need>(`/needs/${needId}`), enabled: Boolean(needId) });
  const [showInvite, setShowInvite] = useState(false);
  const [showPipeline, setShowPipeline] = useState(false);
  const [sent, setSent] = useState('');
  const pdf = () => window.open(candidate.profile_pdf_url, '_blank', 'noopener');
  return <Card className={`candidate-card ${match.state === 'confirmed' ? 'candidate-confirmed' : ''}`}>
    <div className="candidate-card-head"><label className="candidate-select" aria-label={`Выбрать ${candidate.display_name}`}><input type="checkbox" checked={selected} onChange={onToggle} /></label><div className="candidate-avatar">{candidate.display_name.slice(0, 1)}</div><div className="candidate-identity"><h3>{candidate.display_name}</h3><p>{candidate.headline ?? 'Backend Python'} · {gradeName(candidate.verified_grade ?? candidate.declared_grade)}</p></div><Badge tone={match.state === 'confirmed' ? 'good' : match.state === 'needs_confirmation' ? 'warm' : 'neutral'}>{match.state === 'confirmed' ? 'Подтверждён' : match.state === 'needs_confirmation' ? 'Нужно подтвердить' : 'Нужно уточнить'}</Badge></div>
    {candidate.summary && <p className="candidate-summary">{candidate.summary}</p>}
    <div className="candidate-meta"><span>{candidate.allowed_work_modes.map(workModeName).join(', ')}</span><span>{candidate.cities.join(', ') || 'Город не указан'}</span><span>{candidate.max_weekly_hours} ч/нед максимум</span></div>
    <div className="candidate-evidence">{candidate.competency_evidence.map((e) => <div className="evidence-row" key={e.skill_id}><span>{skillLabels[e.skill_id as keyof typeof skillLabels] ?? e.skill_id}</span><Badge tone={e.status === 'test_verified' ? 'good' : e.status === 'candidate_declared' ? 'warm' : 'neutral'}>{e.status === 'test_verified' ? 'Подтверждено тестом' : e.status === 'candidate_declared' ? 'Заявлено кандидатом' : 'Нет данных ФСП'}</Badge></div>)}</div>
    {candidate.qualification && <p className="demo-line">Оценка: {gradeName(candidate.qualification.grade)} · {date(candidate.qualification.assessed_at)} {candidate.demo && <Badge tone="warm">DEMO</Badge>}</p>}
    {candidate.fsp_evidence.length > 0 && <Notice>Есть демо-сигнал ФСП. Он не заменяет проверку навыков и может учитываться только как вспомогательное основание.</Notice>}
    <div className="reason-list match-reasons">{match.rank_reasons.map((reason, i) => <div key={i}><span className="reason-check">•</span><span>{reason}</span></div>)}</div>
    {sent && <Notice tone="success">{sent}</Notice>}
    {showInvite && <InvitationComposer candidate={candidate} needId={needId} need={needQuery.data} onSent={(message) => { setSent(message); setShowInvite(false); }} />}
    {showPipeline && <RecruitingRecordEditor candidateId={candidate.id} needId={needId} onSaved={() => setShowPipeline(false)} onCancel={() => setShowPipeline(false)} />}
    <div className="candidate-actions"><Link className="button button-secondary button-small" to={`/needs/${encodeURIComponent(needId)}/candidate-review?candidate_id=${encodeURIComponent(candidate.id)}`}>Разобрать профиль</Link><Link className="button button-secondary button-small" to={`/short-tasks?need_id=${encodeURIComponent(needId)}&candidate_id=${encodeURIComponent(candidate.id)}`}>Предложить задачу</Link><button className="button button-secondary button-small" onClick={() => setShowPipeline(true)}>В рабочую воронку</button><button className="button button-secondary button-small" onClick={pdf}>Профиль PDF ↗</button><button className="button button-primary button-small" onClick={() => setShowInvite(!showInvite)}>{showInvite ? 'Закрыть' : 'Пригласить'}</button></div>
  </Card>;
}

function InvitationComposer({ candidate, needId, need, onSent, initialTopics = [] }: { candidate: Candidate; needId: string | null; need?: Need; onSent?: (message: string) => void; initialTopics?: string[] }) {
  const [error, setError] = useState('');
  const [sending, setSending] = useState(false);
  const invitation = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const fd = new FormData(event.currentTarget);
    const hours = Number(fd.get('hours'));
    const topics = formValue(fd, 'topics').split('\n').map((x) => x.trim()).filter(Boolean);
    const min = Number(fd.get('min'));
    const max = Number(fd.get('max'));
    setError('');
    if (topics.length > 3) { setError('Укажите не более трёх тем для обсуждения.'); return; }
    if (min <= 0 || max < min) { setError('Проверьте зарплатную вилку: верхняя граница должна быть не меньше нижней.'); return; }
    const payload: Schemas['InvitationCreateRequest'] = {
      candidate_id: candidate.id,
      need_id: needId,
      salary: { min_kopecks: min * 100, max_kopecks: max * 100, currency: 'RUB', period: 'month', basis: formValue(fd, 'basis') as Schemas['MoneyRange']['basis'], workload_type: hours === 40 ? 'full_time' : 'part_time', hours_per_week: hours },
      description: formValue(fd, 'description'),
      contact_method: formValue(fd, 'contact_method') as Schemas['InvitationCreateRequest']['contact_method'],
      company_comment: formValue(fd, 'comment') || null,
      discussion_topics: topics,
    };
    try { payload.hiring_plan = readHiringPlan(fd); } catch (reason) { setError(errorMessage(reason)); return; }
    setSending(true);
    try {
      await api.post('/invitations', payload);
      onSent?.('Приглашение отправлено сервером. Контакты не раскрыты до принятия.');
    } catch (e) { setError(errorMessage(e)); }
    finally { setSending(false); }
  };
  return <form className="invite-form" onSubmit={(e) => void invitation(e)}>
    {error && <ErrorBlock error={error} />}
    <div className="form-grid"><Field label="Зарплата от, ₽ / месяц" name="min" type="number" required min="1" defaultValue={need ? Math.round(need.salary.min_kopecks / 100) : undefined} /><Field label="Зарплата до, ₽ / месяц" name="max" type="number" required min="1" defaultValue={need ? Math.round(need.salary.max_kopecks / 100) : undefined} /><label className="field"><span>Условия суммы</span><select name="basis" defaultValue={need?.salary.basis ?? 'gross'}><option value="gross">До налогов</option><option value="net">На руки</option></select></label><Field label="Часов в неделю" name="hours" type="number" min="1" max="40" required defaultValue={need?.workload_hours_per_week ?? 40} /><label className="field span-all"><span>Как связаться</span><select name="contact_method"><option value="platform">Открыть выбранные контакты</option><option value="email">Email</option><option value="phone">Телефон</option></select></label><TextArea className="span-all" label="Описание предложения" name="description" required rows={3} maxLength={3000} defaultValue={need?.description} /><TextArea className="span-all" label="Комментарий компании" name="comment" rows={2} maxLength={1500} /><TextArea className="span-all" label="Темы для обсуждения (по одной в строке, до 3)" name="topics" rows={2} defaultValue={initialTopics.slice(0, 3).join('\n')} /></div>
    <HiringPlanEditor />
    <div className="invite-form-actions"><span>Контакт откроется только после согласия кандидата.</span><button className="button button-primary button-small" disabled={sending}>{sending ? 'Отправляем…' : 'Отправить приглашение'}</button></div>
  </form>;
}

function ShortlistsPage() {
  const query = useQueryClient(); const shortlists = useQuery({ queryKey: ['shortlists'], queryFn: () => api.get<Group<Shortlist>>('/shortlists') });
  if (shortlists.isPending) return <Loading />; if (shortlists.isError) return <ErrorBlock error={shortlists.error} retry={() => void shortlists.refetch()} />;
  return <><PageHeader eyebrow="СОХРАНЁННЫЙ ВЫБОР" title="Шорт-листы" description="Сравнение использует выбранных кандидатов, не удаляя остальных участников списка." />{shortlists.data.items.length ? <div className="shortlist-grid">{shortlists.data.items.map((list) => <Card key={list.id} className="shortlist-card"><ShortlistManager shortlist={list} onChanged={(updated) => { query.setQueryData<Group<Shortlist>>(['shortlists'], (current) => current ? { ...current, items: current.items.map((item) => item.id === updated.id ? updated : item) } : current); void query.invalidateQueries({ queryKey: ['shortlists'] }); }} /></Card>)}</div> : <Empty title="Шорт-листов пока нет" detail="Сохраните выбор из результатов поиска — список будет храниться на сервере." action={<Link className="button button-secondary button-small" to="/matches">Открыть подборку</Link>} />}</>;
}

function CandidateReviewRoute() {
  const { needId = '' } = useParams(); const location = useLocation();
  const candidateId = new URLSearchParams(location.search).get('candidate_id') ?? '';
  const validUuid = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value);
  if (!validUuid(needId) || !validUuid(candidateId)) return <><PageHeader eyebrow="РАБОТА С КАНДИДАТАМИ" title="Некорректная ссылка" description="Откройте профиль из подборки, чтобы сохранить контекст потребности." /><Link className="button button-secondary" to="/matches">Вернуться к подборке</Link></>;
  return <CandidateReviewWorkspace key={`${needId}:${candidateId}`} needId={needId} candidateId={candidateId} />;
}

function CandidateReviewWorkspace({ needId, candidateId }: { needId: string; candidateId: string }) {
  const [draft, setDraft] = useState<{ need: Need; candidate: Candidate; topics: string[] }>();
  const [notice, setNotice] = useState('');
  const composerRef = useRef<HTMLElement>(null);
  useEffect(() => { if (draft) composerRef.current?.focus(); }, [draft]);
  return <><PageHeader eyebrow="РАЗБОР КАНДИДАТА" title="От профиля к разговору" description="Посмотрите, что уже известно и что осталось уточнить. Условия потребности перейдут в приглашение — проверьте их перед отправкой." action={<Link className="button button-secondary" to={`/matches?need=${encodeURIComponent(needId)}`}>К подборке</Link>} />
    {notice && <Notice tone="success">{notice}</Notice>}
    <CandidateReviewPanel needId={needId} candidateId={candidateId} onTopicsSelected={(topics, need, candidate) => { setNotice(''); setDraft({ topics: topics.slice(0, 3), need, candidate }); }} />
    {draft && <section className="card" ref={composerRef} tabIndex={-1} aria-label="Подготовка приглашения"><div className="card-head"><h2>Приглашение · {draft.candidate.display_name}</h2><button className="button button-secondary button-small" onClick={() => setDraft(undefined)}>Закрыть предложение</button></div><p>Условия перенесены из «{draft.need.title}». Можно изменить их перед отправкой. Это приглашение обсудить работу.</p><InvitationComposer key={`${draft.need.id}:${draft.need.version}:${draft.candidate.id}:${draft.topics.join('|')}`} candidate={draft.candidate} needId={draft.need.id} need={draft.need} initialTopics={draft.topics} onSent={(message) => { setNotice(message); setDraft(undefined); }} /></section>}
  </>;
}

function candidateIdFromInput(value: string): string | null {
  const input = value.trim();
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  if (uuid.test(input)) return input;
  try {
    const url = new URL(input, window.location.origin);
    const segments = url.pathname.split('/').filter(Boolean);
    const index = segments.lastIndexOf('candidates');
    const id = index >= 0 ? segments[index + 1] : undefined;
    return id && uuid.test(id) ? id : null;
  } catch { return null; }
}

function EmployerInvitations({ user }: { user: User }) {
  const query = useQueryClient();
  const location = useLocation();
  const initialCandidateId = new URLSearchParams(location.search).get('candidate_id') ?? '';
  const invitations = useQuery({ queryKey: ['sentInvitations'], queryFn: () => api.get<Group<Invitation>>('/invitations/sent') });
  const [error, setError] = useState('');
  const [showDirect, setShowDirect] = useState(Boolean(initialCandidateId));
  const [profileInput, setProfileInput] = useState(initialCandidateId);
  const [candidateId, setCandidateId] = useState(initialCandidateId);
  const [lookupError, setLookupError] = useState('');
  const [compose, setCompose] = useState(false);
  const [directSent, setDirectSent] = useState('');
  const candidate = useQuery({ queryKey: ['directCandidate', candidateId], queryFn: () => api.get<Candidate>(`/candidates/${candidateId}`), enabled: Boolean(candidateId), retry: false });
  const withdraw = async (invitation: Invitation) => { setError(''); try { await api.post(`/invitations/${invitation.id}/withdraw`, { expected_version: invitation.version }); await query.invalidateQueries({ queryKey: ['sentInvitations'] }); } catch (e) { setError(errorMessage(e)); } };
  const lookup = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const id = candidateIdFromInput(profileInput);
    setLookupError(''); setDirectSent(''); setCompose(false);
    if (!id) { setCandidateId(''); setLookupError('Введите UUID кандидата или ссылку вида /candidates/{UUID}.'); return; }
    setCandidateId(id);
  };
  if (invitations.isPending) return <Loading />;
  if (invitations.isError) return <ErrorBlock error={invitations.error} retry={() => void invitations.refetch()} />;
  return <>
    <PageHeader eyebrow="АКТИВНОСТЬ КОМПАНИИ" title="Отправленные приглашения" description="Условия зафиксированы на момент отправки. Для изменения условий отправьте новую версию, которую кандидат должен принять заново." action={<button className="button button-primary" onClick={() => { setShowDirect((open) => !open); setDirectSent(''); }}>{showDirect ? 'Закрыть форму' : 'Пригласить напрямую'}</button>} />
    <div className="email-verification-row"><Badge tone={user.email_verified ? 'good' : 'warm'}>{user.email_verified ? 'Email аккаунта подтверждён' : 'Email аккаунта не подтверждён'}</Badge><span>{user.company?.name ?? 'Компания не указана'}</span></div>
    {error && <ErrorBlock error={error} />}
    {showDirect && <Card title="Приглашение без потребности" className="direct-invite-card"><p className="field-hint">Найдите доступный публичный профиль по ссылке или UUID. Ссылка сама по себе не раскрывает контакты.</p><form className="direct-lookup" onSubmit={lookup}><Field label="Ссылка на профиль" name="profile_url" value={profileInput} onChange={(event) => setProfileInput(event.target.value)} placeholder="/candidates/UUID или UUID" required /><button className="button button-secondary" type="submit">Проверить профиль</button></form>{lookupError && <ErrorBlock error={lookupError} />}{candidate.isPending && <Loading label="Проверяем видимость профиля и доступ компании" />}{candidate.isError && <ErrorBlock error={candidate.error} retry={() => void candidate.refetch()} />}{candidate.data && <div className="direct-preview"><div className="candidate-card-head"><div className="candidate-avatar">{candidate.data.display_name.slice(0, 1)}</div><div className="candidate-identity"><h3>{candidate.data.display_name}</h3><p>{candidate.data.headline ?? 'Backend Python'} · {gradeName(candidate.data.verified_grade ?? candidate.data.declared_grade)}</p></div><Badge tone={candidate.data.demo ? 'warm' : 'neutral'}>{candidate.data.demo ? 'DEMO' : 'Публичный профиль'}</Badge></div>{candidate.data.summary && <p className="candidate-summary">{candidate.data.summary}</p>}<div className="candidate-meta"><span>{candidate.data.allowed_work_modes.map(workModeName).join(', ')}</span><span>{candidate.data.cities.join(', ') || 'Город не указан'}</span><span>{candidate.data.max_weekly_hours} ч/нед максимум</span></div>{candidate.data.qualification && <p className="demo-line">Оценка: {gradeName(candidate.data.qualification.grade)} · {date(candidate.data.qualification.assessed_at)} {candidate.data.qualification.demo && <Badge tone="warm">demo</Badge>}</p>}<div className="candidate-evidence">{candidate.data.competency_evidence.map((e) => <div className="evidence-row" key={e.skill_id}><span>{skillLabels[e.skill_id as keyof typeof skillLabels] ?? e.skill_id}</span><Badge tone={e.status === 'test_verified' ? 'good' : e.status === 'candidate_declared' ? 'warm' : 'neutral'}>{e.status === 'test_verified' ? 'Подтверждено тестом' : e.status === 'candidate_declared' ? 'Заявлено кандидатом' : 'Нет данных ФСП'}</Badge></div>)}</div><div className="direct-preview-actions"><button className="button button-secondary button-small" onClick={() => window.open(candidate.data.profile_pdf_url, '_blank', 'noopener')}>Профиль PDF ↗</button><button className="button button-primary button-small" onClick={() => setCompose((open) => !open)}>{compose ? 'Закрыть предложение' : 'Составить приглашение'}</button></div>{compose && <InvitationComposer key={candidate.data.id} candidate={candidate.data} needId={null} onSent={(message) => { setDirectSent(message); setCompose(false); void query.invalidateQueries({ queryKey: ['sentInvitations'] }); }} />}{directSent && <Notice tone="success">{directSent}</Notice>}<Notice>Профиль получен с сервера с учётом текущей видимости и прав компании. Контакты доступны только после принятия приглашения.</Notice></div>}</Card>}
    {invitations.data.items.length ? <div className="invitation-list">{invitations.data.items.map((invite) => <EmployerInvitationCard key={invite.id} invitation={invite} onWithdraw={() => void withdraw(invite)} />)}</div> : <Empty title="Вы ещё не отправляли приглашений" detail="Найдите кандидата в подборке или используйте прямое приглашение со ссылкой на профиль." action={<Link className="button button-secondary button-small" to="/matches">К подборке</Link>} />}
  </>;
}

function useRevealedContacts(candidateId: string, enabled: boolean) {
  const [revealed, setRevealed] = useState(false);
  const [contact, setContact] = useState<ContactBundle>();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const requestId = useRef(0);
  const revealedRef = useRef(false);
  const enabledRef = useRef(enabled);
  enabledRef.current = enabled;

  const refresh = React.useCallback(async () => {
    if (!revealedRef.current || !enabledRef.current || document.visibilityState !== 'visible') return;
    setError('');
    setLoading(true);
    const id = ++requestId.current;
    try {
      const result = await readContactIfAuthorized({
        clear: () => setContact(undefined),
        read: () => api.get<ContactBundle>(`/candidates/${candidateId}/contacts`),
        revealed: revealedRef.current,
        visible: document.visibilityState === 'visible',
        authenticated: enabledRef.current,
      });
      if (!isContactResponseCurrent({ requestId: id, currentRequestId: requestId.current, revealed: revealedRef.current, visible: document.visibilityState === 'visible', authenticated: enabledRef.current })) return;
      if (result.status === 'success') setContact(result.value);
      if (result.status === 'error') setError(errorMessage(result.error));
    } finally {
      if (id === requestId.current) setLoading(false);
    }
  }, [candidateId]);

  const reveal = () => {
    if (!enabledRef.current) return;
    revealedRef.current = true;
    setRevealed(true);
    void refresh();
  };
  const hide = () => {
    revealedRef.current = false;
    requestId.current += 1;
    setRevealed(false);
    setContact(undefined);
    setError('');
    setLoading(false);
  };

  useEffect(() => {
    if (!enabled) hide();
    const onFocus = () => { if (revealedRef.current) void refresh(); };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        requestId.current += 1;
        setContact(undefined);
        setLoading(false);
      } else if (revealedRef.current) void refresh();
    };
    const interval = window.setInterval(() => { if (revealedRef.current) void refresh(); }, 30_000);
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      requestId.current += 1;
      window.clearInterval(interval);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [enabled, refresh]);

  return { revealed, contact, error, loading, reveal, hide };
}

function EmployerInvitationCard({ invitation, onWithdraw }: { invitation: Invitation; onWithdraw: () => void }) {
  const query = useQueryClient();
  const candidate = useQuery({ queryKey: ['candidate', invitation.candidate_id], queryFn: () => api.get<Candidate>(`/candidates/${invitation.candidate_id}`) });
  const contacts = useRevealedContacts(invitation.candidate_id, invitation.state === 'accepted');
  const [error, setError] = useState('');
  const [showSupersede, setShowSupersede] = useState(false);
  const supersede = async (event: FormEvent<HTMLFormElement>) => { event.preventDefault(); const fd = new FormData(event.currentTarget); const hours = Number(fd.get('hours')); const min = Number(fd.get('min')); const max = Number(fd.get('max')); if (min <= 0 || max < min) { setError('Проверьте зарплатную вилку: верхняя граница должна быть не меньше нижней.'); return; } const payload: Schemas['InvitationSupersedeRequest'] = { expected_version: invitation.version, salary: { min_kopecks: min * 100, max_kopecks: max * 100, currency: 'RUB', period: 'month', basis: formValue(fd, 'basis') as Schemas['MoneyRange']['basis'], workload_type: hours === 40 ? 'full_time' : 'part_time', hours_per_week: hours }, description: formValue(fd, 'description'), contact_method: formValue(fd, 'contact_method') as Schemas['InvitationSupersedeRequest']['contact_method'], company_comment: formValue(fd, 'comment') || null, discussion_topics: invitation.discussion_topics }; try { payload.hiring_plan = readHiringPlan(fd); await api.post(`/invitations/${invitation.id}/supersede`, payload); setShowSupersede(false); await query.invalidateQueries({ queryKey: ['sentInvitations'] }); } catch (e) { setError(errorMessage(e)); } };
  return <Card className="invite-card">
    <div className="invite-top"><div><div className="eyebrow">ПРЕДЛОЖЕНИЕ · {date(invitation.updated_at)}</div><h2>{candidate.data?.display_name ?? 'Кандидат'}</h2><p>{candidate.data ? `${gradeName(candidate.data.verified_grade ?? candidate.data.declared_grade)} · ${candidate.data.headline ?? 'Backend Python'}` : invitation.candidate_id}</p></div><Badge tone={invitation.state === 'pending' ? 'warm' : invitation.state === 'accepted' ? 'good' : 'neutral'}>{invitation.state === 'pending' ? 'Ожидает ответа' : invitation.state === 'accepted' ? 'Принято' : titleCase(invitation.state)}</Badge></div>
    <div className="salary-highlight">{money(invitation.salary.min_kopecks)}–{money(invitation.salary.max_kopecks)} ₽ <small>/ месяц · {invitation.salary.basis === 'gross' ? 'до вычета налогов' : 'на руки'}</small></div>
    <p>{invitation.description}</p><HiringPlanView plan={invitation.hiring_plan} />{invitation.state === 'accepted' && <div className="invite-actions"><Link className="button button-secondary button-small" to={`/messages/${invitation.id}`}>Диалог и следующий шаг</Link></div>}<div className="invite-meta"><span>Способ контакта: <strong>{invitation.contact_method === 'platform' ? 'Открытие выбранных контактов' : invitation.contact_method === 'email' ? 'По электронной почте' : 'По телефону'}</strong></span><span>Истекает {date(invitation.expires_at)}</span></div>
    {invitation.system_reasons.length > 0 && <div className="reason-list">{invitation.system_reasons.map((reason, index) => <div key={`${reason.kind}-${index}`}><span className="reason-check">•</span><span>{reason.text}{reason.demo && <Badge tone="warm">demo</Badge>}</span></div>)}</div>}
    {error && <ErrorBlock error={error} />}{contacts.error && <ErrorBlock error={contacts.error} retry={() => void contacts.reveal()} />}
    {invitation.state === 'accepted' && contacts.contact && <div className="contact-reveal"><Notice tone="success">Контакты подтверждены сервером на {date(contacts.contact.granted_at)}. Компания может читать выбранные контакты.</Notice>{contacts.contact.channels.map((channel) => <div key={channel.type}><small>{titleCase(channel.type)}</small><strong>{channel.value}</strong></div>)}<button className="text-button" type="button" onClick={contacts.hide}>Скрыть контакты</button></div>}
    {invitation.state === 'accepted' && !contacts.revealed && <button className="button button-primary button-small" onClick={contacts.reveal}>Показать открытые контакты</button>}
    {contacts.loading && <p role="status" aria-live="polite">Проверяем актуальный доступ к контактам…</p>}
    {invitation.state === 'pending' && <div className="invite-actions"><button className="button button-secondary button-small" onClick={onWithdraw}>Отозвать</button><button className="button button-primary button-small" onClick={() => setShowSupersede(!showSupersede)}>Изменить условия</button></div>}
    {showSupersede && invitation.state === 'pending' && <form className="invite-form" onSubmit={(event) => void supersede(event)}><div className="form-grid"><Field label="Зарплата от, ₽ / месяц" name="min" type="number" defaultValue={Math.round(invitation.salary.min_kopecks / 100)} required /><Field label="Зарплата до, ₽ / месяц" name="max" type="number" defaultValue={Math.round(invitation.salary.max_kopecks / 100)} required /><label className="field"><span>Условия</span><select name="basis" defaultValue={invitation.salary.basis}><option value="gross">До налогов</option><option value="net">На руки</option></select></label><Field label="Часов в неделю" name="hours" type="number" min="1" max="40" defaultValue={invitation.salary.hours_per_week} required /><label className="field span-all"><span>Как связаться</span><select name="contact_method" defaultValue={invitation.contact_method}><option value="platform">Открыть выбранные контакты</option><option value="email">Email</option><option value="phone">Телефон</option></select></label><TextArea className="span-all" label="Описание предложения" name="description" defaultValue={invitation.description} required rows={3} /><TextArea className="span-all" label="Комментарий" name="comment" defaultValue={invitation.company_comment ?? ''} rows={2} /></div><HiringPlanEditor initialPlan={invitation.hiring_plan} /><button className="button button-primary button-small">Отправить новую версию</button></form>}
  </Card>;
}

function useSearchParams(): [URLSearchParams, (update: (old: URLSearchParams) => URLSearchParams) => void] {
  const location = useLocation(); const navigate = useNavigate(); const params = new URLSearchParams(location.search);
  return [params, (update) => { const next = update(new URLSearchParams(location.search)); navigate({ search: next.toString() }, { replace: true }); }];
}

export default App;
