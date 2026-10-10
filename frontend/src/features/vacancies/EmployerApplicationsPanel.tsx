import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage } from '../../api';

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

  const items = applicationsQuery.data?.items ?? [];

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

      {applicationsQuery.isPending ? (
        <div className="loading"><span className="spinner" aria-hidden="true" />Загружаем отклики…</div>
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
                  <h3 style={{ margin: '4px 0' }}>{app.candidate_name}</h3>
                  <p style={{ margin: 0, fontSize: '13px', color: 'var(--muted)' }}>
                    {app.candidate_headline || 'Backend Python'} ·
                    {app.verified_grade ? ` Подтверждённый грейд: ${app.verified_grade}` : app.declared_grade ? ` Заявленный: ${app.declared_grade}` : ' Грейд не указан'}
                  </p>
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

              {app.cover_letter && (
                <div style={{ marginTop: '10px', fontSize: '13px', background: '#fff', padding: '10px', borderRadius: '6px' }}>
                  <strong>Сопроводительное письмо:</strong> {app.cover_letter}
                </div>
              )}

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
