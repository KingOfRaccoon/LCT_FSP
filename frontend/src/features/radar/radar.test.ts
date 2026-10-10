import assert from 'node:assert/strict';
import test from 'node:test';
import {
  calculateLabelPosition,
  calculatePoint,
  CANDIDATE_COLORS,
  DECLARED_COLOR,
  DEFAULT_CENTER,
  DEFAULT_RADIUS,
  extractCandidateLevels,
  extractNeedLevels,
  formatPercent,
  generateGridPolygons,
  generatePolygonPoints,
  generateRadialSpokes,
  getSkillAngle,
  mapCandidateEvidenceLevel,
  mapRequirementLevel,
  RADAR_LEVELS,
  STANDARD_SKILLS,
  VACANCY_COLOR,
} from './radar-geometry.ts';

test('STANDARD_SKILLS defines exactly 7 standard competencies', () => {
  assert.equal(STANDARD_SKILLS.length, 7);
  assert.deepEqual(STANDARD_SKILLS, [
    'python-basics',
    'http-api',
    'sql-transactions',
    'reliability',
    'postgres',
    'docker',
    'fastapi',
  ]);
});

test('getSkillAngle calculates clockwise angles starting from the top (-PI/2)', () => {
  const topAngle = getSkillAngle(0, 7);
  assert.equal(Number(topAngle.toFixed(4)), Number((-Math.PI / 2).toFixed(4)));

  const angleStep = (2 * Math.PI) / 7;
  for (let i = 0; i < 7; i++) {
    const expected = -Math.PI / 2 + i * angleStep;
    assert.equal(Number(getSkillAngle(i, 7).toFixed(4)), Number(expected.toFixed(4)));
  }

  // Edge case: single or 0 skills defaults safely
  assert.equal(getSkillAngle(0, 0), -Math.PI / 2);
});

test('calculatePoint returns center coordinates when level is 0', () => {
  const cx = 200;
  const cy = 200;
  const radius = 100;
  for (let i = 0; i < 7; i++) {
    const pt = calculatePoint(cx, cy, radius, 0, getSkillAngle(i, 7));
    assert.equal(pt.x, cx);
    assert.equal(pt.y, cy);
  }
});

test('calculatePoint returns exact top position for index 0 at level 1.0', () => {
  const cx = 220;
  const cy = 220;
  const radius = 140;
  const topPoint = calculatePoint(cx, cy, radius, 1.0, getSkillAngle(0, 7));
  // angle is -PI/2: cos(-PI/2) = 0, sin(-PI/2) = -1
  assert.equal(topPoint.x, cx);
  assert.equal(topPoint.y, cy - radius);
});

test('calculatePoint clamps levels outside [0, 1] range', () => {
  const cx = 100;
  const cy = 100;
  const radius = 50;
  const angle = 0; // pointing right (cos=1, sin=0)

  const clampedNegative = calculatePoint(cx, cy, radius, -0.5, angle);
  assert.equal(clampedNegative.x, 100);
  assert.equal(clampedNegative.y, 100);

  const clampedExcess = calculatePoint(cx, cy, radius, 1.8, angle);
  assert.equal(clampedExcess.x, 150);
  assert.equal(clampedExcess.y, 100);
});

test('generatePolygonPoints produces valid SVG polygon points string', () => {
  const cx = 200;
  const cy = 200;
  const radius = 100;
  const levels = [1.0, 0.66, 0.33, 0.0, 1.0, 0.66, 0.33];

  const pointsStr = generatePolygonPoints(cx, cy, radius, levels);
  assert.ok(typeof pointsStr === 'string');
  const points = pointsStr.split(' ');
  assert.equal(points.length, 7);

  // Each item should be in "x,y" format
  points.forEach((pair) => {
    const [x, y] = pair.split(',').map(Number);
    assert.ok(!Number.isNaN(x));
    assert.ok(!Number.isNaN(y));
  });

  // Empty levels produces empty string
  assert.equal(generatePolygonPoints(cx, cy, radius, []), '');
});

test('generateGridPolygons produces concentric 33%, 66%, and 100% polygons', () => {
  const grid = generateGridPolygons(DEFAULT_CENTER, DEFAULT_CENTER, DEFAULT_RADIUS, 7);
  assert.equal(grid.length, 3);

  assert.equal(grid[0].percent, 33);
  assert.equal(grid[0].label, '33%');
  assert.equal(grid[1].percent, 66);
  assert.equal(grid[1].label, '66%');
  assert.equal(grid[2].percent, 100);
  assert.equal(grid[2].label, '100%');

  // Verify that point distances from center grow strictly monotonically
  const points33 = grid[0].points.split(' ').map((p) => p.split(',').map(Number));
  const points66 = grid[1].points.split(' ').map((p) => p.split(',').map(Number));
  const points100 = grid[2].points.split(' ').map((p) => p.split(',').map(Number));

  for (let i = 0; i < 7; i++) {
    const dist33 = Math.hypot(points33[i][0] - DEFAULT_CENTER, points33[i][1] - DEFAULT_CENTER);
    const dist66 = Math.hypot(points66[i][0] - DEFAULT_CENTER, points66[i][1] - DEFAULT_CENTER);
    const dist100 = Math.hypot(points100[i][0] - DEFAULT_CENTER, points100[i][1] - DEFAULT_CENTER);

    assert.ok(dist33 < dist66, `Grid step 33% (${dist33}) must be inside 66% (${dist66})`);
    assert.ok(dist66 < dist100, `Grid step 66% (${dist66}) must be inside 100% (${dist100})`);
  }
});

test('generateRadialSpokes creates 7 axes from center to 100% perimeter', () => {
  const spokes = generateRadialSpokes(DEFAULT_CENTER, DEFAULT_CENTER, DEFAULT_RADIUS, 7);
  assert.equal(spokes.length, 7);

  spokes.forEach((spoke, i) => {
    assert.equal(spoke.skillIndex, i);
    assert.equal(spoke.x1, DEFAULT_CENTER);
    assert.equal(spoke.y1, DEFAULT_CENTER);

    // Spoke length should equal radius
    const length = Math.hypot(spoke.x2 - spoke.x1, spoke.y2 - spoke.y1);
    assert.ok(Math.abs(length - DEFAULT_RADIUS) < 0.1, `Spoke ${i} length should match radius`);
  });
});

test('calculateLabelPosition computes coordinates and text-anchor correctly', () => {
  // Top label (index 0)
  const topPos = calculateLabelPosition(200, 200, 100, -Math.PI / 2);
  assert.equal(topPos.textAnchor, 'middle');
  assert.equal(topPos.dominantBaseline, 'alphabetic');
  assert.ok(topPos.y < 100);

  // Right side label (angle 0)
  const rightPos = calculateLabelPosition(200, 200, 100, 0);
  assert.equal(rightPos.textAnchor, 'start');
  assert.ok(rightPos.x > 300);

  // Left side label (angle PI)
  const leftPos = calculateLabelPosition(200, 200, 100, Math.PI);
  assert.equal(leftPos.textAnchor, 'end');
  assert.ok(leftPos.x < 100);

  // Bottom label (angle PI/2)
  const bottomPos = calculateLabelPosition(200, 200, 100, Math.PI / 2);
  assert.equal(bottomPos.textAnchor, 'middle');
  assert.equal(bottomPos.dominantBaseline, 'hanging');
  assert.ok(bottomPos.y > 300);
});

test('mapRequirementLevel scales required to 1.0, preferred to 0.66, and missing to 0', () => {
  assert.equal(mapRequirementLevel('required'), RADAR_LEVELS.REQUIRED);
  assert.equal(mapRequirementLevel('preferred'), RADAR_LEVELS.PREFERRED);
  assert.equal(mapRequirementLevel(null), RADAR_LEVELS.NONE);
  assert.equal(mapRequirementLevel(undefined), RADAR_LEVELS.NONE);
  assert.equal(mapRequirementLevel('unknown_importance'), RADAR_LEVELS.NONE);
});

test('mapCandidateEvidenceLevel scales test_verified to 1.0, candidate_declared to 0.66', () => {
  assert.equal(mapCandidateEvidenceLevel('test_verified'), RADAR_LEVELS.VERIFIED);
  assert.equal(mapCandidateEvidenceLevel('candidate_declared'), RADAR_LEVELS.DECLARED);
  assert.equal(mapCandidateEvidenceLevel('unknown'), RADAR_LEVELS.NONE);
  assert.equal(mapCandidateEvidenceLevel(null), RADAR_LEVELS.NONE);
});

test('formatPercent produces readable percentages', () => {
  assert.equal(formatPercent(1.0), '100%');
  assert.equal(formatPercent(0.66), '66%');
  assert.equal(formatPercent(0.33), '33%');
  assert.equal(formatPercent(0), '0%');
});

test('extractNeedLevels properly converts need requirements into 7 levels', () => {
  const need = {
    requirements: [
      { skill_id: 'python-basics', importance: 'required' },
      { skill_id: 'postgres', importance: 'preferred' },
      { skill_id: 'docker', importance: 'required' },
    ],
  };

  const levels = extractNeedLevels(need);
  assert.equal(levels.length, 7);

  // 0: python-basics -> required (1.0)
  assert.equal(levels[0], 1.0);
  // 1: http-api -> not required (0)
  assert.equal(levels[1], 0);
  // 2: sql-transactions -> not required (0)
  assert.equal(levels[2], 0);
  // 3: reliability -> not required (0)
  assert.equal(levels[3], 0);
  // 4: postgres -> preferred (0.66)
  assert.equal(levels[4], 0.66);
  // 5: docker -> required (1.0)
  assert.equal(levels[5], 1.0);
  // 6: fastapi -> not required (0)
  assert.equal(levels[6], 0);
});

test('extractCandidateLevels separates test_verified and declared skills accurately', () => {
  const candidate = {
    competency_evidence: [
      { skill_id: 'python-basics', status: 'test_verified' },
      { skill_id: 'sql-transactions', status: 'test_verified' },
      { skill_id: 'http-api', status: 'candidate_declared' },
    ],
    skills: [
      { skill_id: 'http-api' },
      { skill_id: 'docker' },
    ],
  };

  const { verified, declared, highest } = extractCandidateLevels(candidate);
  assert.equal(verified.length, 7);
  assert.equal(declared.length, 7);
  assert.equal(highest.length, 7);

  // python-basics: verified 1.0, declared 0, highest 1.0
  assert.equal(verified[0], 1.0);
  assert.equal(declared[0], 0);
  assert.equal(highest[0], 1.0);

  // http-api: verified 0, declared 0.66, highest 0.66
  assert.equal(verified[1], 0);
  assert.equal(declared[1], 0.66);
  assert.equal(highest[1], 0.66);

  // sql-transactions: verified 1.0, declared 0, highest 1.0
  assert.equal(verified[2], 1.0);
  assert.equal(declared[2], 0);
  assert.equal(highest[2], 1.0);

  // reliability: none
  assert.equal(verified[3], 0);
  assert.equal(declared[3], 0);
  assert.equal(highest[3], 0);

  // docker: declared via skills list
  assert.equal(verified[5], 0);
  assert.equal(declared[5], 0.66);
  assert.equal(highest[5], 0.66);
});

test('CANDIDATE_COLORS defines emerald, blue, and purple color series', () => {
  assert.equal(CANDIDATE_COLORS.length, 3);
  // Emerald
  assert.equal(CANDIDATE_COLORS[0].stroke, '#10b981');
  // Blue
  assert.equal(CANDIDATE_COLORS[1].stroke, '#2563eb');
  // Purple
  assert.equal(CANDIDATE_COLORS[2].stroke, '#8b5cf6');

  // Vacancy
  assert.equal(VACANCY_COLOR.stroke, '#4f46e5');
  assert.equal(VACANCY_COLOR.strokeDasharray, '5,4');

  // Declared
  assert.equal(DECLARED_COLOR.stroke, '#f59e0b');
  assert.equal(DECLARED_COLOR.strokeDasharray, '4,3');
});
