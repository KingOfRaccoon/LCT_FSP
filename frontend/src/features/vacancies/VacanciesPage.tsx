import { useState, type FormEvent } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, type CandidateApplication, type PublicVacancy } from '../../api';

export function VacanciesPage() {
  const query = useQueryClient();
  const [tab, setTab] = useState<'vacancies' | 'my_applications'>('vacancies');
  const [gradeFilter, setGradeFilter] = useState<string>('');
  const [applyingVacancy, setApplyingVacancy] = useState<PublicVacancy | null>(null);
  const [coverLetter, setCoverLetter] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const vacanciesQuery = useQuery({
    queryKey: ['publicVacancies', gradeFilter],
    queryFn: () => api.get<{ items: PublicVacancy[] }>(`/vacancies${gradeFilter ? `?grade=${gradeFilter}` : ''}`)
  });

  const applicationsQuery = useQuery({
    queryKey: ['myApplications'],
    queryFn: () => api.get<{ items: CandidateApplication[] }>('/me/applications')
  });

  const handleApply = async (e: FormEvent) => {
    e.preventDefault();
    if (!applyingVacancy) return;
    setSubmitting(true);
    setError('');
    setNotice('');
    try {
      await api.post(`/vacancies/${applyingVacancy.id}/apply`, { cover_letter: coverLetter.trim() || null });
      setNotice(`Отклик на вакансию «${applyingVacancy.title}» успешно отправлен компании!`);
      setApplyingVacancy(null);
      setCoverLetter('');
      await query.invalidateQueries({ queryKey: ['myApplications'] });
      setTab('my_applications');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const vacancies = vacanciesQuery.data?.items ?? [];
  const applications = applicationsQuery.data?.items ?? [];

  const money = (kopecks: number) => new Intl.NumberFormat('ru-RU').format(Math.round(kopecks / 100));
  const gradeLabel = (g?: string | null) => g === 'middle' ? 'Middle' : g === 'junior' ? 'Junior' : g;
  const workModeLabel = (m: string) => m === 'remote' ? 'Удалённо' : m === 'hybrid' ? 'Гибрид' : 'Офис';

  return (
    <>
      <div className="page-header">
        <div>
          <div className="eyebrow">ПРЯМОЙ ПОИСК И ОТКЛИК</div>
          <h1>Открытые вакансии и отклики</h1>
          <p>Кандидат может самостоятельно найти подходящую вакансию и проявить инициативу. Зарплатные вилки указаны в рублях.</p>
        </div>
      </div>

      {error && <div className="error-box" role="alert"><span>{error}</span></div>}
      {notice && <div className="notice notice-success" role="status">{notice}</div>}

      <div className="tabs-row" style={{ display: 'flex', gap: '8px', marginBottom: '20px' }}>
        <button
          className={`button ${tab === 'vacancies' ? 'button-primary' : 'button-secondary'}`}
          onClick={() => setTab('vacancies')}
        >
          Каталог вакансий ({vacancies.length})
        </button>
        <button
          className={`button ${tab === 'my_applications' ? 'button-primary' : 'button-secondary'}`}
          onClick={() => setTab('my_applications')}
        >
          Мои отклики ({applications.length})
        </button>
      </div>

      {tab === 'vacancies' && (
        <section>
          <div className="card" style={{ marginBottom: '20px', padding: '16px' }}>
            <div style={{ display: 'flex', gap: '16px', alignItems: 'center' }}>
              <label className="field" style={{ margin: 0 }}>
                <span>Фильтр по уровню</span>
                <select value={gradeFilter} onChange={(e) => setGradeFilter(e.target.value)}>
                  <option value="">Все уровни</option>
                  <option value="junior">Junior</option>
                  <option value="middle">Middle</option>
                </select>
              </label>
              <span className="field-hint" style={{ marginTop: '18px' }}>
                Все открытые вакансии проверены платформой на прозрачность условий.
              </span>
            </div>
          </div>

          {vacanciesQuery.isPending ? (
            <div className="loading"><span className="spinner" aria-hidden="true" />Загружаем вакансии…</div>
          ) : vacancies.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">⌕</div>
              <h3>Пока нет открытых вакансий</h3>
              <p>Компании могут также находить ваш профиль напрямую через обратный подбор.</p>
            </div>
          ) : (
            <div className="vacancy-list" style={{ display: 'grid', gap: '16px' }}>
              {vacancies.map((v) => (
                <article key={v.id} className="card" style={{ padding: '24px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '12px' }}>
                    <div>
                      <div className="eyebrow">{v.company_name} {v.company_industry ? `· ${v.company_industry}` : ''}</div>
                      <h2 style={{ margin: '4px 0 8px 0', fontSize: '20px' }}>{v.title}</h2>
                      <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                        <span className="badge badge-good">{gradeLabel(v.target_grade)}</span>
                        <span className="badge badge-neutral">{workModeLabel(v.work_mode)}</span>
                        {v.city && <span className="badge badge-neutral">{v.city}</span>}
                        <span className="badge badge-neutral">{v.workload_hours_per_week} ч/нед</span>
                      </div>
                    </div>
                    <div style={{ textAlign: 'right' }}>
                      <div className="salary-highlight" style={{ fontSize: '18px', fontWeight: 600 }}>
                        {money(v.salary.min_kopecks)}–{money(v.salary.max_kopecks)} ₽
                      </div>
                      <small style={{ color: 'var(--muted)' }}>
                        {v.salary.basis === 'gross' ? 'до вычета налогов' : 'на руки'}
                      </small>
                    </div>
                  </div>

                  <p style={{ margin: '12px 0 16px 0', lineHeight: 1.5, color: 'var(--text)' }}>
                    {v.description}
                  </p>

                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', paddingTop: '12px', borderTop: '1px solid var(--border)' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                      <span className="badge badge-good">🛡️ Проверенный работодатель · Trust Score 96/100</span>
                    </div>
                    <button
                      className="button button-primary button-small"
                      onClick={() => setApplyingVacancy(v)}
                    >
                      Откликнуться на вакансию →
                    </button>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      )}

      {tab === 'my_applications' && (
        <section>
          {applicationsQuery.isPending ? (
            <div className="loading"><span className="spinner" aria-hidden="true" />Загружаем отклики…</div>
          ) : applications.length === 0 ? (
            <div className="empty">
              <div className="empty-icon">✉</div>
              <h3>Вы ещё не отправляли откликов</h3>
              <p>Перейдите в каталог вакансий, чтобы выбрать интересующую позицию.</p>
            </div>
          ) : (
            <div className="application-list" style={{ display: 'grid', gap: '16px' }}>
              {applications.map((app) => (
                <article key={app.id} className="card" style={{ padding: '20px' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <div>
                      <div className="eyebrow">{app.company_name}</div>
                      <h3 style={{ margin: '4px 0 8px 0' }}>{app.vacancy_title}</h3>
                      <div style={{ display: 'flex', gap: '8px' }}>
                        <span className="badge badge-neutral">{gradeLabel(app.target_grade)}</span>
                        <span className="badge badge-neutral">{workModeLabel(app.work_mode)}</span>
                        <span className="badge badge-neutral">
                          {money(app.salary.min_kopecks)}–{money(app.salary.max_kopecks)} ₽
                        </span>
                      </div>
                    </div>
                    <span className={`badge ${
                      app.status === 'accepted' ? 'badge-good' :
                      app.status === 'declined' ? 'badge-warm' :
                      app.status === 'reviewed' ? 'badge-neutral' : 'badge-warm'
                    }`}>
                      {app.status === 'submitted' ? 'Отправлен' :
                       app.status === 'reviewed' ? 'Просмотрен' :
                       app.status === 'accepted' ? 'Принят к диалогу' : 'Отклонён'}
                    </span>
                  </div>

                  {app.cover_letter && (
                    <div style={{ marginTop: '12px', padding: '10px 14px', background: 'var(--bg-subtle, #f4f4f7)', borderRadius: '6px' }}>
                      <small style={{ color: 'var(--muted)', display: 'block', marginBottom: '4px' }}>Сопроводительное письмо</small>
                      <p style={{ margin: 0, fontSize: '13px' }}>{app.cover_letter}</p>
                    </div>
                  )}

                  <div style={{ marginTop: '12px', fontSize: '12px', color: 'var(--muted)' }}>
                    Отправлен: {new Date(app.created_at).toLocaleString('ru-RU')}
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      )}

      {/* Modal Dialog for applying */}
      {applyingVacancy && (
        <div className="modal-backdrop" style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000
        }}>
          <div className="card" style={{ maxWidth: '540px', width: '90%', padding: '24px', background: '#fff' }}>
            <div className="card-head" style={{ marginBottom: '16px' }}>
              <h2>Отклик на вакансию</h2>
              <button
                className="button button-secondary button-small"
                onClick={() => setApplyingVacancy(null)}
              >
                ✕
              </button>
            </div>
            <p><strong>{applyingVacancy.title}</strong> в {applyingVacancy.company_name}</p>
            <p style={{ fontSize: '13px', color: 'var(--muted)' }}>
              Зарплатная вилка: {money(applyingVacancy.salary.min_kopecks)}–{money(applyingVacancy.salary.max_kopecks)} ₽.
              Ваш профиль и подтверждённые навыки станут доступны компании.
            </p>

            <form onSubmit={handleApply} className="form-stack">
              <label className="field">
                <span>Сопроводительное письмо (необязательно)</span>
                <textarea
                  rows={4}
                  maxLength={1000}
                  placeholder="Расскажите, почему вам интересна эта роль и какие задачи привлекают…"
                  value={coverLetter}
                  onChange={(e) => setCoverLetter(e.target.value)}
                />
              </label>

              <div style={{ display: 'flex', gap: '8px', justifyContent: 'flex-end', marginTop: '16px' }}>
                <button
                  type="button"
                  className="button button-secondary"
                  onClick={() => setApplyingVacancy(null)}
                >
                  Отмена
                </button>
                <button
                  type="submit"
                  className="button button-primary"
                  disabled={submitting}
                >
                  {submitting ? 'Отправляем…' : 'Подтвердить отклик'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  );
}
