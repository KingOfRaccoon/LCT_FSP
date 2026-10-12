import { useEffect, useRef, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, isApplicationContactRequestCurrent, RequestError } from '../../api';

export function EmployerApplicationsPanel() {
  const query = useQueryClient();
  const [updatingId, setUpdatingId] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const applicationsQuery = useQuery({
    queryKey: ['companyApplications'],
    queryFn: () => api.get<{ items: Array<{
      id: string;
      vacancy_id: string;
      vacancy_title: string;
      candidate_id: string;
      candidate_name: string;
      candidate_headline?: string | null;
      declared_grade?: string | null;
      verified_grade?: string | null;
      cover_letter?: string | null;
      status: string;
      created_at: string;
      candidate_processing_active: boolean;
      contact_access_revoked: boolean;
      has_selected_contacts: boolean;
    }> }>('/vacancies/company/applications')
  });

  const updateStatus = async (id: string, status: 'reviewed' | 'accepted' | 'declined') => {
    setUpdatingId(id);
    setError('');
    setNotice('');
    try {
      await api.patch(`/vacancies/applications/${id}/status`, { status });
      await query.invalidateQueries({ queryKey: ['companyApplications'] });
      setNotice(`Статус отклика обновлён: ${status === 'accepted' ? 'принят к диалогу' : status === 'declined' ? 'отклонён' : 'просмотрен'}.`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setUpdatingId(null);
    }
  };

  const items = applicationsQuery.isSuccess && !applicationsQuery.isFetching ? applicationsQuery.data.items : [];

  return (
    <section className="card" style={{ marginTop: '24px', padding: '24px' }}>
      <div className="card-head">
        <div>
          <h2>Входящие отклики кандидатов</h2>
          <p className="field-hint">Кандидаты, откликнувшиеся на открытые вакансии компании напрямую.</p>
        </div>
        <span className="badge badge-neutral">{items.length} откликов</span>
      </div>

      {error && <div className="error-box" role="alert"><span>{error}</span></div>}
      {notice && <div className="notice notice-success" role="status">{notice}</div>}

      {applicationsQuery.isPending || applicationsQuery.isFetching ? (
        <div className="loading"><span className="spinner" aria-hidden="true" />Загружаем отклики…</div>
      ) : applicationsQuery.isError ? (
        <div className="error-box" role="alert"><span>Не удалось загрузить отклики. Личные данные кандидатов скрыты.</span><button className="button button-secondary button-small" type="button" onClick={() => void applicationsQuery.refetch()}>Повторить</button></div>
      ) : items.length === 0 ? (
        <p className="field-hint" style={{ marginTop: '12px' }}>
          Новых откликов пока нет. Опубликуйте вакансию из активной потребности, чтобы соискатели могли откликнуться напрямую.
        </p>
      ) : (
        <div style={{ display: 'grid', gap: '12px', marginTop: '16px' }}>
          {items.map((app) => (
            <div key={app.id} className="card" style={{ padding: '16px', background: 'var(--bg-subtle, #fafafa)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <div className="eyebrow">ВАКАНСИЯ: {app.vacancy_title}</div>
                  {app.candidate_processing_active ? <><h3 style={{ margin: '4px 0' }}>{app.candidate_name}</h3><p style={{ margin: 0, fontSize: '13px', color: 'var(--muted)' }}>{app.candidate_headline || 'Заголовок не указан'} · {app.verified_grade ? `Подтверждённый грейд: ${app.verified_grade}` : app.declared_grade ? `Заявленный грейд: ${app.declared_grade}` : 'Грейд не указан'}</p></> : <p className="field-hint">Кандидат отозвал согласие на обработку. Личные данные скрыты.</p>}
                </div>
                <span className={`badge ${
                  app.status === 'accepted' ? 'badge-good' :
                  app.status === 'declined' ? 'badge-warm' :
                  app.status === 'reviewed' ? 'badge-neutral' : 'badge-warm'
                }`}>
                  {app.status === 'submitted' ? 'Новый отклик' :
                   app.status === 'reviewed' ? 'Просмотрен' :
                   app.status === 'accepted' ? 'Принят' : 'Отклонён'}
                </span>
              </div>

              {app.candidate_processing_active && app.cover_letter && (
                <div style={{ marginTop: '10px', fontSize: '13px', background: '#fff', padding: '10px', borderRadius: '6px' }}>
                  <strong>Сопроводительное письмо:</strong> {app.cover_letter}
                </div>
              )}

              {app.contact_access_revoked ? <p className="field-hint">Доступ к выбранным контактам отозван.</p> : !app.candidate_processing_active ? <p className="field-hint">Доступ к личным данным и контактам приостановлен.</p> : !app.has_selected_contacts ? <p className="field-hint">Контакты не переданы.</p> : <ApplicationContactAccess applicationId={app.id} available onAccessUnavailable={() => { void query.invalidateQueries({ queryKey: ['companyApplications'] }); }} />}

              <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end', marginTop: '12px' }}>
                {app.status === 'submitted' && (
                  <button
                    className="button button-secondary button-small"
                    disabled={updatingId === app.id}
                    onClick={() => void updateStatus(app.id, 'reviewed')}
                  >
                    Отметить просмотренным
                  </button>
                )}
                {app.status !== 'accepted' && (
                  <button
                    className="button button-primary button-small"
                    disabled={updatingId === app.id}
                    onClick={() => void updateStatus(app.id, 'accepted')}
                  >
                    Принять к диалогу
                  </button>
                )}
                {app.status !== 'declined' && (
                  <button
                    className="button button-secondary button-small"
                    disabled={updatingId === app.id}
                    onClick={() => void updateStatus(app.id, 'declined')}
                  >
                    Отклонить
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function ApplicationContactAccess({ applicationId, available, onAccessUnavailable }: { applicationId: string; available: boolean; onAccessUnavailable: () => void }) {
  const [revealed, setRevealed] = useState(false);
  const [loading, setLoading] = useState(false);
  const [channels, setChannels] = useState<Array<{ type: string; value: string }> | null>(null);
  const [error, setError] = useState('');
  const requestId = useRef(0);
  const accessAvailable = useRef(available);
  accessAvailable.current = available;

  const clear = () => {
    requestId.current += 1;
    setRevealed(false);
    setChannels(null);
    setError('');
    setLoading(false);
  };

  const load = async () => {
    if (!accessAvailable.current) return;
    const id = ++requestId.current;
    setChannels(null);
    setError('');
    setLoading(true);
    try {
      const result = await api.get<{ application_id: string; channels: Array<{ type: string; value: string }> }>(`/vacancies/applications/${applicationId}/contacts`);
      if (isApplicationContactRequestCurrent(id, requestId.current, accessAvailable.current)) {
        setChannels(result.channels);
        setRevealed(true);
      }
    } catch (err) {
      if (isApplicationContactRequestCurrent(id, requestId.current, accessAvailable.current)) {
        setError(errorMessage(err));
        setRevealed(false);
        if (err instanceof RequestError && err.status === 404) onAccessUnavailable();
      }
    } finally {
      if (isApplicationContactRequestCurrent(id, requestId.current, accessAvailable.current)) setLoading(false);
    }
  };

  useEffect(() => {
    if (!available) clear();
  }, [available]);

  useEffect(() => () => { requestId.current += 1; }, []);

  useEffect(() => {
    if (!revealed || !available) return;
    const refresh = () => { if (document.visibilityState === 'visible') void load(); };
    const interval = window.setInterval(refresh, 30_000);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener('focus', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, [revealed, available, applicationId]);

  return <div className="form-footer" style={{ justifyContent: 'flex-start', marginTop: '12px' }}>
    {!revealed ? <button className="button button-secondary button-small" type="button" disabled={!available || loading} onClick={() => void load()}>{loading ? 'Проверяем доступ…' : 'Показать выбранные контакты'}</button> : <button className="text-button" type="button" onClick={clear}>Скрыть контакты</button>}
    {error && <span className="field-hint" role="alert">Не удалось подтвердить доступ к контактам: {error}</span>}
    {available && channels && <div className="contact-reveal">{channels.length ? channels.map((channel) => <div key={channel.type}><small>{channel.type}</small><strong>{channel.value}</strong></div>) : <p className="field-hint">Контакты больше недоступны.</p>}</div>}
  </div>;
}
