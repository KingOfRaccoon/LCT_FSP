import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { buildVacancyApplicationPayload, buildVacancyPublishPayload, claimVacancyPublish, isApplicationContactRequestCurrent, nextVacancyPublishIdempotencyKey, clearVacancyPublishIntent, releaseVacancyPublish } from '../api.ts';

const appSource = await readFile(new URL('../App.tsx', import.meta.url), 'utf8');
const demoSeederSource = await readFile(new URL('../../../backend/src/main/kotlin/ru/fsp/talent/demo/DemoSeeder.kt', import.meta.url), 'utf8');
const resumeImportSource = await readFile(new URL('./adoption/ResumeImportPanel.tsx', import.meta.url), 'utf8');
const resumeSelectionSource = await readFile(new URL('./adoption/resume-selection.ts', import.meta.url), 'utf8');
const vacancySource = await readFile(new URL('./vacancies/VacanciesPage.tsx', import.meta.url), 'utf8');
const employerApplicationsSource = await readFile(new URL('./vacancies/EmployerApplicationsPanel.tsx', import.meta.url), 'utf8');

test('company and vacancy screens do not claim unverified employer trust scores', () => {
  assert.doesNotMatch(appSource, /companyTrustScore|\/trust-score|Антифрод и индекс доверия|Верифицированный работодатель|Надёжный профиль работодателя|\?\? 95/iu);
  assert.doesNotMatch(vacancySource, /Trust Score 96\/100|вакансии проверены платформой на прозрачность/iu);
});

test('one-click demo login uses the password configured by DemoSeeder', () => {
  const seededPassword = demoSeederSource.match(/encoder\.encode\("([^"]+)"\)/u)?.[1];
  assert.ok(seededPassword, 'DemoSeeder must define its seeded account password');
  assert.ok(appSource.includes(`password: '${seededPassword}'`), 'quick login password must match DemoSeeder');
});

test('the shared candidate PDF flow is consent-gated, reviewed, and explicitly selected before saving', () => {
  assert.match(appSource, /import \{ ResumeImportPanel \} from '\.\/features\/adoption\/ResumeImportPanel'/u);
  assert.match(appSource, /<ResumeImportPanel profile=\{profile\} processingAccepted=\{processingAccepted\} onApplied=\{refreshImportedProfile\} \/>/u);
  assert.doesNotMatch(appSource, /onResumeFileSelected|resumeSuggestions|\/me\/resume\/parse/u);
  assert.match(resumeImportSource, /api\.upload<ResumeImportDraft>\('\/me\/resume\/import'/u);
  assert.match(resumeImportSource, /if \(!file \|\| loading \|\| !processingAccepted\) return/u);
  assert.match(resumeImportSource, /const \[selected, setSelected\] = useState<Field\[\]>\(\[\]\)/u);
  assert.match(resumeImportSource, /if \(!draft \|\| selected\.length === 0 \|\| saving\) return/u);
  assert.match(resumeImportSource, /buildSelectedProfilePatch\(profile, edited, selected\)/u);
  assert.match(resumeImportSource, /Распознанные контакты показываются только вам и не добавляются автоматически/u);
  assert.match(resumeSelectionSource, /if \(!selectableFields\.includes\(field\)\) continue/u);
  assert.doesNotMatch(resumeSelectionSource, /declared_grade.*selectableFields|expected_version.*selectableFields/u);
});

test('vacancy and employer application routes coexist with the prototype workspace routes', () => {
  for (const path of ['/vacancies', '/employer-vacancies', '/needs', '/matches', '/recruiting', '/methodist']) {
    assert.ok(appSource.includes(`path="${path}"`), `expected route ${path}`);
  }
  assert.match(appSource, /<EmployerApplicationsPanel \/>/u);
  assert.match(appSource, /<NeedInsightsDisclosure key=\{`insights:\$\{need\.id\}:\$\{need\.version\}`\} need=\{need\} \/>/u);
});

test('vacancy publishing sends the need salary and conditions as numeric wire fields', () => {
  const need = {
    id: 'need-1', title: 'Backend Python', description: 'API', specialization_id: 'backend-python-v1' as const,
    target_grade: 'middle' as const, work_mode: 'hybrid' as const, city: 'Казань', workload_hours_per_week: 32,
    salary: { min_kopecks: 25000000, max_kopecks: 42000000, currency: 'RUB' as const, period: 'month' as const, basis: 'net' as const, workload_type: 'part_time' as const, hours_per_week: 32 },
  };
  assert.deepEqual(buildVacancyPublishPayload(need), {
    need_id: 'need-1', title: 'Backend Python', description: 'API', specialization_id: 'backend-python-v1',
    target_grade: 'middle', work_mode: 'hybrid', city: 'Казань', workload_hours_per_week: 32,
    salary_min_kopecks: 25000000, salary_max_kopecks: 42000000, salary_basis: 'net',
  });
});

test('application payload contains only explicitly selected channels that are currently enabled', () => {
  assert.deepEqual(buildVacancyApplicationPayload('  Хочу обсудить роль  ', ['email', 'phone', 'email'], [
    { type: 'email', value: 'a@example.test', enabled: true },
    { type: 'phone', value: '+79990000000', enabled: false },
  ]), { cover_letter: 'Хочу обсудить роль', contact_channel_types: ['email'] });
  assert.deepEqual(buildVacancyApplicationPayload('', [], []), { cover_letter: null, contact_channel_types: [] });
});

test('vacancy and application screens require processing consent and explicit contact selection', () => {
  assert.match(vacancySource, /scope === 'processing'/u);
  assert.match(vacancySource, /buildVacancyApplicationPayload/u);
  assert.match(vacancySource, /Откликнуться и открыть выбранные контакты этой компании/u);
  assert.match(vacancySource, /contact-access\/revoke/u);
});

test('application contact replies are accepted only for the current request and active grant', () => {
  assert.equal(isApplicationContactRequestCurrent(4, 4, true), true);
  assert.equal(isApplicationContactRequestCurrent(3, 4, true), false);
  assert.equal(isApplicationContactRequestCurrent(4, 4, false), false);
});

test('vacancy contact reads with an empty selected-channel list are shown as no contacts transferred', () => {
  assert.match(employerApplicationsSource, /Контакты не переданы/u);
  assert.match(employerApplicationsSource, /!app\.has_selected_contacts/u);
});

test('ATS export exposes only the generic JSON export contract', async () => {
  const source = await readFile(new URL('./employer/EmployerWorkflow.tsx', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /huntflow|Huntflow/u);
  assert.match(source, /\/recruiting\/export\/ats\?format=json/u);
  assert.match(source, /Экспорт профилей \(JSON\)/u);
});

test('failed vacancy publication retries keep the same key only for the identical need payload', () => {
  const intents = new Map();
  const first = nextVacancyPublishIdempotencyKey(intents, 'need-1', { salary: 100 }, () => 'key-1');
  const retry = nextVacancyPublishIdempotencyKey(intents, 'need-1', { salary: 100 }, () => 'key-2');
  const changed = nextVacancyPublishIdempotencyKey(intents, 'need-1', { salary: 120 }, () => 'key-3');
  assert.equal(first, 'key-1');
  assert.equal(retry, 'key-1');
  assert.equal(changed, 'key-3');
  clearVacancyPublishIntent(intents, 'need-1', changed);
  assert.equal(nextVacancyPublishIdempotencyKey(intents, 'need-1', { salary: 120 }, () => 'key-4'), 'key-4');
});

test('vacancy publication has a synchronous per-need in-flight guard', () => {
  const active = new Set();
  assert.equal(claimVacancyPublish(active, 'need-1'), true);
  assert.equal(claimVacancyPublish(active, 'need-1'), false);
  assert.equal(claimVacancyPublish(active, 'need-2'), true);
  releaseVacancyPublish(active, 'need-1');
  assert.equal(claimVacancyPublish(active, 'need-1'), true);
});

test('vacancy publication wires synchronous claim, stable key, disabled state, and conflict refresh', () => {
  assert.match(appSource, /claimVacancyPublish\(publishInFlight\.current, need\.id\)/u);
  assert.match(appSource, /api\.post\('\/vacancies', payload, idempotencyKey\)/u);
  assert.match(appSource, /disabled=\{publishingNeeds\.has\(need\.id\)\}/u);
  assert.match(appSource, /VERSION_CONFLICT[\s\S]*?invalidateQueries\(\{ queryKey: \['needs'\] \}\)/u);
});
