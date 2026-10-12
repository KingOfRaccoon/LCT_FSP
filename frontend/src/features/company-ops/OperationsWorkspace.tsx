import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorMessage, RequestError } from '../../api';
import { isValidRussianInn, validateCompanyReview, type CompanyReviewDraft } from './logic';
import './company-ops.css';

type OpsSummary = {
  generated_at: string;
  process: { uptime_seconds: number; heap_used_bytes: number; heap_max_bytes: number };
  database: { available: boolean };
  http: { requests_total: number; server_errors_total: number; average_duration_seconds: number };
  outbox: { worker_enabled: boolean; worker_last_tick_at: string | null; counts: { pending: number; delivered: number; failed: number; discarded: number } | null; oldest_due_age_seconds: number | null };
  upstream: { dadata: { success: number; timeout: number; error: number }; smtp: { success: number; timeout: number; error: number } | null };
};
type VerificationRequest = {
  id: string; version: number; company_id: string; company_version: number; criteria_current: boolean; criteria_hash: string;
  status: string; demo: boolean; inn: string; legal_name: string;
  snapshot: { inn: string; legal_name: string; website?: string | null; contact_email?: string | null; owner_email?: string | null; owner_email_verified?: boolean };
  source_type: string; source_label: string; source_reference?: string | null; checked_at?: string | null; expires_at?: string | null; review_notes?: string | null; created_at: string;
};
type QueueResponse = { items: VerificationRequest[] };
type Tab = 'monitor' | 'queue';
type ReviewDraft = CompanyReviewDraft;
type PendingReview = { fingerprint: string; key: string };

const emptyDraft: ReviewDraft = { outcome: 'approve', evidence_reference: '', registry_inn: '', registry_legal_name: '', registry_status: 'UNKNOWN', representative_reviewed: false, comment: '' };
const statusLabels: Record<string, string> = { pending: 'Ожидает проверки', registry_confirmed: 'Сверено с реестром', reviewed: 'Проверена оператором', needs_changes: 'Нужны уточнения', rejected: 'Отклонена', unavailable: 'Нет данных', expired: 'Срок истёк', superseded: 'Реквизиты изменились' };
const isoLabel = (value?: string | null) => value ? new Date(value).toLocaleString('ru-RU') : 'Нет данных';
const countLabel = (value: number | null | undefined) => value == null ? 'Нет данных' : value.toLocaleString('ru-RU');
const durationLabel = (value: number | null | undefined) => value == null ? 'Нет данных' : `${value.toLocaleString('ru-RU')} с`;
const bytesLabel = (value: number | null | undefined) => value == null ? 'Нет данных' : `${(value / 1024 / 1024).toFixed(1)} МБ`;
const asPayload = (payload: object) => payload as import('../../api').ApiPayload;

function Metric({ label, value, tone }: { label: string; value: string; tone?: 'good' | 'bad' | 'warm' }) {
  return <div className={`company-ops-metric ${tone ? `is-${tone}` : ''}`}><span>{label}</span><strong>{value}</strong></div>;
}

function OpsMonitor({ summary }: { summary: OpsSummary }) {
  const counts = summary.outbox.counts;
  const upstream = summary.upstream;
  return <div className="company-ops-monitor">
    <p className="company-ops-intro">Снимок состояния сервиса на {isoLabel(summary.generated_at)}. Метрики описывают процесс и подключённые зависимости; они не подтверждают доступность для всех пользователей.</p>
    <div className="company-ops-grid">
      <Metric label="База данных" value={summary.database.available ? 'Доступна' : 'Недоступна'} tone={summary.database.available ? 'good' : 'bad'} />
      <Metric label="Время работы процесса" value={durationLabel(summary.process.uptime_seconds)} />
      <Metric label="Память процесса" value={`${bytesLabel(summary.process.heap_used_bytes)} / ${bytesLabel(summary.process.heap_max_bytes)}`} />
      <Metric label="HTTP запросы" value={countLabel(summary.http.requests_total)} />
      <Metric label="Ошибки сервера" value={countLabel(summary.http.server_errors_total)} tone={summary.http.server_errors_total > 0 ? 'bad' : 'good'} />
      <Metric label="Средняя длительность" value={durationLabel(summary.http.average_duration_seconds)} />
    </div>
    <section className="company-ops-subsection"><h3>Очередь отправки</h3><p>Флаг worker_enabled относится к этому экземпляру API. Доставлено означает, что SMTP принял запрос на отправку, а не то, что письмо прочитано.</p><div className="company-ops-grid">
      <Metric label="Обработчик в этом процессе" value={summary.outbox.worker_enabled ? 'Включён' : 'Выключен'} tone={summary.outbox.worker_enabled ? 'good' : 'warm'} />
      <Metric label="Последний общий тик" value={isoLabel(summary.outbox.worker_last_tick_at)} />
      <Metric label="Ожидают" value={countLabel(counts?.pending)} />
      <Metric label="Приняты SMTP" value={countLabel(counts?.delivered)} />
      <Metric label="Ошибки отправки" value={countLabel(counts?.failed)} tone={counts?.failed ? 'bad' : undefined} />
      <Metric label="Отброшены" value={countLabel(counts?.discarded)} />
      <Metric label="Возраст старейшей готовой задачи" value={durationLabel(summary.outbox.oldest_due_age_seconds)} />
    </div></section>
    <section className="company-ops-subsection"><h3>Подключённые сервисы</h3><div className="company-ops-grid">
      <Metric label="DaData: успешные ответы" value={countLabel(upstream.dadata.success)} />
      <Metric label="DaData: тайм-ауты" value={countLabel(upstream.dadata.timeout)} tone={upstream.dadata.timeout ? 'warm' : undefined} />
      <Metric label="DaData: ошибки" value={countLabel(upstream.dadata.error)} tone={upstream.dadata.error ? 'bad' : undefined} />
      {upstream.smtp ? <>
        <Metric label="SMTP: успешная передача" value={countLabel(upstream.smtp.success)} />
        <Metric label="SMTP: тайм-ауты" value={countLabel(upstream.smtp.timeout)} tone={upstream.smtp.timeout ? 'warm' : undefined} />
        <Metric label="SMTP: ошибки" value={countLabel(upstream.smtp.error)} tone={upstream.smtp.error ? 'bad' : undefined} />
      </> : <p className="company-ops-intro">Счётчики SMTP доступны в процессе worker; состояние outbox показано выше.</p>}
    </div></section>
  </div>;
}

export function OperationsWorkspace({ permissions }: { permissions: string[] }) {
  const canMonitor = permissions.includes('monitor_read');
  const canReview = permissions.includes('company_review');
  const [tab, setTab] = useState<Tab>(canMonitor ? 'monitor' : 'queue');
  const [summary, setSummary] = useState<OpsSummary>();
  const [queue, setQueue] = useState<VerificationRequest[]>([]);
  const [drafts, setDrafts] = useState<Record<string, ReviewDraft>>({});
  const [pending, setPending] = useState<Record<string, PendingReview>>({});
  const [conflicts, setConflicts] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(false);
  const [savingId, setSavingId] = useState<string>();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [closed, setClosed] = useState(false);
  const accessGeneration = useRef(0);
  const requestPath = useCallback((id: string) => `/operations/company-verifications/${encodeURIComponent(id)}/review`, []);

  const clearProtectedState = useCallback(() => {
    accessGeneration.current += 1; setSummary(undefined); setQueue([]); setDrafts({}); setPending({}); setConflicts({}); setClosed(true); setLoading(false); setSavingId(undefined);
  }, []);
  const load = useCallback(async (target: Tab) => {
    if ((target === 'monitor' && !canMonitor) || (target === 'queue' && !canReview)) return;
    const generation = ++accessGeneration.current;
    setLoading(true); setError(''); setNotice('');
    try {
      if (target === 'monitor') {
        const result = await api.get<OpsSummary>('/operations/summary');
        if (generation === accessGeneration.current) { setSummary(result); setClosed(false); }
      } else {
        const result = await api.get<QueueResponse>('/operations/company-verifications');
        if (generation === accessGeneration.current) {
          setQueue(result.items); setClosed(false);
          setDrafts((current) => Object.fromEntries(result.items.map((item) => [item.id, current[item.id] ?? { ...emptyDraft, registry_inn: item.inn, registry_legal_name: item.legal_name }])));
        }
      }
    } catch (reason) {
      if (generation !== accessGeneration.current) return;
      if (reason instanceof RequestError && [401, 403].includes(reason.status)) {
        clearProtectedState(); setError('Доступ к панели закрыт. Предыдущие чувствительные данные скрыты.');
      } else setError(errorMessage(reason));
    } finally { if (generation === accessGeneration.current) setLoading(false); }
  }, [canMonitor, canReview, clearProtectedState]);

  useEffect(() => {
    const firstAllowed = canMonitor ? 'monitor' : canReview ? 'queue' : undefined;
    if (!firstAllowed) { clearProtectedState(); setError('У вашей учётной записи нет разрешений на панели операций.'); return; }
    if (!canMonitor) setSummary(undefined);
    if (!canReview) { setQueue([]); setDrafts({}); setPending({}); setConflicts({}); }
    setTab(firstAllowed); setClosed(false); void load(firstAllowed);
    return () => { accessGeneration.current += 1; };
  }, [canMonitor, canReview, clearProtectedState, load]);

  const updateDraft = (id: string, patch: Partial<ReviewDraft>) => {
    setDrafts((current) => ({ ...current, [id]: { ...(current[id] ?? emptyDraft), ...patch } }));
    setPending((current) => { const next = { ...current }; delete next[id]; return next; });
    setError(''); setNotice('');
  };

  const submitReview = async (item: VerificationRequest) => {
    if (!canReview || closed || savingId) return;
    const draft = drafts[item.id] ?? { ...emptyDraft, registry_inn: item.inn, registry_legal_name: item.legal_name };
    const validation = validateCompanyReview(draft, { inn: item.inn, legal_name: item.legal_name });
    if (validation) { setError(validation); return; }
    const body = { expected_version: item.version, ...draft };
    const fingerprint = JSON.stringify(body);
    const retry = pending[item.id]?.fingerprint === fingerprint ? pending[item.id] : { fingerprint, key: crypto.randomUUID() };
    setPending((current) => ({ ...current, [item.id]: retry })); setSavingId(item.id); setError(''); setNotice('');
    try {
      const response = await api.post<{ request: VerificationRequest }>(requestPath(item.id), asPayload(body), retry.key);
      setQueue((current) => current.filter((entry) => entry.id !== item.id));
      setDrafts((current) => { const next = { ...current }; delete next[item.id]; return next; });
      setPending((current) => { const next = { ...current }; delete next[item.id]; return next; });
      setConflicts((current) => { const next = { ...current }; delete next[item.id]; return next; });
      setNotice(`Решение сохранено: ${statusLabels[response.request.status] ?? response.request.status}.`);
    } catch (reason) {
      if (reason instanceof RequestError && [401, 403].includes(reason.status)) {
        clearProtectedState(); setError('Доступ закрыт. Предыдущие чувствительные данные скрыты.');
      } else if (reason instanceof RequestError && reason.status === 409) {
        setConflicts((current) => ({ ...current, [item.id]: true }));
        setError('Запись очереди изменилась. Черновик решения сохранён; перечитайте очередь перед отправкой снова.');
      } else setError(errorMessage(reason));
    } finally { setSavingId(undefined); }
  };

  if (!canMonitor && !canReview) return <section className="card company-ops-card"><h2>Панель операций</h2><p>У вашей учётной записи нет разрешений на панели операций.</p></section>;
  return <section className="card company-ops-card" aria-labelledby="operations-title">
    <div className="card-head"><h2 id="operations-title">Панель операций</h2><button type="button" className="button button-secondary button-small" onClick={() => void load(tab)} disabled={loading || closed}>{loading ? 'Обновляем…' : 'Обновить'}</button></div>
    {error && <div className="notice notice-danger" role="alert">{error}</div>}{notice && <div className="notice notice-success" role="status">{notice}</div>}
    {(canMonitor || canReview) && <div className="company-ops-tabs" role="tablist" aria-label="Разделы панели операций">
      {canMonitor && <button role="tab" aria-selected={tab === 'monitor'} className="button button-secondary button-small" onClick={() => { setTab('monitor'); void load('monitor'); }}>Мониторинг</button>}
      {canReview && <button role="tab" aria-selected={tab === 'queue'} className="button button-secondary button-small" onClick={() => { setTab('queue'); void load('queue'); }}>Проверка компаний <span className="company-ops-count">{queue.length}</span></button>}
    </div>}
    {closed && <button type="button" className="button button-secondary button-small" onClick={() => { setError(''); setClosed(false); void load(tab); }}>Повторить загрузку</button>}
    {loading && <p role="status" className="field-hint">Загружаем данные…</p>}
    {!loading && tab === 'monitor' && canMonitor && summary && <OpsMonitor summary={summary} />}
    {!loading && tab === 'queue' && canReview && <div className="company-ops-queue">
      {queue.length === 0 && <p className="field-hint">Очередь пуста или пока не загружена.</p>}
      {queue.map((item) => {
        const draft = drafts[item.id] ?? { ...emptyDraft, registry_inn: item.inn, registry_legal_name: item.legal_name };
        const validation = validateCompanyReview(draft, { inn: item.inn, legal_name: item.legal_name });
        return <article className="company-ops-review" key={item.id}>
          <div className="company-ops-review-head"><div><div className="eyebrow">ЗАПРОС · {item.id.slice(0, 8)}</div><h3>{item.snapshot.legal_name || item.legal_name}</h3></div><span className="badge badge-warm">{statusLabels[item.status] ?? item.status}</span></div>
          <dl className="company-ops-facts"><div><dt>ИНН</dt><dd>{item.snapshot.inn || item.inn}</dd></div><div><dt>Версия компании</dt><dd>{item.company_version}</dd></div><div><dt>Почта владельца</dt><dd>{item.snapshot.owner_email ? `${item.snapshot.owner_email} · ${item.snapshot.owner_email_verified ? 'подтверждена' : 'не подтверждена'}` : 'Нет данных'}</dd></div><div><dt>Сайт из профиля</dt><dd>{item.snapshot.website || 'Не указан'}</dd></div><div><dt>Создан</dt><dd>{isoLabel(item.created_at)}</dd></div></dl>
          <p className="company-ops-intro">Контакты и сайт показаны как заявленные сведения профиля. URL источника — справочная запись оператора; приложение не загружает его содержимое.</p>
          {item.source_reference && <p className="company-ops-source">Текущий источник: <span>{item.source_reference}</span></p>}
          {!item.criteria_current && <div className="notice notice-danger">Реквизиты изменились после отправки. Запрос нельзя одобрить, пока работодатель не отправит проверку актуальных данных.</div>}
          {conflicts[item.id] && <div className="notice notice-danger" role="status">Черновик сохранён в форме. Перечитайте очередь, чтобы получить актуальную версию записи.</div>}
          <div className="company-ops-form">
            <label className="field"><span>Решение</span><select value={draft.outcome} onChange={(event) => updateDraft(item.id, { outcome: event.target.value as ReviewDraft['outcome'] })}><option value="approve">Подтвердить по ручной проверке</option><option value="request_changes">Запросить уточнения</option><option value="reject">Отклонить</option></select></label>
            <label className="field"><span>ИНН из проверяемого источника</span><input inputMode="numeric" value={draft.registry_inn} onChange={(event) => updateDraft(item.id, { registry_inn: event.target.value })} /></label>
            <label className="field"><span>Юридическое наименование в источнике</span><input value={draft.registry_legal_name} maxLength={300} onChange={(event) => updateDraft(item.id, { registry_legal_name: event.target.value })} /></label>
            <label className="field"><span>Статус в источнике</span><select value={draft.registry_status} onChange={(event) => updateDraft(item.id, { registry_status: event.target.value as ReviewDraft['registry_status'] })}><option value="UNKNOWN">Нет данных</option><option value="ACTIVE">Действует</option><option value="LIQUIDATING">Ликвидируется</option><option value="TERMINATED">Прекратила деятельность</option></select></label>
            <label className="field"><span>Ссылка или идентификатор проверенного источника</span><input value={draft.evidence_reference} maxLength={1000} placeholder="Ссылка на запись или номер проверки" onChange={(event) => updateDraft(item.id, { evidence_reference: event.target.value })} /></label>
            <label className="company-ops-check"><input type="checkbox" checked={draft.representative_reviewed} onChange={(event) => updateDraft(item.id, { representative_reviewed: event.target.checked })} /><span>Я отдельно проверил(а), что заявитель представляет компанию</span></label>
            <label className="field company-ops-span-all"><span>Комментарий решения</span><textarea rows={3} maxLength={2000} value={draft.comment} onChange={(event) => updateDraft(item.id, { comment: event.target.value })} /></label>
          </div>
          {draft.outcome === 'approve' && !isValidRussianInn(draft.registry_inn) && <p className="field-hint">Для одобрения нужен корректный ИНН из источника.</p>}
          {validation && <p className="field-hint">{validation}</p>}
          {item.demo && <p className="company-ops-demo">Заявка помечена как DEMO: решение останется демонстрационным и не подтверждает реальную организацию.</p>}
          <div className="company-ops-actions"><button className="button button-primary button-small" type="button" onClick={() => void submitReview(item)} disabled={Boolean(savingId) || !item.criteria_current || Boolean(validation)}>{savingId === item.id ? 'Сохраняем…' : 'Сохранить решение'}</button>{conflicts[item.id] && <button className="button button-secondary button-small" type="button" onClick={() => void load('queue')} disabled={loading}>Перечитать очередь</button>}</div>
        </article>;
      })}
    </div>}
  </section>;
}
