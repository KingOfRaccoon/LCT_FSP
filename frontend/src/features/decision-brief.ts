import type { Schemas } from '../api';

type FitCheck = Schemas['FitCheck'];

export function summarizeDecision(checks: FitCheck[]) {
  return {
    confirmedSkills: checks.filter((check) => check.type === 'skill' && check.status === 'met'),
    matchingConditions: checks.filter((check) => ['work_mode', 'city', 'workload'].includes(check.type) && check.status === 'met'),
    grade: checks.find((check) => check.type === 'grade'),
    specialization: checks.find((check) => check.type === 'specialization'),
    requiredToClarify: checks.filter((check) =>
      (check.type === 'skill' && check.importance === 'required' || check.type === 'grade') && check.status !== 'met' && check.status !== 'mismatch'),
    preferredTopics: checks.filter((check) => check.type === 'skill' && check.importance === 'preferred' && check.status !== 'met' && check.status !== 'mismatch'),
    openConditions: checks.filter((check) => ['work_mode', 'city', 'workload'].includes(check.type) && check.status !== 'met' && check.status !== 'mismatch'),
    mismatches: checks.filter((check) => check.status === 'mismatch'),
  };
}
