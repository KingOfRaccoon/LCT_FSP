import type { CandidateProfilePatch, ExperienceInput, ProjectInput, ResumeImportPatch } from './types.ts';
import { skillLabels, type SkillId } from '../../api.ts';

type ExistingProfile = {
  version: number;
  skills: { skill_id: string }[];
  experience_entries: CandidateProfilePatch['experience_entries'] extends infer T ? NonNullable<T> : never;
  project_entries: CandidateProfilePatch['project_entries'] extends infer T ? NonNullable<T> : never;
};

const selectableFields: readonly (keyof ResumeImportPatch)[] = [
  'display_name', 'headline', 'summary', 'allowed_work_modes', 'cities', 'desired_hours_per_week', 'max_weekly_hours',
  'skills', 'experience_entries', 'project_entries', 'industry', 'role_preferences', 'soft_skills',
];

function appendImportedEntries<T extends { id: string }>(existing: readonly T[], imported: readonly T[]): T[] {
  const ids = new Set(existing.map((entry) => entry.id));
  const additions = imported.map((entry) => {
    const id = ids.has(entry.id) ? crypto.randomUUID() : entry.id;
    ids.add(id);
    return id === entry.id ? entry : { ...entry, id };
  });
  return [...existing, ...additions];
}

function safeSkillIds(value: readonly string[]): SkillId[] {
  return value.filter((id): id is SkillId => Object.hasOwn(skillLabels, id));
}

function toExperienceInput(entry: ExperienceInput): ExperienceInput {
  return {
    id: entry.id,
    ...(entry.company === undefined ? {} : { company: entry.company }),
    role: entry.role,
    description: entry.description,
    start_month: entry.start_month,
    end_month: entry.end_month,
    skill_ids: safeSkillIds(entry.skill_ids),
  };
}

function toProjectInput(entry: ProjectInput): ProjectInput {
  return { id: entry.id, title: entry.title, description: entry.description, url: entry.url, skill_ids: safeSkillIds(entry.skill_ids) };
}

export function buildSelectedProfilePatch(
  profile: ExistingProfile,
  candidatePatch: ResumeImportPatch,
  selectedFields: readonly (keyof ResumeImportPatch)[],
): CandidateProfilePatch {
  const patch: CandidateProfilePatch = { expected_version: profile.version };
  for (const field of selectedFields) {
    if (!selectableFields.includes(field)) continue;
    const value = candidatePatch[field];
    if (value === undefined) continue;
    if (field === 'skills') {
      patch.skills = safeSkillIds([...new Set([...profile.skills.map((skill) => skill.skill_id), ...(value as string[])])]);
    } else if (field === 'experience_entries') {
      const existing = profile.experience_entries.map(toExperienceInput);
      const imported = (value as NonNullable<CandidateProfilePatch['experience_entries']>).map(toExperienceInput);
      patch.experience_entries = appendImportedEntries(existing, imported);
    } else if (field === 'project_entries') {
      const existing = profile.project_entries.map(toProjectInput);
      const imported = (value as NonNullable<CandidateProfilePatch['project_entries']>).map(toProjectInput);
      patch.project_entries = appendImportedEntries(existing, imported);
    } else {
      (patch as Record<string, unknown>)[field] = value;
    }
  }
  return patch;
}
