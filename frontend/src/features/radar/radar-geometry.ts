import { skillLabels, type SkillId } from '../../api.ts';

export const STANDARD_SKILLS: readonly SkillId[] = [
  'python-basics',
  'http-api',
  'sql-transactions',
  'reliability',
  'postgres',
  'docker',
  'fastapi',
] as const;

export const RADAR_LEVELS = {
  NONE: 0,
  BASIC: 0.33,
  PREFERRED: 0.66,
  REQUIRED: 1.0,
  DECLARED: 0.66,
  VERIFIED: 1.0,
} as const;

export const CANDIDATE_COLORS = [
  { stroke: '#10b981', fill: 'rgba(16, 185, 129, 0.20)', labelColor: '#059669', name: 'Изумрудный' }, // Emerald
  { stroke: '#2563eb', fill: 'rgba(37, 99, 235, 0.18)', labelColor: '#1d4ed8', name: 'Синий' },      // Blue
  { stroke: '#8b5cf6', fill: 'rgba(139, 92, 246, 0.20)', labelColor: '#7c3aed', name: 'Фиолетовый' }, // Purple
] as const;

export const VACANCY_COLOR = {
  stroke: '#4f46e5',
  fill: 'rgba(79, 70, 229, 0.12)',
  strokeDasharray: '5,4',
} as const;

export const DECLARED_COLOR = {
  stroke: '#f59e0b',
  fill: 'rgba(245, 158, 11, 0.12)',
  strokeDasharray: '4,3',
} as const;

export const DEFAULT_GRID_STEPS: readonly number[] = [0.33, 0.66, 1.0] as const;
export const DEFAULT_VIEW_BOX_SIZE = 440;
export const DEFAULT_CENTER = 220;
export const DEFAULT_RADIUS = 140;

export interface Point {
  x: number;
  y: number;
}

export interface GridPolygon {
  ratio: number;
  percent: number;
  label: string;
  points: string;
}

export interface RadialSpoke {
  skillIndex: number;
  angle: number;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface LabelPosition {
  x: number;
  y: number;
  textAnchor: 'start' | 'middle' | 'end';
  dominantBaseline: 'hanging' | 'central' | 'alphabetic' | 'auto';
}

/**
 * Calculates angle in radians for a skill index.
 * Angle starts at top (-PI/2) and proceeds clockwise.
 */
export function getSkillAngle(index: number, totalSkills: number = STANDARD_SKILLS.length): number {
  if (totalSkills <= 0) return -Math.PI / 2;
  return -Math.PI / 2 + (2 * Math.PI * index) / totalSkills;
}

/**
 * Calculates (x, y) coordinates for a point given center, radius, level ratio [0..1], and angle.
 */
export function calculatePoint(
  cx: number,
  cy: number,
  radius: number,
  level: number,
  angle: number,
): Point {
  const boundedLevel = Math.max(0, Math.min(1, level));
  const x = Number((cx + radius * boundedLevel * Math.cos(angle)).toFixed(2));
  const y = Number((cy + radius * boundedLevel * Math.sin(angle)).toFixed(2));
  return { x, y };
}

/**
 * Generates an SVG polygon points string from a list of level ratios [0..1].
 */
export function generatePolygonPoints(
  cx: number,
  cy: number,
  radius: number,
  levels: readonly number[],
  totalSkills: number = levels.length,
): string {
  if (levels.length === 0) return '';
  return levels
    .map((level, i) => {
      const angle = getSkillAngle(i, totalSkills);
      const pt = calculatePoint(cx, cy, radius, level, angle);
      return `${pt.x},${pt.y}`;
    })
    .join(' ');
}

/**
 * Generates concentric grid polygons at 33%, 66%, and 100%.
 */
export function generateGridPolygons(
  cx: number,
  cy: number,
  radius: number,
  totalSkills: number = STANDARD_SKILLS.length,
  steps: readonly number[] = DEFAULT_GRID_STEPS,
): GridPolygon[] {
  return steps.map((step) => {
    const uniformLevels = Array.from({ length: totalSkills }, () => step);
    const percent = Math.round(step * 100);
    return {
      ratio: step,
      percent,
      label: `${percent}%`,
      points: generatePolygonPoints(cx, cy, radius, uniformLevels, totalSkills),
    };
  });
}

/**
 * Generates radial spoke axes from the center to the outer perimeter (100%).
 */
export function generateRadialSpokes(
  cx: number,
  cy: number,
  radius: number,
  totalSkills: number = STANDARD_SKILLS.length,
): RadialSpoke[] {
  return Array.from({ length: totalSkills }, (_, i) => {
    const angle = getSkillAngle(i, totalSkills);
    const outer = calculatePoint(cx, cy, radius, 1.0, angle);
    return {
      skillIndex: i,
      angle,
      x1: cx,
      y1: cy,
      x2: outer.x,
      y2: outer.y,
    };
  });
}

/**
 * Calculates label position outside perimeter with text alignment adjustments.
 */
export function calculateLabelPosition(
  cx: number,
  cy: number,
  radius: number,
  angle: number,
  offset: number = 24,
): LabelPosition {
  const labelDistance = radius + offset;
  const x = Number((cx + labelDistance * Math.cos(angle)).toFixed(2));
  const y = Number((cy + labelDistance * Math.sin(angle)).toFixed(2));

  const cos = Math.cos(angle);
  const sin = Math.sin(angle);

  let textAnchor: 'start' | 'middle' | 'end' = 'middle';
  if (cos > 0.25) {
    textAnchor = 'start';
  } else if (cos < -0.25) {
    textAnchor = 'end';
  }

  let dominantBaseline: 'hanging' | 'central' | 'alphabetic' | 'auto' = 'central';
  if (sin < -0.6) {
    dominantBaseline = 'alphabetic';
  } else if (sin > 0.6) {
    dominantBaseline = 'hanging';
  }

  return { x, y, textAnchor, dominantBaseline };
}

/**
 * Maps vacancy requirement importance to level ratio [0..1].
 * 'required' -> 1.0, 'preferred' -> 0.66, none -> 0.0
 */
export function mapRequirementLevel(importance?: 'required' | 'preferred' | string | null): number {
  if (importance === 'required') return RADAR_LEVELS.REQUIRED;
  if (importance === 'preferred') return RADAR_LEVELS.PREFERRED;
  return RADAR_LEVELS.NONE;
}

/**
 * Maps candidate competency evidence status to level ratio [0..1].
 * 'test_verified' -> 1.0, 'candidate_declared' -> 0.66, unknown -> 0.0
 */
export function mapCandidateEvidenceLevel(
  status?: 'test_verified' | 'candidate_declared' | 'unknown' | string | null,
): number {
  if (status === 'test_verified') return RADAR_LEVELS.VERIFIED;
  if (status === 'candidate_declared') return RADAR_LEVELS.DECLARED;
  return RADAR_LEVELS.NONE;
}

/**
 * Returns formatted percentage for a level ratio.
 */
export function formatPercent(level: number): string {
  if (level >= 0.95) return '100%';
  if (level >= 0.6) return '66%';
  if (level >= 0.3) return '33%';
  return '0%';
}

/**
 * Helper to get human label for a skill ID.
 */
export function getSkillLabel(skillId: string): string {
  return skillLabels[skillId as SkillId] ?? skillId;
}

/**
 * Extracts requirement levels for a given Need across the target skills.
 */
export function extractNeedLevels(
  need: { requirements?: readonly { skill_id: string; importance: string }[] | null },
  skills: readonly SkillId[] = STANDARD_SKILLS,
): number[] {
  const reqMap = new Map<string, string>();
  for (const r of need.requirements ?? []) {
    reqMap.set(r.skill_id, r.importance);
  }
  return skills.map((id) => mapRequirementLevel(reqMap.get(id)));
}

/**
 * Extracts test-verified and declared competency levels for a candidate across target skills.
 */
export function extractCandidateLevels(
  candidate: {
    skills?: readonly { skill_id: string }[] | null;
    competency_evidence?: readonly { skill_id: string; status: string }[] | null;
  },
  skills: readonly SkillId[] = STANDARD_SKILLS,
): { verified: number[]; declared: number[]; highest: number[] } {
  const evidenceStatus = new Map<string, string>();
  for (const ev of candidate.competency_evidence ?? []) {
    evidenceStatus.set(ev.skill_id, ev.status);
  }

  const declaredSkills = new Set(
    (candidate.skills ?? []).map((s) => s.skill_id),
  );

  const verified: number[] = [];
  const declared: number[] = [];
  const highest: number[] = [];

  for (const id of skills) {
    const status = evidenceStatus.get(id);
    let vLevel = 0;
    let dLevel = 0;

    if (status === 'test_verified') {
      vLevel = RADAR_LEVELS.VERIFIED;
    } else if (status === 'candidate_declared') {
      dLevel = RADAR_LEVELS.DECLARED;
    } else if (declaredSkills.has(id)) {
      dLevel = RADAR_LEVELS.DECLARED;
    }

    verified.push(vLevel);
    declared.push(dLevel);
    highest.push(Math.max(vLevel, dLevel));
  }

  return { verified, declared, highest };
}
