import { useId, useMemo, useState, type CSSProperties } from 'react';
import type { Schemas, SkillId } from '../../api';
import {
  calculateLabelPosition,
  calculatePoint,
  CANDIDATE_COLORS,
  DECLARED_COLOR,
  DEFAULT_CENTER,
  DEFAULT_GRID_STEPS,
  DEFAULT_RADIUS,
  DEFAULT_VIEW_BOX_SIZE,
  extractCandidateLevels,
  extractNeedLevels,
  formatPercent,
  generateGridPolygons,
  generatePolygonPoints,
  generateRadialSpokes,
  getSkillAngle,
  getSkillLabel,
  STANDARD_SKILLS,
  VACANCY_COLOR,
} from './radar-geometry';
import './radar.css';

export { CANDIDATE_COLORS, VACANCY_COLOR, DECLARED_COLOR };

export interface CandidateSeries {
  id: string;
  name: string;
  color: string;
  fillColor: string;
  verifiedLevels: number[];
  declaredLevels: number[];
  demo?: boolean;
  rawCandidate?: Schemas['CandidatePublic'];
  fitSummary?: string;
  fitState?: Schemas['CandidateFit']['state'];
}

export interface CompetencyRadarChartProps {
  need?: Schemas['Need'] | null;
  items?: Schemas['CandidateComparisonItem'][] | null;
  singleCandidate?: Schemas['CandidatePublic'] | null;
  candidateSeries?: CandidateSeries[];
  skills?: readonly SkillId[];
  size?: number;
  interactive?: boolean;
  className?: string;
  style?: CSSProperties;
  title?: string;
  description?: string;
}

interface TooltipState {
  x: number;
  y: number;
  skillLabel: string;
  seriesName: string;
  status: string;
  scoreText: string;
  color: string;
}

export function CompetencyRadarChart({
  need,
  items,
  singleCandidate,
  candidateSeries: customSeries,
  skills = STANDARD_SKILLS,
  size = 440,
  interactive = true,
  className = '',
  style,
  title,
  description,
}: CompetencyRadarChartProps) {
  const chartId = useId();
  const [hoveredTooltip, setHoveredTooltip] = useState<TooltipState | null>(null);
  const [hoveredSkillIndex, setHoveredSkillIndex] = useState<number | null>(null);

  // Layer visibility state
  const [visibleLayers, setVisibleLayers] = useState<Record<string, boolean>>({
    vacancy: true,
    candidate_0: true,
    candidate_1: true,
    candidate_2: true,
    declared: true,
  });

  const toggleLayer = (layerKey: string) => {
    setVisibleLayers((prev) => ({
      ...prev,
      [layerKey]: !prev[layerKey],
    }));
  };

  // 1. Compute vacancy levels
  const needLevels = useMemo(() => {
    if (!need) return skills.map(() => 0);
    return extractNeedLevels(need, skills);
  }, [need, skills]);

  // 2. Build candidate series
  const candidates: CandidateSeries[] = useMemo(() => {
    if (customSeries && customSeries.length > 0) {
      return customSeries;
    }

    if (items && items.length > 0) {
      return items.map((item, idx) => {
        const colorCfg = CANDIDATE_COLORS[idx % CANDIDATE_COLORS.length];
        const { verified, declared } = extractCandidateLevels(item.candidate, skills);
        return {
          id: item.candidate.id,
          name: item.candidate.display_name,
          color: colorCfg.stroke,
          fillColor: colorCfg.fill,
          verifiedLevels: verified,
          declaredLevels: declared,
          demo: item.candidate.demo,
          rawCandidate: item.candidate,
          fitSummary: item.fit.summary,
          fitState: item.fit.state,
        };
      });
    }

    if (singleCandidate) {
      const colorCfg = CANDIDATE_COLORS[0];
      const { verified, declared } = extractCandidateLevels(singleCandidate, skills);
      return [
        {
          id: singleCandidate.id,
          name: singleCandidate.display_name,
          color: colorCfg.stroke,
          fillColor: colorCfg.fill,
          verifiedLevels: verified,
          declaredLevels: declared,
          demo: singleCandidate.demo,
          rawCandidate: singleCandidate,
        },
      ];
    }

    return [];
  }, [customSeries, items, singleCandidate, skills]);

  // Geometry calculations
  const cx = DEFAULT_CENTER;
  const cy = DEFAULT_CENTER;
  const radius = DEFAULT_RADIUS;
  const totalSkills = skills.length;

  const gridPolygons = useMemo(
    () => generateGridPolygons(cx, cy, radius, totalSkills, DEFAULT_GRID_STEPS),
    [cx, cy, radius, totalSkills],
  );

  const radialSpokes = useMemo(
    () => generateRadialSpokes(cx, cy, radius, totalSkills),
    [cx, cy, radius, totalSkills],
  );

  const axisLabels = useMemo(() => {
    return skills.map((skillId, i) => {
      const angle = getSkillAngle(i, totalSkills);
      const pos = calculateLabelPosition(cx, cy, radius, angle, 24);
      return {
        skillId,
        label: getSkillLabel(skillId),
        index: i,
        angle,
        ...pos,
      };
    });
  }, [cx, cy, radius, skills, totalSkills]);

  // Need polygon string
  const needPolygonPoints = useMemo(() => {
    return generatePolygonPoints(cx, cy, radius, needLevels, totalSkills);
  }, [cx, cy, radius, needLevels, totalSkills]);

  // Check if any candidate has declared competencies
  const hasDeclaredSkills = useMemo(() => {
    return candidates.some((c) => c.declaredLevels.some((lvl) => lvl > 0));
  }, [candidates]);

  // Metrics summary for display
  const metrics = useMemo(() => {
    const requiredSkillIndices = needLevels
      .map((lvl, idx) => (lvl >= 0.99 ? idx : -1))
      .filter((idx) => idx !== -1);

    return candidates.map((cand) => {
      const verifiedCount = requiredSkillIndices.filter(
        (idx) => (cand.verifiedLevels[idx] ?? 0) >= 0.99,
      ).length;
      const declaredCount = cand.declaredLevels.filter((lvl) => lvl > 0).length;
      return {
        candidateId: cand.id,
        name: cand.name,
        verifiedRequired: verifiedCount,
        totalRequired: requiredSkillIndices.length,
        declaredCount,
      };
    });
  }, [needLevels, candidates]);

  return (
    <div className={`radar-wrapper ${className}`} style={style} role="region" aria-label="Радар компетенций">
      {(title || need?.title) && (
        <div className="radar-header">
          <div className="radar-header-title">
            <h3>{title ?? 'Радар компетенций'}</h3>
            <p>
              {description ??
                'Сопоставление подтверждённых тестом и заявленных навыков с требованиями позиции'}
            </p>
          </div>
          <div className="radar-scale-guide">
            <span className="radar-scale-step">
              <span className="radar-scale-dot" /> 0% Не подтверждено
            </span>
            <span className="radar-scale-step">
              <span className="radar-scale-dot" style={{ background: '#f59e0b' }} /> 66% Желательно / Заявлено
            </span>
            <span className="radar-scale-step">
              <span className="radar-scale-dot" style={{ background: '#10b981' }} /> 100% Обязательно / Тест
            </span>
          </div>
        </div>
      )}

      <div className="radar-body">
        {/* SVG Radar Chart */}
        <div className="radar-svg-container" style={{ maxWidth: size }}>
          <svg
            className="radar-svg"
            viewBox={`0 0 ${DEFAULT_VIEW_BOX_SIZE} ${DEFAULT_VIEW_BOX_SIZE}`}
            width={size}
            height={size}
            role="img"
            aria-labelledby={`${chartId}-title ${chartId}-desc`}
          >
            <title id={`${chartId}-title`}>Интерактивный радар компетенций</title>
            <desc id={`${chartId}-desc`}>
              Сравнительный график требований вакансии и навыков соискателей
            </desc>

            {/* Background grid concentric polygons */}
            <g className="radar-grid" aria-hidden="true">
              {gridPolygons.map((grid, idx) => (
                <polygon
                  key={grid.ratio}
                  points={grid.points}
                  className={`radar-grid-polygon ${idx === gridPolygons.length - 1 ? 'radar-grid-polygon-outer' : ''}`}
                />
              ))}

              {/* Grid percentage indicator labels along the top vertical spoke */}
              {gridPolygons.map((grid) => {
                const pt = calculatePoint(cx, cy, radius, grid.ratio, -Math.PI / 2);
                return (
                  <text
                    key={`label-${grid.ratio}`}
                    x={pt.x + 4}
                    y={pt.y - 2}
                    className="radar-grid-label"
                  >
                    {grid.label}
                  </text>
                );
              })}
            </g>

            {/* Radial Spoke Axes */}
            <g className="radar-spokes" aria-hidden="true">
              {radialSpokes.map((spoke) => (
                <line
                  key={spoke.skillIndex}
                  x1={spoke.x1}
                  y1={spoke.y1}
                  x2={spoke.x2}
                  y2={spoke.y2}
                  className="radar-spoke"
                />
              ))}
            </g>

            {/* Competency Axis Text Labels */}
            <g className="radar-labels" aria-hidden="true">
              {axisLabels.map((axis) => {
                const isHovered = hoveredSkillIndex === axis.index;
                const isRequired = (needLevels[axis.index] ?? 0) >= 0.99;
                return (
                  <text
                    key={axis.skillId}
                    x={axis.x}
                    y={axis.y}
                    textAnchor={axis.textAnchor}
                    dominantBaseline={axis.dominantBaseline}
                    className={`radar-axis-label ${isHovered ? 'radar-axis-label-highlight' : ''}`}
                    style={{
                      fontWeight: isRequired ? 700 : 500,
                    }}
                  >
                    {axis.label}
                    {isRequired && ' *'}
                  </text>
                );
              })}
            </g>

            {/* Layer 1: Vacancy Requirements Polygon */}
            {visibleLayers.vacancy && need && (
              <g className="radar-layer-vacancy">
                <polygon
                  points={needPolygonPoints}
                  fill={VACANCY_COLOR.fill}
                  stroke={VACANCY_COLOR.stroke}
                  strokeWidth={2}
                  strokeDasharray={VACANCY_COLOR.strokeDasharray}
                  className="radar-polygon"
                />
                {/* Vertex markers for Vacancy Requirements */}
                {needLevels.map((lvl, i) => {
                  if (lvl <= 0) return null;
                  const angle = getSkillAngle(i, totalSkills);
                  const pt = calculatePoint(cx, cy, radius, lvl, angle);
                  const isHovered =
                    hoveredTooltip?.seriesName === 'Требования вакансии' &&
                    hoveredTooltip?.skillLabel === getSkillLabel(skills[i]);
                  return (
                    <circle
                      key={`vac-vertex-${i}`}
                      cx={pt.x}
                      cy={pt.y}
                      r={isHovered ? 6 : 4}
                      fill="#ffffff"
                      stroke={VACANCY_COLOR.stroke}
                      strokeWidth={2}
                      className={`radar-vertex ${isHovered ? 'radar-vertex-active' : ''}`}
                      onMouseEnter={() => {
                        if (!interactive) return;
                        setHoveredSkillIndex(i);
                        setHoveredTooltip({
                          x: pt.x,
                          y: pt.y,
                          skillLabel: getSkillLabel(skills[i]),
                          seriesName: 'Требования вакансии',
                          status: lvl >= 0.99 ? 'Обязательный навык' : 'Желательный навык',
                          scoreText: formatPercent(lvl),
                          color: VACANCY_COLOR.stroke,
                        });
                      }}
                      onMouseLeave={() => {
                        setHoveredSkillIndex(null);
                        setHoveredTooltip(null);
                      }}
                    />
                  );
                })}
              </g>
            )}

            {/* Layer 2: Candidate Declared Polygons (Amber dashed) */}
            {visibleLayers.declared &&
              candidates.map((cand) => {
                const hasDeclared = cand.declaredLevels.some((lvl) => lvl > 0);
                if (!hasDeclared) return null;
                const points = generatePolygonPoints(
                  cx,
                  cy,
                  radius,
                  cand.declaredLevels,
                  totalSkills,
                );
                return (
                  <g key={`cand-declared-${cand.id}`} className="radar-layer-declared">
                    <polygon
                      points={points}
                      fill={DECLARED_COLOR.fill}
                      stroke={DECLARED_COLOR.stroke}
                      strokeWidth={1.75}
                      strokeDasharray={DECLARED_COLOR.strokeDasharray}
                      className="radar-polygon"
                    />
                    {cand.declaredLevels.map((lvl, i) => {
                      if (lvl <= 0) return null;
                      const angle = getSkillAngle(i, totalSkills);
                      const pt = calculatePoint(cx, cy, radius, lvl, angle);
                      return (
                        <circle
                          key={`cand-dec-v-${cand.id}-${i}`}
                          cx={pt.x}
                          cy={pt.y}
                          r={3.5}
                          fill="#ffffff"
                          stroke={DECLARED_COLOR.stroke}
                          strokeWidth={2}
                          className="radar-vertex"
                          onMouseEnter={() => {
                            if (!interactive) return;
                            setHoveredSkillIndex(i);
                            setHoveredTooltip({
                              x: pt.x,
                              y: pt.y,
                              skillLabel: getSkillLabel(skills[i]),
                              seriesName: cand.name,
                              status: 'Заявлено кандидатом (без теста)',
                              scoreText: '66%',
                              color: DECLARED_COLOR.stroke,
                            });
                          }}
                          onMouseLeave={() => {
                            setHoveredSkillIndex(null);
                            setHoveredTooltip(null);
                          }}
                        />
                      );
                    })}
                  </g>
                );
              })}

            {/* Layer 3: Candidate Test-Verified Polygons (Emerald, Blue, Purple) */}
            {candidates.map((cand, candIdx) => {
              const layerKey = `candidate_${candIdx}`;
              if (!visibleLayers[layerKey]) return null;

              const points = generatePolygonPoints(
                cx,
                cy,
                radius,
                cand.verifiedLevels,
                totalSkills,
              );

              return (
                <g key={`cand-verified-${cand.id}`} className={`radar-layer-${candIdx}`}>
                  <polygon
                    points={points}
                    fill={cand.fillColor}
                    stroke={cand.color}
                    strokeWidth={2.5}
                    className="radar-polygon"
                  />
                  {cand.verifiedLevels.map((lvl, i) => {
                    const angle = getSkillAngle(i, totalSkills);
                    const pt = calculatePoint(cx, cy, radius, lvl, angle);
                    const isHovered =
                      hoveredTooltip?.seriesName === cand.name &&
                      hoveredTooltip?.skillLabel === getSkillLabel(skills[i]);

                    return (
                      <circle
                        key={`cand-ver-v-${cand.id}-${i}`}
                        cx={pt.x}
                        cy={pt.y}
                        r={isHovered ? 6 : lvl > 0 ? 4.5 : 2.5}
                        fill={lvl > 0 ? cand.color : '#94a3b8'}
                        stroke="#ffffff"
                        strokeWidth={lvl > 0 ? 2 : 1}
                        className={`radar-vertex ${isHovered ? 'radar-vertex-active' : ''}`}
                        onMouseEnter={() => {
                          if (!interactive) return;
                          setHoveredSkillIndex(i);
                          setHoveredTooltip({
                            x: pt.x,
                            y: pt.y,
                            skillLabel: getSkillLabel(skills[i]),
                            seriesName: cand.name,
                            status:
                              lvl >= 0.99
                                ? 'Подтверждено тестом платформы'
                                : 'Тест не пройден / Нет сведений',
                            scoreText: formatPercent(lvl),
                            color: cand.color,
                          });
                        }}
                        onMouseLeave={() => {
                          setHoveredSkillIndex(null);
                          setHoveredTooltip(null);
                        }}
                      />
                    );
                  })}
                </g>
              );
            })}

            {/* Interactive SVG Hover Tooltip Card */}
            {hoveredTooltip && (
              <g
                className="radar-tooltip-group"
                pointerEvents="none"
                transform={`translate(${
                  // Clamp tooltip within SVG boundaries
                  Math.min(
                    DEFAULT_VIEW_BOX_SIZE - 200,
                    Math.max(10, hoveredTooltip.x - 95),
                  )
                }, ${
                  hoveredTooltip.y < 85
                    ? hoveredTooltip.y + 12
                    : hoveredTooltip.y - 75
                })`}
              >
                <rect
                  width={190}
                  height={62}
                  rx={8}
                  ry={8}
                  className="radar-tooltip-box"
                />
                <circle
                  cx={18}
                  cy={18}
                  r={5}
                  fill={hoveredTooltip.color}
                  stroke="#ffffff"
                  strokeWidth={1.5}
                />
                <text x={30} y={22} className="radar-tooltip-title">
                  {hoveredTooltip.skillLabel}
                </text>
                <text x={18} y={38} className="radar-tooltip-meta">
                  {hoveredTooltip.seriesName}
                </text>
                <text
                  x={18}
                  y={52}
                  className="radar-tooltip-status"
                  fill={hoveredTooltip.color}
                >
                  {hoveredTooltip.status} ({hoveredTooltip.scoreText})
                </text>
              </g>
            )}
          </svg>
        </div>

        {/* Sidebar: Legend Toggles & Candidate Breakdown */}
        <div className="radar-sidebar">
          {/* Interactive Legend Layer Toggles */}
          <div className="radar-legend">
            <div className="radar-legend-title">Слои и фильтры отображения</div>
            <div className="radar-legend-items">
              {need && (
                <button
                  type="button"
                  className={`radar-legend-toggle ${visibleLayers.vacancy ? 'is-active' : 'is-inactive'}`}
                  onClick={() => toggleLayer('vacancy')}
                  aria-pressed={visibleLayers.vacancy}
                >
                  <span
                    className="radar-legend-swatch radar-legend-swatch-dashed"
                    style={{
                      borderColor: VACANCY_COLOR.stroke,
                      background: VACANCY_COLOR.fill,
                    }}
                  />
                  <span className="radar-legend-label">
                    <span>Требования роли</span>
                    <span className="radar-legend-check">
                      {visibleLayers.vacancy ? '✔' : '✕'}
                    </span>
                  </span>
                </button>
              )}

              {candidates.map((cand, idx) => {
                const layerKey = `candidate_${idx}`;
                const isActive = visibleLayers[layerKey] ?? true;
                return (
                  <button
                    key={cand.id}
                    type="button"
                    className={`radar-legend-toggle ${isActive ? 'is-active' : 'is-inactive'}`}
                    onClick={() => toggleLayer(layerKey)}
                    aria-pressed={isActive}
                  >
                    <span
                      className="radar-legend-swatch"
                      style={{
                        borderColor: cand.color,
                        background: cand.fillColor,
                      }}
                    />
                    <span className="radar-legend-label">
                      <span>
                        {cand.name}
                        {cand.demo && <small style={{ marginLeft: 6, color: '#f59e0b' }}>DEMO</small>}
                      </span>
                      <span className="radar-legend-check">
                        {isActive ? '✔' : '✕'}
                      </span>
                    </span>
                  </button>
                );
              })}

              {hasDeclaredSkills && (
                <button
                  type="button"
                  className={`radar-legend-toggle ${visibleLayers.declared ? 'is-active' : 'is-inactive'}`}
                  onClick={() => toggleLayer('declared')}
                  aria-pressed={visibleLayers.declared}
                >
                  <span
                    className="radar-legend-swatch radar-legend-swatch-dashed"
                    style={{
                      borderColor: DECLARED_COLOR.stroke,
                      background: DECLARED_COLOR.fill,
                    }}
                  />
                  <span className="radar-legend-label">
                    <span>Заявлено кандидатами</span>
                    <span className="radar-legend-check">
                      {visibleLayers.declared ? '✔' : '✕'}
                    </span>
                  </span>
                </button>
              )}
            </div>
          </div>

          {/* Candidate Competency Fit Summary */}
          {metrics.length > 0 && (
            <div className="radar-candidates-summary">
              <div className="radar-legend-title">Сводка соответствия</div>
              {metrics.map((m, idx) => {
                const cand = candidates[idx];
                const matchRate =
                  m.totalRequired > 0
                    ? Math.round((m.verifiedRequired / m.totalRequired) * 100)
                    : 100;
                return (
                  <div key={m.candidateId} className="radar-candidate-card">
                    <div className="radar-candidate-card-header">
                      <span className="radar-candidate-name">
                        <span
                          className="radar-candidate-indicator"
                          style={{ background: cand?.color ?? '#10b981' }}
                        />
                        {m.name}
                      </span>
                      {cand?.demo && (
                        <span className="radar-metric-pill is-warm">DEMO</span>
                      )}
                    </div>
                    <div className="radar-candidate-metrics">
                      <span
                        className={`radar-metric-pill ${
                          matchRate >= 80 ? 'is-good' : matchRate >= 50 ? 'is-warm' : ''
                        }`}
                      >
                        Подтверждено: {m.verifiedRequired} из {m.totalRequired} ({matchRate}%)
                      </span>
                      {m.declaredCount > 0 && (
                        <span className="radar-metric-pill">
                          Заявлено: {m.declaredCount}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
