import { useEffect, useState } from 'react';
import type { ProctoringReportData } from './proctoring-types.ts';
import {
  loadManagerReviewQueue,
  fetchServerReviewQueue,
  getFraudStatusBadgeInfo,
  getActiveFraudBan,
} from './proctoring-storage.ts';
import { ProctoringReport } from './ProctoringReport.tsx';
import './proctoring.css';

export function ProctoringQueuePage() {
  const [reports, setReports] = useState<ProctoringReportData[]>(() => loadManagerReviewQueue());
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | 'review_required' | 'confirmed_fraud' | 'clean'>('all');

  const refreshQueue = () => {
    fetchServerReviewQueue().then(setReports);
  };

  useEffect(() => {
    fetchServerReviewQueue().then(setReports);
  }, []);

  const selectedReport = reports.find((r) => r.attempt_id === selectedAttemptId);

  const pendingCount = reports.filter((r) => r.review_status === 'review_required').length;
  const fraudCount = reports.filter((r) => r.review_status === 'confirmed_fraud').length;
  const cleanCount = reports.filter(
    (r) => r.review_status === 'auto_passed' || r.review_status === 'dismissed_clean'
  ).length;

  const filteredReports = reports.filter((r) => {
    if (filter === 'review_required') return r.review_status === 'review_required';
    if (filter === 'confirmed_fraud') return r.review_status === 'confirmed_fraud';
    if (filter === 'clean')
      return r.review_status === 'auto_passed' || r.review_status === 'dismissed_clean';
    return true;
  });

  return (
    <div className="proctoring-queue-page" style={{ maxWidth: '1100px', margin: '0 auto' }}>
      <div className="page-header">
        <div>
          <div className="eyebrow">КОНТРОЛЬ РЕГЛАМЕНТА И ФРОДА</div>
          <h1>Проверка видеозаписей и подтверждение оценок</h1>
          <p>
            Кабинет сотрудника платформы и работодателя. Просмотр сохранённых видеозаписей сессий, проверка подозрительных моментов и подтверждение честности сдачи экзаменов кандидатами.
          </p>
        </div>
        <div className="page-header-action">
          <button type="button" className="button button-secondary" onClick={refreshQueue}>
            ↻ Обновить сессии
          </button>
        </div>
      </div>

      {/* Summary Stat Grid */}
      <div className="stat-grid" style={{ marginBottom: '1.5rem' }}>
        <div className="stat-card">
          <div className="stat-top">
            <span>Ожидают решения</span>
            <span className={`stat-dot ${pendingCount > 0 ? 'warm' : 'good'}`} />
          </div>
          <strong>{pendingCount}</strong>
          <small>Требуют проверки экспертом</small>
        </div>

        <div className="stat-card">
          <div className="stat-top">
            <span>Подтверждённый фрод</span>
            <span className="stat-dot danger" />
          </div>
          <strong>{fraudCount}</strong>
          <small>Аннулировано, бан на 30 дней</small>
        </div>

        <div className="stat-card">
          <div className="stat-top">
            <span>Подтверждено честно</span>
            <span className="stat-dot good" />
          </div>
          <strong>{cleanCount}</strong>
          <small>Грейд зачтён кандидату</small>
        </div>

        <div className="stat-card">
          <div className="stat-top">
            <span>Всего сессий в архиве</span>
            <span className="stat-dot" />
          </div>
          <strong>{reports.length}</strong>
          <small>С видеозаписями и телеметрией</small>
        </div>
      </div>

      {/* Detailed session inspection view if selected */}
      {selectedReport ? (
        <div>
          <div style={{ marginBottom: '1rem' }}>
            <button
              type="button"
              className="text-button"
              onClick={() => setSelectedAttemptId(null)}
              style={{ fontWeight: 600, fontSize: '0.95rem' }}
            >
              ← Вернуться к списку проверок
            </button>
          </div>

          <ProctoringReport
            report={selectedReport}
            isManagerView={true}
            onVerdictSubmitted={(updated) => {
              setReports((prev) =>
                prev.map((item) => (item.attempt_id === updated.attempt_id ? updated : item))
              );
            }}
          />
        </div>
      ) : (
        /* Sessions List */
        <section className="card">
          <div className="card-head">
            <div>
              <h2>Сессии сдачи экзаменов кандидатов</h2>
              <p style={{ margin: '0.2rem 0 0 0', fontSize: '0.85rem', color: '#55565d' }}>
                Выберите сессию для просмотра видеозаписи и подтверждения оценки
              </p>
            </div>

            {/* Filter Tabs */}
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              <button
                type="button"
                className={`button button-small ${filter === 'all' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('all')}
              >
                Все ({reports.length})
              </button>
              <button
                type="button"
                className={`button button-small ${filter === 'review_required' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('review_required')}
              >
                Требуют проверки ({pendingCount})
              </button>
              <button
                type="button"
                className={`button button-small ${filter === 'confirmed_fraud' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('confirmed_fraud')}
              >
                Фрод ({fraudCount})
              </button>
              <button
                type="button"
                className={`button button-small ${filter === 'clean' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('clean')}
              >
                Честные ({cleanCount})
              </button>
            </div>
          </div>

          {filteredReports.length === 0 ? (
            <div className="empty" style={{ padding: '2.5rem' }}>
              <div className="empty-icon">✓</div>
              <h3>В выбранной категории сессий нет</h3>
              <p>Все спорные сессии в этой вкладке уже обработаны экспертами платформы.</p>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', padding: '0.5rem 0' }}>
              {filteredReports.map((item) => {
                const badgeInfo = getFraudStatusBadgeInfo(item.review_status);
                const activeBan = getActiveFraudBan(
                  item.specialization_id || 'backend-python-v1',
                  item.requested_grade || 'junior'
                );

                return (
                  <div
                    key={item.attempt_id}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      padding: '1rem 1.25rem',
                      background: item.review_status === 'review_required' ? '#fffdf7' : '#f8f9fa',
                      border:
                        item.review_status === 'review_required'
                          ? '1px solid #fed7aa'
                          : '1px solid #e9ecef',
                      borderRadius: '8px',
                      flexWrap: 'wrap',
                      gap: '1rem',
                    }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '1rem' }}>
                      <span
                        style={{
                          width: '42px',
                          height: '42px',
                          borderRadius: '50%',
                          background:
                            item.review_status === 'confirmed_fraud'
                              ? '#ffe4e6'
                              : item.review_status === 'review_required'
                              ? '#fef3c7'
                              : '#e0f2fe',
                          color:
                            item.review_status === 'confirmed_fraud'
                              ? '#ec1d35'
                              : item.review_status === 'review_required'
                              ? '#b45309'
                              : '#0284c7',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontWeight: 700,
                          fontSize: '1.1rem',
                        }}
                      >
                        {item.candidate_name ? item.candidate_name.slice(0, 1) : 'К'}
                      </span>
                      <div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
                          <strong style={{ fontSize: '1.05rem' }}>
                            {item.candidate_name || 'Кандидат'}
                          </strong>
                          <span className="badge">
                            {item.requested_grade?.toUpperCase() ?? 'JUNIOR'}
                          </span>
                          <span
                            className="badge"
                            style={{
                              background: '#eff6ff',
                              color: '#2563eb',
                              borderColor: '#bfdbfe',
                            }}
                          >
                            🎥 Видео доступно
                          </span>
                        </div>
                        <p style={{ margin: '0.2rem 0 0 0', fontSize: '0.85rem', color: '#55565d' }}>
                          Попытка: <code>{item.attempt_id.slice(0, 10)}</code> · Сессия:{' '}
                          {new Date(item.started_at).toLocaleString('ru-RU')} · {item.events.length} событий
                        </p>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '1.25rem', flexWrap: 'wrap' }}>
                      <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: '0.85rem', color: '#55565d' }}>Индекс риска:</div>
                        <strong
                          style={{
                            fontSize: '1.2rem',
                            color:
                              item.fraud_score > 50
                                ? '#ec1d35'
                                : item.fraud_score > 25
                                ? '#b45309'
                                : '#10b981',
                          }}
                        >
                          {item.fraud_score} / 100
                        </strong>
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.25rem' }}>
                        <span className={`badge badge-${badgeInfo.tone}`}>
                          {badgeInfo.label}
                        </span>
                        {item.review_status === 'confirmed_fraud' && activeBan && (
                          <small style={{ color: '#ec1d35', fontSize: '0.75rem' }}>
                            Бан еще {activeBan.days_left} дн.
                          </small>
                        )}
                      </div>

                      <button
                        type="button"
                        className="button button-primary button-small"
                        onClick={() => setSelectedAttemptId(item.attempt_id)}
                      >
                        Смотреть видео и протокол →
                      </button>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
