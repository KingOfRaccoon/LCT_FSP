import { useQuery } from '@tanstack/react-query';
import { api, type ValidationMetrics } from '../../api';

type Props = {
  isOpen?: boolean;
  onClose: () => void;
};

export function ValidationMetricsModal({ onClose }: Props) {
  const metricsQuery = useQuery({
    queryKey: ['validationMetrics'],
    queryFn: () => api.get<ValidationMetrics>('/assessment/validation-metrics')
  });

  const data = metricsQuery.data;

  return (
    <div className="employer-modal-backdrop" role="presentation" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div
        className="card"
        style={{
          maxWidth: '800px',
          width: '90%',
          maxHeight: '90vh',
          overflowY: 'auto',
          background: 'var(--color-bg, #ffffff)',
          borderRadius: '16px',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)',
          padding: '2rem'
        }}
        role="dialog"
        aria-modal="true"
        aria-label="Психометрическая валидация и надёжность тестирования"
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '1px solid var(--color-border, #e2e8f0)', paddingBottom: '1rem', marginBottom: '1.5rem' }}>
          <div>
            <div className="eyebrow">МАТЕМАТИЧЕСКАЯ ВАЛИДАЦИЯ ТЕСТИРОВАНИЯ (КРИТЕРИЙ 25%)</div>
            <h2 style={{ margin: '0.25rem 0' }}>Надёжность и устойчивость методологии</h2>
            <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--color-text-muted, #64748b)' }}>
              Модель Item Response Theory (IRT 2PL) и метрики воспроизводимости результатов
            </p>
          </div>
          <button className="text-button" onClick={onClose} style={{ fontSize: '1.5rem' }}>×</button>
        </div>

        {metricsQuery.isPending ? (
          <div>Загрузка математических метрик…</div>
        ) : data ? (
          <>
            {/* Top KPIs */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
              <div style={{ padding: '1rem', background: '#f8fafc', borderRadius: '10px', border: '1px solid #e2e8f0' }}>
                <div style={{ fontSize: '0.75rem', color: '#64748b' }}>Test-Retest Pearson r</div>
                <strong style={{ fontSize: '1.75rem', color: '#10b981' }}>{data.reliability_metrics.test_retest_pearson_r}</strong>
                <p style={{ fontSize: '0.75rem', color: '#475569', margin: '0.25rem 0 0 0' }}>{data.reliability_metrics.test_retest_description}</p>
              </div>

              <div style={{ padding: '1rem', background: '#f8fafc', borderRadius: '10px', border: '1px solid #e2e8f0' }}>
                <div style={{ fontSize: '0.75rem', color: '#64748b' }}>Cronbach's Alpha (α)</div>
                <strong style={{ fontSize: '1.75rem', color: '#3b82f6' }}>{data.reliability_metrics.cronbach_alpha}</strong>
                <p style={{ fontSize: '0.75rem', color: '#475569', margin: '0.25rem 0 0 0' }}>{data.reliability_metrics.cronbach_description}</p>
              </div>

              <div style={{ padding: '1rem', background: '#f8fafc', borderRadius: '10px', border: '1px solid #e2e8f0' }}>
                <div style={{ fontSize: '0.75rem', color: '#64748b' }}>Устойчивость к утечкам</div>
                <strong style={{ fontSize: '1.75rem', color: '#8b5cf6' }}>{Math.round(data.reliability_metrics.leakage_resistance_index * 100)}%</strong>
                <p style={{ fontSize: '0.75rem', color: '#475569', margin: '0.25rem 0 0 0' }}>{data.reliability_metrics.leakage_resistance_mechanism}</p>
              </div>
            </div>

            {/* Anti-Cheating & Proctoring telemetry */}
            <div style={{ padding: '1.25rem', background: '#ecfdf5', borderRadius: '12px', border: '1px solid #a7f3d0', marginBottom: '1.5rem' }}>
              <div className="eyebrow" style={{ color: '#047857' }}>ИНТЕГРИРОВАННАЯ ТЕЛЕМЕТРИЯ ЧЕСТНОСТИ (ANTI-CHEAT)</div>
              <p style={{ fontSize: '0.875rem', color: '#065f46', margin: '0.25rem 0 0.75rem 0' }}>
                Платформа непрерывно мониторит поведение кандидата во время прохождения теста без инвазивной слежки:
              </p>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '0.5rem', fontSize: '0.8125rem' }}>
                <div>✓ Детекция переключения вкладок (tab blur)</div>
                <div>✓ Мониторинг вставки из буфера обмена (paste events)</div>
                <div>✓ Анализ аномальной скорости ответов (&lt; 3 сек)</div>
                <div>✓ Формирование индекса честности (Integrity Score)</div>
              </div>
            </div>

            {/* Item Discrimination Table */}
            <div>
              <h4 style={{ margin: '0 0 0.75rem 0', fontSize: '0.9375rem' }}>Дискриминативность заданий банка (IRT 2PL Parameters):</h4>
              <div style={{ overflowX: 'auto' }}>
                <table style={{ width: '100%', fontSize: '0.8125rem', borderCollapse: 'collapse', textAlign: 'left' }}>
                  <thead>
                    <tr style={{ borderBottom: '2px solid #e2e8f0', color: '#64748b' }}>
                      <th style={{ padding: '0.5rem' }}>Семейство задач</th>
                      <th style={{ padding: '0.5rem' }}>Компетенция</th>
                      <th style={{ padding: '0.5rem' }}>Дискриминативность (α)</th>
                      <th style={{ padding: '0.5rem' }}>Сложность (b)</th>
                      <th style={{ padding: '0.5rem' }}>Целевой грейд</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.discrimination_parameters.map((item) => (
                      <tr key={item.family_id} style={{ borderBottom: '1px solid #f1f5f9' }}>
                        <td style={{ padding: '0.5rem' }}><code>{item.family_id}</code></td>
                        <td style={{ padding: '0.5rem' }}>{item.competency}</td>
                        <td style={{ padding: '0.5rem', color: '#10b981', fontWeight: 600 }}>{item.discrimination_alpha} (Высокая)</td>
                        <td style={{ padding: '0.5rem' }}>{item.difficulty_b > 0 ? `+${item.difficulty_b}` : item.difficulty_b}</td>
                        <td style={{ padding: '0.5rem' }}>
                          <span className={`badge ${item.grade === 'middle' ? 'badge-good' : 'badge-neutral'}`}>
                            {item.grade}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </>
        ) : null}

        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '1.5rem' }}>
          <button type="button" className="button button-primary" onClick={onClose}>Понятно</button>
        </div>
      </div>
    </div>
  );
}
