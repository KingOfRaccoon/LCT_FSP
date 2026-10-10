import type { ProctoringReportData } from './proctoring-types';
import { getFraudStatusBadgeInfo } from './proctoring-storage';
import './proctoring.css';

interface CandidateFraudStatusCardProps {
  report: ProctoringReportData | null;
}

export function CandidateFraudStatusCard({ report }: CandidateFraudStatusCardProps) {
  if (!report) return null;
  const badgeInfo = getFraudStatusBadgeInfo(report.review_status);
  const bannerClass =
    report.review_status === 'confirmed_fraud'
      ? 'fraud'
      : report.review_status === 'review_required'
      ? 'suspicious'
      : 'clean';

  return (
    <section className="card" style={{ marginTop: '1.5rem', marginBottom: '1.5rem' }}>
      <div className={`proctoring-score-banner ${bannerClass}`} style={{ marginBottom: 0 }}>
        <div>
          <span className="eyebrow" style={{ color: 'inherit' }}>
            СТАТУС ПРОВЕРКИ ЧЕСТНОСТИ СДАЧИ
          </span>
          <h3 style={{ margin: '0.2rem 0', fontSize: '1.25rem' }}>{badgeInfo.label}</h3>
          <p style={{ margin: 0, fontSize: '0.9rem', lineHeight: 1.45 }}>{badgeInfo.description}</p>
        </div>
        <div style={{ textAlign: 'right', minWidth: '110px' }}>
          <span className={`badge badge-${badgeInfo.tone}`} style={{ fontSize: '0.85rem' }}>
            {badgeInfo.label}
          </span>
        </div>
      </div>
    </section>
  );
}
