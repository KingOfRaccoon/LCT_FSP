import { useQuery } from '@tanstack/react-query';
import { api, type CompetencyRadarData } from '../../api';
import { CompetencyRadarChart } from './CompetencyRadarChart';

type PassportProps = {
  data?: CompetencyRadarData;
  candidateId?: string;
  candidateName?: string;
  verifiedGrade?: string | null;
  isOpen?: boolean;
  onClose: () => void;
  onDownloadPdf?: () => void;
};

export function DigitalPassportModal({ data, candidateId, candidateName, verifiedGrade, onClose, onDownloadPdf }: PassportProps) {
  const query = useQuery({
    queryKey: ['competency-radar', candidateId ?? 'me'],
    queryFn: () => {
      if (candidateId) {
        return api.get<CompetencyRadarData>(`/candidates/${candidateId}/competency-radar`);
      }
      return api.get<CompetencyRadarData>('/me/competency-radar');
    },
    enabled: !data,
  });

  const passportData = data ?? query.data;

  return (
    <div className="employer-modal-backdrop" role="presentation" onClick={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div
        className="card"
        style={{
          maxWidth: '720px',
          width: '90%',
          maxHeight: '90vh',
          overflowY: 'auto',
          background: 'var(--color-bg, #ffffff)',
          border: '2px solid var(--color-border, #0f172a)',
          borderRadius: '16px',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)',
          padding: '2rem',
          position: 'relative'
        }}
        role="dialog"
        aria-modal="true"
        aria-label="Цифровой Паспорт Компетенций ФСП"
      >
        {/* Header */}
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '2px solid var(--color-border, #e2e8f0)', paddingBottom: '1.25rem', marginBottom: '1.5rem' }}>
          <div>
            <div className="eyebrow" style={{ color: '#dc2626', letterSpacing: '0.1em', fontWeight: 700 }}>
              ФЕДЕРАЦИЯ СПОРТИВНОГО ПРОГРАММИРОВАНИЯ
            </div>
            <h2 style={{ margin: '0.25rem 0', fontSize: '1.625rem' }}>Цифровой Паспорт Компетенций</h2>
            <div style={{ fontSize: '0.875rem', color: 'var(--color-text-muted, #64748b)' }}>
              Официальный стандарт верификации квалификации IT-специалистов
            </div>
          </div>
          <button className="text-button" onClick={onClose} style={{ fontSize: '1.5rem', lineHeight: 1 }}>×</button>
        </div>

        {!passportData ? (
          <div style={{ padding: '2rem', textAlign: 'center', color: 'var(--muted, #64748b)' }}>
            Формирование цифрового паспорта и криптографической печати…
          </div>
        ) : (
          <>
            {/* Passport Identity Card */}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '1.5rem', marginBottom: '1.5rem' }}>
              <div>
                <div style={{ padding: '1rem', background: 'var(--color-bg-secondary, #f8fafc)', borderRadius: '12px', border: '1px solid var(--color-border, #e2e8f0)' }}>
                  <div style={{ fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.05em', color: '#64748b' }}>Владелец паспорта</div>
                  <h3 style={{ margin: '0.25rem 0', fontSize: '1.25rem' }}>{passportData.display_name || candidateName || 'Специалист'}</h3>
                  <div style={{ display: 'flex', gap: '0.5rem', marginTop: '0.5rem', flexWrap: 'wrap' }}>
                    <span className="badge badge-good" style={{ fontWeight: 600 }}>
                      {passportData.verified_grade || verifiedGrade ? `Подтверждён: ${(passportData.verified_grade || verifiedGrade || '').toUpperCase()}` : 'Без подтверждённого грейда'}
                    </span>
                    <span className="badge badge-neutral">
                      Заявлен: {(passportData.declared_grade || 'junior').toUpperCase()}
                    </span>
                  </div>
                  <div style={{ marginTop: '0.75rem', fontSize: '0.8125rem', color: '#475569' }}>
                    <div>Идентификатор: <code>{passportData.passport_seal.verification_id}</code></div>
                    <div>Выдан: {new Date(passportData.passport_seal.issued_at).toLocaleDateString('ru-RU')}</div>
                  </div>
                </div>

                {/* IRT Psychometric Ability Box */}
                <div style={{ marginTop: '1rem', padding: '1rem', background: '#ecfdf5', borderRadius: '12px', border: '1px solid #a7f3d0' }}>
                  <div style={{ fontSize: '0.75rem', fontWeight: 600, color: '#047857', letterSpacing: '0.05em' }}>
                    ПСИХОМЕТРИЧЕСКАЯ ОЦЕНКА (IRT 2PL)
                  </div>
                  <div style={{ display: 'flex', alignItems: 'baseline', gap: '0.75rem', marginTop: '0.35rem' }}>
                    <strong style={{ fontSize: '1.75rem', color: '#065f46' }}>{passportData.irt_ability.percentile_rank}%</strong>
                    <span style={{ fontSize: '0.875rem', color: '#047857' }}>процентиль среди специалистов отрасли</span>
                  </div>
                  <div style={{ fontSize: '0.8125rem', color: '#065f46', marginTop: '0.25rem' }}>
                    Латентная способность θ: <strong>{passportData.irt_ability.latent_theta > 0 ? `+${passportData.irt_ability.latent_theta}` : passportData.irt_ability.latent_theta}</strong> (SEM: {passportData.irt_ability.sem})
                  </div>
                </div>
              </div>

              {/* SVG Radar */}
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
                <CompetencyRadarChart data={passportData} showTarget={false} size={280} />
              </div>
            </div>

            {/* Competency Axis Breakdown */}
            <div style={{ marginBottom: '1.5rem' }}>
              <h4 style={{ margin: '0 0 0.75rem 0', fontSize: '0.9375rem' }}>Подтверждённые срезы компетенций:</h4>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0.75rem' }}>
                {passportData.radar_axes.map((axis) => (
                  <div key={axis.key} style={{ padding: '0.75rem', background: 'var(--color-bg-secondary, #f8fafc)', borderRadius: '8px', border: '1px solid var(--color-border, #e2e8f0)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.8125rem' }}>
                      <strong>{axis.label}</strong>
                      <span style={{ color: '#10b981', fontWeight: 700 }}>{axis.verified}%</span>
                    </div>
                    <div style={{ width: '100%', height: '6px', background: '#e2e8f0', borderRadius: '3px', marginTop: '0.5rem', overflow: 'hidden' }}>
                      <div style={{ width: `${axis.verified}%`, height: '100%', background: '#10b981', borderRadius: '3px' }} />
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Cryptographic Integrity Seal */}
            <div style={{ padding: '0.875rem', background: '#f1f5f9', borderRadius: '10px', fontSize: '0.75rem', color: '#475569', marginBottom: '1.5rem', wordBreak: 'break-all' }}>
              <div style={{ fontWeight: 600, color: '#1e293b', marginBottom: '0.25rem' }}>Криптографический хэш верификации (SHA-256 Integrity Seal):</div>
              <code>{passportData.passport_seal.sha256_seal}</code>
              <div style={{ marginTop: '0.25rem', fontSize: '0.6875rem', color: '#64748b' }}>
                Защищено от модификации. Любое несанкционированное изменение данных делает хэш недействительным.
              </div>
            </div>
          </>
        )}

        {/* Actions */}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem', flexWrap: 'wrap' }}>
          <button type="button" className="button button-secondary" onClick={onClose}>Закрыть</button>
          {onDownloadPdf && (
            <button type="button" className="button button-primary" onClick={onDownloadPdf}>
              📄 Скачать официальный PDF-паспорт ↓
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
