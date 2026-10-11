import { useState } from 'react';
import type { ProctoringReportData } from './proctoring-types.ts';
import {
  loadManagerReviewQueue,
  getFraudStatusBadgeInfo,
  getActiveFraudBan,
  clearDemoProctoringData,
} from './proctoring-storage.ts';
import { isDemoProctoringEnabled } from './proctoring-policy.ts';
import { ProctoringReport } from './ProctoringReport.tsx';
import './proctoring.css';

export function ProctoringQueuePage() {
  const [reports, setReports] = useState<ProctoringReportData[]>(() => loadManagerReviewQueue());
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null);
  const [filter, setFilter] = useState<'all' | 'review_required' | 'confirmed_fraud' | 'clean'>('all');

  const refreshQueue = () => setReports(loadManagerReviewQueue());

  const clearDemoData = () => {
    clearDemoProctoringData();
    setReports(loadManagerReviewQueue());
    setSelectedAttemptId(null);
  };

  const selectedReport = reports.find((r) => r.attempt_id === selectedAttemptId);

  const pendingCount = reports.filter((r) => r.review_status === 'review_required').length;
  const fraudCount = reports.filter((r) => r.review_status === 'confirmed_fraud').length;
  const cleanCount = reports.filter(
    (r) => r.review_status === 'auto_passed' || r.review_status === 'dismissed_clean'
  ).length;

  if (!isDemoProctoringEnabled()) {
    return <div className="notice notice-info">Локальная proctoring demo отключена. Она не является частью обычной оценки.</div>;
  }

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
          <div className="eyebrow">ЛОКАЛЬНЫЙ DEMO-МАКЕТ</div>
          <h1>Симуляция локальных сигналов</h1>
          <p>
            Только синтетические примеры и данные в памяти этой вкладки. Здесь нет реального экспертного просмотра, серверных записей, обвинений или влияния на оценку и грейд.
          </p>
        </div>
        <div className="page-header-action">
          <button type="button" className="button button-secondary" onClick={refreshQueue}>
            ↻ Обновить примеры
          </button>
          <button type="button" className="button button-secondary" onClick={clearDemoData}>
            Очистить мои demo-данные
          </button>
        </div>
      </div>

      {/* Summary Stat Grid */}
      <div className="stat-grid" style={{ marginBottom: '1.5rem' }}>
        <div className="stat-card">
          <div className="stat-top">
            <span>DEMO · сигналов для просмотра</span>
            <span className={`stat-dot ${pendingCount > 0 ? 'warm' : 'good'}`} />
          </div>
          <strong>{pendingCount}</strong>
          <small>Не являются экспертными решениями</small>
        </div>

        <div className="stat-card">
          <div className="stat-top">
            <span>DEMO · высокий сигнал</span>
            <span className="stat-dot danger" />
          </div>
          <strong>{fraudCount}</strong>
          <small>Не меняет оценку или допуск</small>
        </div>

        <div className="stat-card">
          <div className="stat-top">
            <span>DEMO · низкий сигнал</span>
            <span className="stat-dot good" />
          </div>
          <strong>{cleanCount}</strong>
          <small>Не подтверждает грейд</small>
        </div>

        <div className="stat-card">
          <div className="stat-top">
            <span>Синтетических примеров</span>
            <span className="stat-dot" />
          </div>
          <strong>{reports.length}</strong>
          <small>Они не сохраняются между входами</small>
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
              ← К списку DEMO-примеров
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
              <h2>Синтетические DEMO-примеры</h2>
              <p style={{ margin: '0.2rem 0 0 0', fontSize: '0.85rem', color: '#55565d' }}>
                Примеры интерфейса; видеозаписи и экспертной проверки здесь нет
              </p>
            </div>

            {/* Filter Tabs */}
            <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
              <button
                type="button"
                className={`button button-small ${filter === 'all' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('all')}
              >
                Все DEMO ({reports.length})
              </button>
              <button
                type="button"
                className={`button button-small ${filter === 'review_required' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('review_required')}
              >
                Сигнал для обсуждения ({pendingCount})
              </button>
              <button
                type="button"
                className={`button button-small ${filter === 'confirmed_fraud' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('confirmed_fraud')}
              >
                Высокий сигнал ({fraudCount})
              </button>
              <button
                type="button"
                className={`button button-small ${filter === 'clean' ? 'button-primary' : 'button-secondary'}`}
                onClick={() => setFilter('clean')}
              >
                Низкий сигнал ({cleanCount})
              </button>
            </div>
          </div>

          {filteredReports.length === 0 ? (
            <div className="empty" style={{ padding: '2.5rem' }}>
              <div className="empty-icon">✓</div>
              <h3>В выбранной категории сессий нет</h3>
              <p>В этой категории нет локальных demo-примеров.</p>
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
                            {item.demo ? item.candidate_name || 'DEMO · участник' : 'DEMO · синтетический участник'}
                          </strong>
                          <span className="badge">
                            DEMO · {item.requested_grade?.toUpperCase() ?? 'JUNIOR'}
                          </span>
                          <span
                            className="badge"
                            style={{
                              background: '#eff6ff',
                              color: '#2563eb',
                              borderColor: '#bfdbfe',
                            }}
                          >
                            {item.video_available ? 'DEMO · временное локальное видео' : 'DEMO · видео отсутствует'}
                          </span>
                        </div>
                        <p style={{ margin: '0.2rem 0 0 0', fontSize: '0.85rem', color: '#55565d' }}>
                          DEMO: <code>{item.attempt_id.slice(0, 10)}</code> · Создано: {' '}
                          {new Date(item.started_at).toLocaleString('ru-RU')} · {item.events.length} событий
                        </p>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '1.25rem', flexWrap: 'wrap' }}>
                      <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: '0.85rem', color: '#55565d' }}>Демо-сигнал:</div>
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
                          {item.fraud_score} / 100 · DEMO
                        </strong>
                      </div>

                      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '0.25rem' }}>
                        <span className={`badge badge-${badgeInfo.tone}`}>
                          {badgeInfo.label}
                        </span>
                        {item.review_status === 'confirmed_fraud' && activeBan && (
                          <small style={{ color: '#ec1d35', fontSize: '0.75rem' }}>
                            Демо-блокировка: {activeBan.days_left} дн. (только этот макет)
                          </small>
                        )}
                      </div>

                      <button
                        type="button"
                        className="button button-primary button-small"
                        onClick={() => setSelectedAttemptId(item.attempt_id)}
                      >
                        Открыть DEMO-пример →
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
