import { useQuery } from '@tanstack/react-query';
import { api, type CompetencyRadarData } from '../../api';

type RadarProps = {
  data?: CompetencyRadarData;
  candidateId?: string;
  needId?: string;
  showTarget?: boolean;
  size?: number;
};

export function CompetencyRadarChart({ data, candidateId, needId, showTarget = true, size = 320 }: RadarProps) {
  const query = useQuery({
    queryKey: ['competency-radar', candidateId ?? 'me', needId ?? 'none'],
    queryFn: () => {
      if (candidateId) {
        return api.get<CompetencyRadarData>(`/candidates/${candidateId}/competency-radar${needId ? `?need_id=${needId}` : ''}`);
      }
      return api.get<CompetencyRadarData>(`/me/competency-radar${needId ? `?need_id=${needId}` : ''}`);
    },
    enabled: !data,
  });

  const radarData = data ?? query.data;

  if (!radarData) {
    if (query.isLoading) {
      return <div style={{ padding: '1.5rem', textAlign: 'center', color: 'var(--muted, #64748b)' }}>Загрузка радара компетенций…</div>;
    }
    return null;
  }

  const axes = radarData.radar_axes;
  const count = axes.length;
  if (count === 0) return null;

  const center = size / 2;
  const radius = (size / 2) * 0.72;

  // Convert polar coordinates to Cartesian (x, y)
  const polarToCartesian = (index: number, score: number) => {
    const angle = (Math.PI * 2 * index) / count - Math.PI / 2;
    const r = (score / 100) * radius;
    const x = center + r * Math.cos(angle);
    const y = center + r * Math.sin(angle);
    return { x, y };
  };

  const axisEndpoints = axes.map((_, i) => polarToCartesian(i, 100));

  // Polygons for verified, declared, and target
  const verifiedPoints = axes.map((axis, i) => {
    const { x, y } = polarToCartesian(i, axis.verified);
    return `${x},${y}`;
  }).join(' ');

  const declaredPoints = axes.map((axis, i) => {
    const { x, y } = polarToCartesian(i, axis.declared);
    return `${x},${y}`;
  }).join(' ');

  const targetPoints = axes.map((axis, i) => {
    const { x, y } = polarToCartesian(i, axis.target);
    return `${x},${y}`;
  }).join(' ');

  // Grid levels (25%, 50%, 75%, 100%)
  const gridLevels = [25, 50, 75, 100];

  return (
    <div className="competency-radar-wrapper" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '100%' }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ overflow: 'visible' }}>
        {/* Background concentric polygons */}
        {gridLevels.map((level) => {
          const points = axes.map((_, i) => {
            const { x, y } = polarToCartesian(i, level);
            return `${x},${y}`;
          }).join(' ');
          return (
            <polygon
              key={level}
              points={points}
              fill="none"
              stroke="var(--color-border, #e2e8f0)"
              strokeWidth="1"
              strokeDasharray={level < 100 ? '2 2' : 'none'}
            />
          );
        })}

        {/* Axis lines */}
        {axisEndpoints.map((endpoint, i) => (
          <line
            key={i}
            x1={center}
            y1={center}
            x2={endpoint.x}
            y2={endpoint.y}
            stroke="var(--color-border, #cbd5e1)"
            strokeWidth="1"
          />
        ))}

        {/* Target Polygon (Role Requirement) */}
        {showTarget && (
          <polygon
            points={targetPoints}
            fill="rgba(249, 115, 22, 0.08)"
            stroke="#f97316"
            strokeWidth="2"
            strokeDasharray="4 3"
          />
        )}

        {/* Declared Polygon (Candidate Self-Declaration) */}
        <polygon
          points={declaredPoints}
          fill="rgba(59, 130, 246, 0.08)"
          stroke="#3b82f6"
          strokeWidth="1.5"
          strokeDasharray="2 2"
        />

        {/* Verified Polygon (Test Verified) */}
        <polygon
          points={verifiedPoints}
          fill="rgba(16, 185, 129, 0.25)"
          stroke="#10b981"
          strokeWidth="2.5"
        />

        {/* Axis Labels */}
        {axes.map((axis, i) => {
          const angle = (Math.PI * 2 * i) / count - Math.PI / 2;
          const labelDist = radius + 22;
          const lx = center + labelDist * Math.cos(angle);
          const ly = center + labelDist * Math.sin(angle);
          const anchor = Math.abs(Math.cos(angle)) < 0.2 ? 'middle' : Math.cos(angle) > 0 ? 'start' : 'end';

          return (
            <text
              key={axis.key}
              x={lx}
              y={ly}
              textAnchor={anchor}
              dominantBaseline="central"
              fontSize="11"
              fontWeight="600"
              fill="var(--color-text-main, #1e293b)"
            >
              {axis.label.split(' ')[0]}
            </text>
          );
        })}
      </svg>

      {/* Legend */}
      <div style={{ display: 'flex', gap: '1rem', marginTop: '0.75rem', fontSize: '0.75rem', flexWrap: 'wrap', justifyContent: 'center' }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
          <span style={{ width: '12px', height: '12px', background: '#10b981', borderRadius: '2px', display: 'inline-block' }} />
          <strong>Подтверждено тестом</strong>
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
          <span style={{ width: '12px', height: '12px', background: '#3b82f6', borderRadius: '2px', display: 'inline-block', opacity: 0.6 }} />
          <span>Заявлено кандидатом</span>
        </span>
        {showTarget && (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: '0.35rem' }}>
            <span style={{ width: '12px', height: '12px', background: '#f97316', borderRadius: '2px', display: 'inline-block' }} />
            <span>Требование роли</span>
          </span>
        )}
      </div>

      {/* Skill Gap Analysis & Auto-Generated Interview Guide */}
      {radarData.gaps && radarData.gaps.length > 0 && (
        <div style={{ marginTop: '1.5rem', width: '100%', textAlign: 'left' }}>
          <h4 style={{ margin: '0 0 0.5rem 0', fontSize: '0.9375rem', color: '#b45309' }}>
            ⚠️ Анализ разрывов компетенций (Skill Gaps) и подсказки для собеседования:
          </h4>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
            {radarData.gaps.map((gap) => (
              <div
                key={gap.competency}
                style={{
                  background: 'var(--surface-subtle, #fffbeb)',
                  border: '1px solid #fde68a',
                  borderRadius: '8px',
                  padding: '0.75rem 1rem'
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <strong>{gap.label}</strong>
                  <span style={{ fontSize: '0.8125rem', color: '#b45309', fontWeight: 600 }}>
                    Дефицит: -{gap.gap_delta} п. (Текущий: {gap.candidate_score} / Требуется: {gap.target_score})
                  </span>
                </div>
                {gap.interview_questions && gap.interview_questions.length > 0 && (
                  <div style={{ marginTop: '0.5rem', fontSize: '0.8125rem' }}>
                    <span style={{ fontWeight: 600, color: '#92400e' }}>Рекомендуемый проверочный вопрос:</span>
                    <p style={{ margin: '0.2rem 0', fontStyle: 'italic', color: 'var(--color-text-main, #1e293b)' }}>
                      «{gap.interview_questions[0].question}»
                    </p>
                    <small style={{ color: 'var(--muted, #64748b)' }}>
                      Критерий оценки: {gap.interview_questions[0].rubric}
                    </small>
                  </div>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
