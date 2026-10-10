import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorMessage, RequestError, type Company } from '../../api';
import { isValidRussianInn, verificationBadgeModel, type PublicCompanyVerificationSummary } from './logic';
import './company-ops.css';

type CompanyWithRequisites = Company & { inn?: string | null; legal_name?: string | null };
type RequestDto = {
  id: string; version: number; company_version: number; criteria_current: boolean; status: string; demo: boolean;
  inn: string; legal_name: string; snapshot: { website?: string | null; contact_email?: string | null; owner_email?: string | null; owner_email_verified?: boolean };
  provider?: string | null; source_type: string; source_label: string; source_reference: string | null; checked_at: string | null;
  expires_at: string | null; review_notes: string | null; created_at: string;
};
type VerificationResponse = {
  public: PublicCompanyVerificationSummary;
  owner: { summary: { company_version: number; current_status: string | null; criteria_current: boolean }; current_request: RequestDto | null; history: RequestDto[] };
  current_request?: RequestDto | null;
};
type OwnerView = VerificationResponse['owner'];

const requestStatus: Record<string, string> = {
  pending: 'Ожидает проверки', registry_confirmed: 'Сверено с реестром', reviewed: 'Проверена оператором',
  needs_changes: 'Нужны уточнения', rejected: 'Отклонена', unavailable: 'Проверка недоступна',
  expired: 'Срок истёк', superseded: 'Данные изменились',
};
const asPayload = (payload: object) => payload as import('../../api').ApiPayload;
const dateLabel = (value?: string | null) => value ? new Date(value).toLocaleString('ru-RU') : '—';

export function CompanyVerificationBadge({ summary }: { summary?: PublicCompanyVerificationSummary }) {
  const badge = verificationBadgeModel(summary);
  return <span className={`badge badge-${badge.tone}`} aria-label={`Статус проверки компании: ${badge.label}`}>{badge.label}</span>;
}

export function CompanyVerificationPanel({ company: inputCompany, onChanged, disabledReason }: {
  company: Company;
  onChanged: () => Promise<void>;
  disabledReason?: string;
}) {
  const company = inputCompany as CompanyWithRequisites;
  const [inn, setInn] = useState(company.inn ?? '');
  const [legalName, setLegalName] = useState(company.legal_name ?? '');
  const [publicSummary, setPublicSummary] = useState<PublicCompanyVerificationSummary>();
  const [owner, setOwner] = useState<OwnerView>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [needsReload, setNeedsReload] = useState(false);
  const pending = useRef<{ action: string; fingerprint: string; key: string } | undefined>(undefined);
  const accessGeneration = useRef(0);
  const closed = useRef(false);

  const clearSensitive = useCallback(() => {
    accessGeneration.current += 1; closed.current = true;
    setOwner(undefined); setPublicSummary(undefined); setLoading(false); setSaving(false);
  }, []);
  const refresh = useCallback(async () => {
    const generation = ++accessGeneration.current;
    try {
      const response = await api.get<VerificationResponse>('/me/company/verification');
      if (generation !== accessGeneration.current) return;
      setPublicSummary(response.public); setOwner(response.owner); closed.current = false;
    } catch (reason) {
      if (generation !== accessGeneration.current) return;
      if (reason instanceof RequestError && [401, 403].includes(reason.status)) {
        clearSensitive(); setError('Доступ к данным проверки закрыт. Предыдущие сведения скрыты.');
      } else setError(errorMessage(reason));
    } finally { if (generation === accessGeneration.current) setLoading(false); }
  }, [clearSensitive]);

  useEffect(() => {
    void refresh();
    return () => { accessGeneration.current += 1; };
  }, [refresh]);
  useEffect(() => {
    setInn(company.inn ?? ''); setLegalName(company.legal_name ?? '');
  }, [company.id, company.version, company.inn, company.legal_name]);

  const saveRequisites = async () => {
    if (disabledReason || saving || closed.current) return;
    if (!isValidRussianInn(inn)) { setError('Проверьте ИНН: контрольные цифры не совпадают.'); return; }
    if (!legalName.trim()) { setError('Укажите юридическое наименование компании.'); return; }
    setSaving(true); setError(''); setNotice('');
    try {
      const payload = { expected_version: company.version, inn: inn.replace(/\s/g, ''), legal_name: legalName.trim() };
      const fingerprint = JSON.stringify(payload);
      const request = pending.current?.action === 'save-requisites' && pending.current.fingerprint === fingerprint
        ? pending.current : { action: 'save-requisites', fingerprint, key: crypto.randomUUID() };
      pending.current = request;
      await api.patch('/me/company', asPayload(payload), request.key);
      pending.current = undefined;
      setNeedsReload(false);
      await onChanged();
      await refresh();
      setNotice('Реквизиты сохранены. Теперь можно отправить запрос на проверку.');
    } catch (reason) {
      if (reason instanceof RequestError && [401, 403].includes(reason.status)) { clearSensitive(); setError('Доступ закрыт. Предыдущие сведения скрыты.'); }
      else if (reason instanceof RequestError && reason.status === 409) { setNeedsReload(true); setError('Профиль компании изменился. Ваша форма сохранена; перечитайте актуальные данные перед повторным сохранением.'); }
      else setError(errorMessage(reason));
    } finally { if (!closed.current) setSaving(false); }
  };

  const requestVerification = async () => {
    if (disabledReason || saving || closed.current) return;
    if (!isValidRussianInn(inn) || !legalName.trim()) { setError('Сначала заполните и сохраните ИНН и юридическое наименование.'); return; }
    const payload = { expected_company_version: company.version };
    const fingerprint = JSON.stringify(payload);
    const request = pending.current?.action === 'request' && pending.current.fingerprint === fingerprint
      ? pending.current : { action: 'request', fingerprint, key: crypto.randomUUID() };
    pending.current = request; setSaving(true); setError(''); setNotice('');
    try {
      const response = await api.post<VerificationResponse>('/me/company/verification/requests', payload, request.key);
      pending.current = undefined; setPublicSummary(response.public); setOwner(response.owner);
      setNotice(response.public.demo ? 'Запрос сохранён. Результат будет помечен как демонстрационный.' : 'Запрос на проверку отправлен.');
      await onChanged();
    } catch (reason) {
      if (reason instanceof RequestError && [401, 403].includes(reason.status)) { clearSensitive(); setError('Доступ закрыт. Предыдущие сведения скрыты.'); }
      else if (reason instanceof RequestError && reason.status === 409) { setNeedsReload(true); setError('Данные компании обновились. Запрос не отправлен; перечитайте профиль и повторите действие.'); }
      else setError(errorMessage(reason));
    } finally { if (!closed.current) setSaving(false); }
  };

  const checkRegistry = async () => {
    const requestRow = owner?.current_request;
    if (disabledReason || saving || closed.current || !requestRow || !requestRow.criteria_current || !['pending', 'registry_confirmed', 'unavailable'].includes(requestRow.status)) return;
    const payload = { expected_version: requestRow.version };
    const fingerprint = JSON.stringify(payload);
    const action = `registry-check:${requestRow.id}`;
    const request = pending.current?.action === action && pending.current.fingerprint === fingerprint
      ? pending.current : { action, fingerprint, key: crypto.randomUUID() };
    pending.current = request; setSaving(true); setError(''); setNotice('');
    try {
      const response = await api.post<VerificationResponse>(`/me/company/verification/requests/${encodeURIComponent(requestRow.id)}/registry-check`, payload, request.key);
      pending.current = undefined;
      setPublicSummary(response.public); setOwner(response.owner);
      const updated = response.current_request ?? response.owner.current_request;
      setNotice(updated?.status === 'unavailable'
        ? 'Сервис реестра недоступен. Автоматическая сверка не подтвердила данные; запрос можно продолжить вручную.'
        : response.public.demo
          ? 'Ответ сервиса получен. Это DEMO результат, он не подтверждает реальную компанию.'
          : 'Ответ сервиса реестра получен. Сверка не подтверждает полномочия представителя.');
      await onChanged();
    } catch (reason) {
      if (reason instanceof RequestError && [401, 403].includes(reason.status)) { clearSensitive(); setError('Доступ закрыт. Предыдущие сведения скрыты.'); }
      else if (reason instanceof RequestError && reason.status === 409) { setNeedsReload(true); setError('Запрос на проверку изменился. Ваш профиль не перезаписан; перечитайте состояние перед повторной сверкой.'); }
      else setError(errorMessage(reason));
    } finally { if (!closed.current) setSaving(false); }
  };

  const badge = verificationBadgeModel(publicSummary);
  return <section className="card company-ops-card" aria-labelledby="company-verification-title">
    <div className="card-head"><h2 id="company-verification-title">Проверка компании</h2><CompanyVerificationBadge summary={publicSummary} /></div>
    <p className="company-ops-intro">Проверка сверяет реквизиты и сведения о представителе. Она не подтверждает право подписывать договоры и сама по себе не открывает контакты кандидатов.</p>
    {error && <div className="notice notice-danger" role="alert">{error}</div>}
    {notice && <div className="notice notice-success" role="status">{notice}</div>}
    {disabledReason && <p className="field-hint">{disabledReason}</p>}
    <div className="company-ops-fields">
      <label className="field"><span>ИНН</span><input inputMode="numeric" autoComplete="off" value={inn} onChange={(event) => { setInn(event.target.value); setError(''); pending.current = undefined; }} disabled={Boolean(disabledReason) || saving} aria-describedby="company-inn-hint" /><small id="company-inn-hint">10 цифр для организации или 12 для ИП. Проверяется контрольная сумма.</small></label>
      <label className="field"><span>Юридическое наименование</span><input autoComplete="organization" value={legalName} onChange={(event) => { setLegalName(event.target.value); setError(''); pending.current = undefined; }} disabled={Boolean(disabledReason) || saving} maxLength={300} /></label>
    </div>
    <div className="company-ops-actions">
      <button className="button button-secondary button-small" type="button" onClick={() => void saveRequisites()} disabled={Boolean(disabledReason) || saving || closed.current}>{saving ? 'Сохраняем…' : 'Сохранить реквизиты'}</button>
      <button className="button button-primary button-small" type="button" onClick={() => void requestVerification()} disabled={Boolean(disabledReason) || saving || closed.current || loading || owner?.current_request?.status === 'pending'}>{saving ? 'Отправляем…' : owner?.current_request?.status === 'pending' ? 'Запрос уже в очереди' : 'Запросить проверку'}</button>
      {owner?.current_request && owner.current_request.criteria_current && ['pending', 'registry_confirmed', 'unavailable'].includes(owner.current_request.status) && <button className="button button-secondary button-small" type="button" onClick={() => void checkRegistry()} disabled={Boolean(disabledReason) || saving || closed.current}>{saving ? 'Проверяем…' : 'Проверить по реестру'}</button>}
      {needsReload && <button className="button button-secondary button-small" type="button" onClick={() => { setNeedsReload(false); void onChanged().then(refresh).catch((reason) => setError(errorMessage(reason))); }}>Перечитать профиль</button>}
      {closed.current && <button className="button button-secondary button-small" type="button" onClick={() => { setError(''); void refresh(); }}>Перезагрузить</button>}
    </div>
    {owner?.current_request && owner.current_request.criteria_current && ['pending', 'registry_confirmed', 'unavailable'].includes(owner.current_request.status) && <p className="company-ops-intro">Сверка с реестром необязательна и запускается только по вашему нажатию. Если внешний сервис не настроен или недоступен, статус останется «недоступна»; автоматическая сверка не заменяет проверку оператора.</p>}
    {publicSummary && <div className="company-ops-meta">
      <p><strong>Результат:</strong> {badge.label}</p>
      <p><strong>Обновлено:</strong> {dateLabel(publicSummary.checked_at)}{publicSummary.expires_at ? ` · срок до ${dateLabel(publicSummary.expires_at)}` : ''}</p>
      {publicSummary.demo && <p className="company-ops-demo">Демо-режим: сведения не являются подтверждением реальной компании.</p>}
      <ul aria-label="Что проверяется">{(publicSummary.checks ?? []).map((check) => <li key={check.code}><span>{check.code === 'registry' ? 'Сведения реестра' : check.code === 'legal_name' ? 'Юридическое наименование' : check.code === 'corporate_email' ? 'Домен подтверждённой почты владельца / заявленный сайт' : 'Представитель'}</span><span>{check.status === 'passed' ? 'Совпадает' : check.status === 'failed' ? 'Есть расхождение' : 'Нет данных'}</span></li>)}</ul>
    </div>}
    {loading && <p className="field-hint" role="status">Загружаем состояние проверки…</p>}
    {owner?.current_request && <div className="company-ops-request"><h3>Последний запрос · {owner.current_request.demo ? 'ДЕМО · ' : ''}{requestStatus[owner.current_request.status] ?? owner.current_request.status}</h3><p>Создан: {dateLabel(owner.current_request.created_at)} · версия реквизитов: {owner.current_request.company_version}</p><p>Источник: {owner.current_request.source_label || 'Операторская проверка'}</p>{!owner.current_request.criteria_current && <p className="company-ops-demo">Реквизиты изменились после запроса. Создайте новый запрос, чтобы проверить актуальную версию.</p>}{owner.current_request.review_notes && <p>Комментарий оператора: {owner.current_request.review_notes}</p>}</div>}
  </section>;
}
