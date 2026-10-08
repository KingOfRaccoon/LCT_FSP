import { useState } from 'react';
import { Link } from 'react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, skillLabels, type Candidate, type Schemas, type Shortlist, type SkillId, type Taxonomy } from '../../api';

type BankCandidate = Omit<Candidate, 'category_evidence'> & {
  industry?: string | null;
  role_preferences?: string[];
  soft_skills?: string[];
  category_evidence?: 'confirmed' | 'unconfirmed' | 'unknown';
};
type BankSearch = {
  specialization_id: 'backend-python-v1' | null;
  grade: 'junior' | 'middle' | null;
  category_status: 'any' | 'confirmed' | 'unconfirmed';
  skill_ids: SkillId[];
  fsp_status: 'any' | 'has_evidence' | 'no_evidence';
  limit: 20;
  cursor: string | null;
};
type SearchResponse = Omit<Schemas['CandidateBankPage'], 'items'> & { items: BankCandidate[] };

const emptyFilters = (): BankSearch => ({ specialization_id: null, grade: null, category_status: 'any', skill_ids: [], fsp_status: 'any', limit: 20, cursor: null });

export function CaseBankPage() {
  const query = useQueryClient();
  const taxonomy = useQuery({ queryKey: ['taxonomy'], queryFn: () => api.get<Taxonomy>('/taxonomy') });
  const [filters, setFilters] = useState<BankSearch>(emptyFilters);
  const [result, setResult] = useState<SearchResponse>();
  const [searchedFilters, setSearchedFilters] = useState<BankSearch>();
  const [selected, setSelected] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [savingShortlist, setSavingShortlist] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const search = async (cursor: string | null = null, requestFilters: BankSearch = filters) => {
    setBusy(true); setError(''); setNotice('');
    try {
      const page = await api.post<SearchResponse>('/candidates/search', { ...requestFilters, cursor });
      setResult(cursor ? { ...page, items: [...(result?.items ?? []), ...page.items] } : page);
      setSearchedFilters(requestFilters);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusy(false); }
  };

  const updateFilters = (update: (current: BankSearch) => BankSearch) => setFilters((current) => ({ ...update(current), cursor: null }));
  const toggleSkill = (skill: SkillId) => updateFilters((current) => ({ ...current, skill_ids: current.skill_ids.includes(skill) ? current.skill_ids.filter((id) => id !== skill) : [...current.skill_ids, skill] }));
  const toggleCandidate = (id: string) => setSelected((current) => current.includes(id) ? current.filter((item) => item !== id) : [...current, id]);
  const saveShortlist = async () => {
    if (!selected.length || savingShortlist) return;
    setSavingShortlist(true);
    setError(''); setNotice('');
    try {
      const list = await api.post<Shortlist>('/shortlists', {
        name: `Банк кандидатов · ${new Date().toLocaleDateString('ru-RU')}`,
        need_id: null,
        query_snapshot: null,
        candidate_ids: selected,
      });
      setNotice(`Выбор сохранён в «${list.name}». Он останется доступен в шорт-листах при смене фильтров.`);
      setSelected([]);
      await query.invalidateQueries({ queryKey: ['shortlists'] });
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setSavingShortlist(false); }
  };

  if (taxonomy.isPending) return <div className="loading"><span className="spinner" aria-hidden="true" />Загружаем фильтры…</div>;
  if (taxonomy.isError) return <div className="error-box" role="alert"><span>{errorMessage(taxonomy.error)}</span><button className="button button-secondary button-small" onClick={() => void taxonomy.refetch()}>Повторить</button></div>;
  const skillOptions = taxonomy.data.skills.filter((skill): skill is { id: SkillId; test_assessed: boolean } => Object.hasOwn(skillLabels, skill.id));
  const filtersChanged = Boolean(searchedFilters && JSON.stringify(filters) !== JSON.stringify(searchedFilters));

  return <>
    <header className="page-header"><div><div className="eyebrow">НЕЗАВИСИМЫЙ ПОИСК</div><h1>Банк кандидатов</h1><p>Ищите публичные профили без создания потребности. Уровень и навыки показаны с указанием источника.</p></div></header>
    {error && <div className="error-box" role="alert"><span>{error}</span></div>}{notice && <div className="notice notice-success" role="status">{notice}</div>}
    <section className="card case-bank-filters">
      <div className="card-head"><h2>Параметры поиска</h2><span className="eyebrow">СЕРВЕРНЫЕ ФИЛЬТРЫ</span></div>
      <div className="case-bank-filter-grid">
        <label className="field"><span>Специализация</span><select value={filters.specialization_id ?? ''} onChange={(event) => updateFilters((current) => ({ ...current, specialization_id: event.target.value ? 'backend-python-v1' : null }))}><option value="">Любая</option><option value="backend-python-v1">Backend Python</option></select></label>
        <label className="field"><span>Подтверждённый уровень</span><select value={filters.grade ?? ''} onChange={(event) => updateFilters((current) => ({ ...current, grade: (event.target.value || null) as BankSearch['grade'] }))}><option value="">Любой</option><option value="junior">Junior</option><option value="middle">Middle</option></select></label>
        <label className="field"><span>Категория</span><select value={filters.category_status} onChange={(event) => updateFilters((current) => ({ ...current, category_status: event.target.value as BankSearch['category_status'] }))}><option value="any">Любая</option><option value="confirmed">Подтверждена тестом</option><option value="unconfirmed">Не подтверждена тестом</option></select></label>
        <label className="field"><span>Сигнал ФСП</span><select value={filters.fsp_status} onChange={(event) => updateFilters((current) => ({ ...current, fsp_status: event.target.value as BankSearch['fsp_status'] }))}><option value="any">Любой</option><option value="has_evidence">Есть свидетельства</option><option value="no_evidence">Нет свидетельств</option></select></label>
      </div>
      <fieldset className="case-bank-skills"><legend>Компетенции</legend><div className="check-grid">{skillOptions.map((skill) => <label className="check-item" key={skill.id}><input type="checkbox" checked={filters.skill_ids.includes(skill.id)} onChange={() => toggleSkill(skill.id)} />{skillLabels[skill.id]}</label>)}</div><small>{filters.skill_ids.length ? 'Нужен хотя бы один из выбранных навыков. Подтверждённые тестом и заявленные навыки показаны раздельно.' : 'Подтверждённые тестом и заявленные навыки отображаются раздельно в карточке.'}</small></fieldset>
      <div className="case-bank-search-row"><span className="field-hint">Результаты доступны только для профилей, которые сейчас открыты для подбора.</span><button className="button button-primary" disabled={busy} onClick={() => void search()}>{busy ? 'Ищем…' : 'Найти кандидатов'} <span>→</span></button></div>
    </section>
    {result && <>
      <div className="case-bank-results-head"><div><div className="eyebrow">РЕЗУЛЬТАТЫ</div><p>{result.items.length} в текущей выдаче</p></div><div className="case-bank-actions"><button className="button button-secondary" disabled={!selected.length || savingShortlist} onClick={() => void saveShortlist()}>{savingShortlist ? 'Сохраняем…' : `Сохранить выбор · ${selected.length}`}</button><Link className="button button-secondary" to="/shortlists">Мои шорт-листы →</Link></div></div>
      {filtersChanged && <div className="notice">Фильтры изменены. Запустите новый поиск, чтобы обновить результаты.</div>}
      {result.items.length ? <div className="case-bank-list">{result.items.map((candidate) => <BankCandidateCard key={candidate.id} candidate={candidate} selected={selected.includes(candidate.id)} onToggle={() => toggleCandidate(candidate.id)} />)}</div> : <div className="empty"><div className="empty-icon">⌕</div><h3>По этим фильтрам кандидатов нет</h3><p>Попробуйте изменить фильтры. Отсутствие данных ФСП не означает низкую оценку.</p></div>}
      {result.next_cursor && !filtersChanged && searchedFilters && <button className="button button-secondary button-wide load-more" disabled={busy} onClick={() => void search(result.next_cursor, searchedFilters)}>{busy ? 'Загружаем…' : 'Загрузить ещё'}</button>}
    </>}
  </>;
}

function BankCandidateCard({ candidate, selected, onToggle }: { candidate: BankCandidate; selected: boolean; onToggle: () => void }) {
  const status = candidate.category_evidence ?? (candidate.verified_grade ? 'confirmed' : 'unknown');
  const verifiedGradeLabel = candidate.verified_grade ? candidate.verified_grade === 'middle' ? 'Middle' : 'Junior' : 'Уровень не указан';
  const declaredGradeLabel = candidate.declared_grade ? candidate.declared_grade === 'middle' ? 'Middle' : 'Junior' : 'Уровень не указан';
  return <article className="card case-bank-candidate">
    <div className="case-bank-candidate-head"><label className="candidate-select" aria-label={`Выбрать ${candidate.display_name}`}><input type="checkbox" checked={selected} onChange={onToggle} /></label><div className="candidate-avatar">{candidate.display_name.slice(0, 1)}</div><div className="candidate-identity"><h3>{candidate.display_name}</h3><p>{candidate.headline ?? 'Backend Python'} · {candidate.industry || 'Индустрия не указана'}</p></div><div className="case-bank-badges"><span className={`badge ${status === 'confirmed' ? 'badge-good' : status === 'unconfirmed' ? 'badge-warm' : ''}`}>{status === 'confirmed' ? `Тест · ${verifiedGradeLabel}` : status === 'unconfirmed' ? `Заявленный уровень · ${declaredGradeLabel}` : 'Уровень и категория не указаны'}</span>{(candidate.demo || candidate.qualification?.demo) && <span className="badge badge-warm">DEMO</span>}</div></div>
    {candidate.summary && <p className="candidate-summary">{candidate.summary}</p>}
    <div className="candidate-meta"><span>{candidate.allowed_work_modes.map((mode) => mode === 'remote' ? 'Удалённо' : mode === 'hybrid' ? 'Гибрид' : 'Офис').join(', ')}</span><span>{candidate.cities.join(', ') || 'Город не указан'}</span><span>{candidate.max_weekly_hours} ч/нед максимум</span></div>
    {candidate.role_preferences?.length ? <p className="case-bank-extra"><strong>Рассматривает:</strong> {candidate.role_preferences.join(', ')}</p> : null}
    {candidate.soft_skills?.length ? <p className="case-bank-extra"><strong>Гибкие навыки · заявлено:</strong> {candidate.soft_skills.join(', ')}</p> : null}
    <div className="candidate-evidence">{candidate.competency_evidence.map((evidence) => <div className="evidence-row" key={evidence.skill_id}><span>{skillLabels[evidence.skill_id as SkillId] ?? evidence.skill_id}</span><span className={`badge ${evidence.status === 'test_verified' ? 'badge-good' : evidence.status === 'candidate_declared' ? 'badge-warm' : ''}`}>{evidence.status === 'test_verified' ? 'Подтверждено тестом' : evidence.status === 'candidate_declared' ? 'Заявлено кандидатом' : 'Нет данных ФСП'}</span></div>)}</div>
    {candidate.fsp_signal !== 'none' && <div className="notice">Серверный сигнал ФСП: {candidate.fsp_signal === 'prize' ? 'призовое свидетельство' : 'участие'}. Он не подтверждает профессиональный уровень.</div>}
    {candidate.fsp_evidence.length > 0 && <div className="case-bank-fsp-records"><strong>Записи ФСП</strong>{candidate.fsp_evidence.map((evidence, index) => <p key={`${evidence.event_title}-${index}`}>{evidence.event_title}{evidence.event_date ? ` · ${evidence.event_date}` : ''} · {evidence.result} · {evidence.validity === 'valid' ? 'действительно' : evidence.validity === 'revoked' ? 'отозвано' : 'статус неизвестен'} · источник: {evidence.source === 'fsp_mock' ? 'демо ФСП' : evidence.source}{evidence.demo && ' · DEMO'}{evidence.team_result ? ' · командный результат' : ''}{evidence.participant_linked === true ? ' · участник связан' : evidence.participant_linked === false ? ' · участник не связан' : ''}{evidence.membership_confirmed ? ' · состав подтверждён' : ''}{evidence.used_for_ranking === false ? ' · Не учтено в сигнале' : evidence.used_for_ranking === true ? ' · Учтено в сигнале ФСП' : ' · учёт в сигнале не указан'}</p>)}{candidate.fsp_signal === 'none' && <small>Эти записи не дают серверного квалифицированного сигнала и не подтверждают личную категорию.</small>}</div>}
    <div className="reason-list case-bank-reasons"><div><span className="reason-check">•</span><span>{status === 'confirmed' ? 'Категория подтверждена оценкой платформы.' : status === 'unconfirmed' ? 'Категория не подтверждена оценкой платформы; грейд указан кандидатом.' : 'Данных для определения категории нет.'}</span></div><div><span className="reason-check">•</span><span>Контакты кандидата откроются только после принятия приглашения.</span></div></div>
    <div className="case-bank-card-actions"><a className="button button-secondary button-small" href={candidate.profile_pdf_url} target="_blank" rel="noreferrer">Профиль PDF ↗</a><Link className="button button-primary button-small" to={`/invitations?candidate_id=${encodeURIComponent(candidate.id)}`}>Прямое приглашение →</Link></div>
  </article>;
}

export type ConsentScope = 'processing' | 'publication';
export type ConsentState = Schemas['ConsentPage'];

export function ConsentPanel({ onChanged, includePublication = true }: { onChanged?: () => void; includePublication?: boolean }) {
  const query = useQueryClient();
  const consents = useQuery({ queryKey: ['consents'], queryFn: () => api.get<ConsentState>('/me/consents') });
  const [read, setRead] = useState<Record<ConsentScope, boolean>>({ processing: false, publication: false });
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busyScope, setBusyScope] = useState<ConsentScope | null>(null);
  const mutate = async (scope: ConsentScope, action: 'accept' | 'revoke') => {
    const document = consents.data?.documents.find((item) => item.scope === scope);
    if (!document || (action === 'accept' && !read[scope])) return;
    if (action === 'revoke' && !window.confirm(scope === 'publication' ? 'Отозвать согласие на публикацию? Профиль будет скрыт от работодателей.' : includePublication ? 'Отозвать согласие на обработку? Профиль будет скрыт, а редактирование и новая оценка заблокируются до повторного согласия.' : 'Отозвать согласие на обработку? Редактирование профиля компании будет заблокировано до повторного согласия.')) return;
    setBusyScope(scope); setError(''); setNotice('');
    try {
      await api.post('/me/consents', { scope, action, version: document.version });
      setRead((current) => ({ ...current, [scope]: false }));
      await query.invalidateQueries({ queryKey: ['consents'] });
      await query.invalidateQueries({ queryKey: ['profile'] });
      await query.invalidateQueries({ queryKey: ['me'] });
      onChanged?.();
      setNotice(action === 'accept' ? 'Согласие сохранено.' : scope === 'publication' ? 'Профиль скрыт от работодателей.' : includePublication ? 'Профиль скрыт. Для возобновления редактирования и оценки потребуется новое согласие.' : 'Редактирование профиля компании заблокировано до повторного принятия согласия.');
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setBusyScope(null); }
  };
  if (consents.isPending) return <div className="loading"><span className="spinner" aria-hidden="true" />Загружаем условия…</div>;
  if (consents.isError || !consents.data) return <div className="error-box" role="alert"><span>{errorMessage(consents.error)}</span><button className="button button-secondary button-small" onClick={() => void consents.refetch()}>Повторить</button></div>;
  return <div className="case-consent-panel">{error && <div className="error-box" role="alert">{error}</div>}{notice && <div className="notice notice-success" role="status">{notice}</div>}
    {(['processing', ...(includePublication ? ['publication' as const] : [])] as ConsentScope[]).map((scope) => {
      const document = consents.data!.documents.find((item) => item.scope === scope);
      if (!document) return null;
      const state = consents.data!.states.find((item) => item.scope === scope);
      const accepted = state?.accepted ?? false;
      return <section className="case-consent-document" key={scope}><div className="case-consent-heading"><div><div className="eyebrow">ВЕРСИЯ {document.version}</div><h3>{document.title}</h3></div><span className={`badge ${accepted ? 'badge-good' : 'badge-warm'}`}>{accepted ? 'Действует' : 'Не принято'}</span></div><div className="case-consent-text">{document.text}</div>{!accepted && <label className="case-consent-check"><input type="checkbox" checked={read[scope]} onChange={(event) => setRead((current) => ({ ...current, [scope]: event.target.checked }))} />Я прочитал(а) этот текст и принимаю его</label>}<div className="case-consent-actions">{accepted ? <button className="button button-secondary button-small" disabled={busyScope === scope} onClick={() => void mutate(scope, 'revoke')}>Отозвать согласие</button> : <button className="button button-primary button-small" disabled={!read[scope] || busyScope === scope} onClick={() => void mutate(scope, 'accept')}>{busyScope === scope ? 'Сохраняем…' : 'Принять условия'}</button>}</div></section>;
    })}
    {consents.data.events.length > 0 && <div className="case-consent-history"><h3>История действий</h3>{consents.data.events.map((event) => <p key={event.id}>{event.scope === 'processing' ? 'Обработка данных' : 'Публикация профиля'} · {event.action === 'accept' ? 'принято' : 'отозвано'} · версия {event.version} · {new Date(event.occurred_at).toLocaleString('ru-RU')}</p>)}</div>}
    <p className="field-hint">Текст согласия представлен как проект для оператора платформы и не является юридическим заключением.</p>
  </div>;
}
