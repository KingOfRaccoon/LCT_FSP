import { useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { api, errorMessage, RequestError, skillLabels, type CandidateProfile } from '../../api';
import { buildSelectedProfilePatch } from './resume-selection';
import type { ExperienceInput, ProjectInput, ResumeImportDraft, ResumeImportPatch, SkillId, WorkMode } from './types';
import './adoption.css';

type Field = keyof ResumeImportPatch;
const labels: Record<Field, string> = {
  display_name: 'Имя в профиле', headline: 'Заголовок', summary: 'О себе', allowed_work_modes: 'Формат работы', cities: 'Города',
  desired_hours_per_week: 'Желаемые часы в неделю', max_weekly_hours: 'Максимум часов в неделю', skills: 'Навыки',
  experience_entries: 'Опыт работы', project_entries: 'Проекты', industry: 'Отрасль', role_preferences: 'Предпочитаемые роли', soft_skills: 'Личные навыки',
};
const editableFields = Object.keys(labels) as Field[];
const workModeLabels: Record<string, string> = { remote: 'Удалённо', hybrid: 'Гибрид', office: 'Офис' };
const skillIds = Object.keys(skillLabels) as SkillId[];

function editorValue(value: unknown): string {
  if (Array.isArray(value) && value.every((item) => typeof item === 'string')) return value.join(', ');
  if (value === null || value === undefined) return '';
  return String(value);
}

function parseField(field: Field, value: string): unknown {
  if (['allowed_work_modes', 'cities', 'role_preferences', 'soft_skills'].includes(field)) return value.split(',').map((part) => part.trim()).filter(Boolean);
  if (['desired_hours_per_week', 'max_weekly_hours'].includes(field)) {
    if (value === '' && field === 'desired_hours_per_week') return null;
    const parsed = Number(value);
    if (!Number.isSafeInteger(parsed) || parsed < 0 || (field === 'max_weekly_hours' && parsed < 1)) throw new Error('Укажите целое число часов в неделю.');
    return parsed;
  }
  return value;
}

function SkillCheckboxes({ value, onChange, label }: { value: SkillId[]; onChange: (skills: SkillId[]) => void; label: string }) {
  return <fieldset className="adoption-choice-group"><legend>{label}</legend><div className="adoption-choice-grid">{skillIds.map((id) => <label key={id}><input type="checkbox" checked={value.includes(id)} onChange={(event) => onChange(event.target.checked ? [...new Set([...value, id])] : value.filter((skill) => skill !== id))} /><span>{skillLabels[id]}</span></label>)}</div></fieldset>;
}

function ExperienceEditor({ entries, onChange }: { entries: ExperienceInput[]; onChange: (entries: ExperienceInput[]) => void }) {
  return <div className="adoption-entry-list">{entries.map((entry, index) => <fieldset className="adoption-entry-editor" key={entry.id}>
    <legend>Опыт работы {index + 1}</legend><button type="button" className="text-button" onClick={() => onChange(entries.filter((item) => item.id !== entry.id))}>Удалить эту запись</button>
    <label className="field"><span>Компания</span><input value={entry.company ?? ''} maxLength={160} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, company: event.target.value || null } : item))} /></label>
    <label className="field"><span>Должность</span><input required value={entry.role} maxLength={160} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, role: event.target.value } : item))} /></label>
    <div className="adoption-form-grid"><label className="field"><span>Начало</span><input required type="month" value={entry.start_month} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, start_month: event.target.value } : item))} /></label><label className="field"><span>Окончание</span><input type="month" value={entry.end_month ?? ''} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, end_month: event.target.value || null } : item))} /><small>Оставьте пустым, если продолжаете работать.</small></label></div>
    <label className="field"><span>Описание</span><textarea rows={3} maxLength={2000} value={entry.description} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, description: event.target.value } : item))} /></label>
    <SkillCheckboxes label="Навыки по вашему описанию" value={entry.skill_ids} onChange={(skill_ids) => onChange(entries.map((item) => item.id === entry.id ? { ...item, skill_ids } : item))} />
  </fieldset>)}</div>;
}

function ProjectEditor({ entries, onChange }: { entries: ProjectInput[]; onChange: (entries: ProjectInput[]) => void }) {
  return <div className="adoption-entry-list">{entries.map((entry, index) => <fieldset className="adoption-entry-editor" key={entry.id}>
    <legend>Проект {index + 1}</legend><button type="button" className="text-button" onClick={() => onChange(entries.filter((item) => item.id !== entry.id))}>Удалить эту запись</button>
    <label className="field"><span>Название</span><input required value={entry.title} maxLength={160} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, title: event.target.value } : item))} /></label>
    <label className="field"><span>Ссылка на проект</span><input type="url" inputMode="url" pattern="https://.*" placeholder="https://…" value={entry.url ?? ''} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, url: event.target.value || null } : item))} /></label>
    <label className="field"><span>Описание</span><textarea rows={3} maxLength={2000} value={entry.description} onChange={(event) => onChange(entries.map((item) => item.id === entry.id ? { ...item, description: event.target.value } : item))} /></label>
    <SkillCheckboxes label="Навыки по вашему описанию" value={entry.skill_ids} onChange={(skill_ids) => onChange(entries.map((item) => item.id === entry.id ? { ...item, skill_ids } : item))} />
  </fieldset>)}</div>;
}

export function ResumeImportPanel({ profile, processingAccepted, onApplied }: { profile: CandidateProfile; processingAccepted: boolean; onApplied: () => void | Promise<void> }) {
  const [file, setFile] = useState<File>();
  const [draft, setDraft] = useState<ResumeImportDraft>();
  const [edited, setEdited] = useState<ResumeImportPatch>({});
  const [selected, setSelected] = useState<Field[]>([]);
  const [parseError, setParseError] = useState('');
  const [editError, setEditError] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadForm = useRef<HTMLFormElement>(null);

  const chooseFile = (event: ChangeEvent<HTMLInputElement>) => {
    setFile(event.target.files?.[0]); setDraft(undefined); setEdited({}); setSelected([]); setParseError(''); setEditError(''); setError(''); setNotice('');
  };
  const tooManyEntries = (selected.includes('experience_entries') && profile.experience_entries.length + (edited.experience_entries?.length ?? 0) > 10) ||
    (selected.includes('project_entries') && profile.project_entries.length + (edited.project_entries?.length ?? 0) > 10);
  const updateField = <K extends Field>(field: K, value: ResumeImportPatch[K]) => setEdited((current) => ({ ...current, [field]: value }));
  const importResume = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!file || loading || !processingAccepted) return;
    setLoading(true); setError(''); setNotice(''); setParseError(''); setEditError('');
    try {
      const body = new FormData(); body.append('file', file);
      const result = await api.upload<ResumeImportDraft>('/me/resume/import', body);
      const candidatePatch = Object.fromEntries(Object.entries(result.candidate_patch).filter(([key]) => editableFields.includes(key as Field))) as ResumeImportPatch;
      const reviewedDraft = { ...result, candidate_patch: candidatePatch };
      setDraft(reviewedDraft); setEdited({ ...candidatePatch }); setSelected([]); setEditError('');
      if (!Object.keys(candidatePatch).length) setParseError('В документе не удалось найти поля профиля. Попробуйте другой PDF.');
    } catch (cause) {
      setDraft(undefined); setParseError(errorMessage(cause));
    } finally { setLoading(false); }
  };
  const apply = async () => {
    if (!draft || selected.length === 0 || saving) return;
    setSaving(true); setError(''); setNotice('');
    try {
      const patch = buildSelectedProfilePatch(profile, edited, selected);
      await api.patch<CandidateProfile>('/me/profile', patch);
      setDraft(undefined); setFile(undefined); setEdited({}); setSelected([]); setNotice('Выбранные сведения добавлены в профиль. Проверьте их в редакторе профиля.');
      if (fileInput.current) fileInput.current.value = '';
      try { await onApplied(); } catch { setNotice('Профиль сохранён. Не удалось обновить его отображение; обновите данные страницы.'); }
    } catch (cause) {
      const status = cause instanceof RequestError ? cause.status : 0;
      setError(status === 409 ? 'Профиль изменился после загрузки резюме. Ваши правки и выбор сохранены; обновите профиль, затем загрузите резюме ещё раз.' : errorMessage(cause));
    } finally { setSaving(false); }
  };

  return <section className="card adoption-card" aria-labelledby="resume-import-title">
    <div className="card-head"><div><span className="eyebrow">ПРОФИЛЬ</span><h2 id="resume-import-title">Заполнить профиль из резюме</h2></div><span className="badge badge-neutral">Черновик</span></div>
    <p className="adoption-intro">Загрузите PDF, проверьте распознанные сведения и сами отметьте поля для добавления. Резюме не подтверждает навыки и не меняет грейд.</p>
    {!processingAccepted && <p className="notice" role="note">Для загрузки требуется разрешение на обработку резюме. Его можно включить в настройках согласий.</p>}
    <form ref={uploadForm} className="adoption-upload-form" onSubmit={(event) => void importResume(event)}>
      <label className="field"><span>Файл резюме · PDF</span><input ref={fileInput} type="file" accept="application/pdf,.pdf" onChange={chooseFile} disabled={!processingAccepted || loading || saving} aria-describedby="resume-file-note" /></label>
      <small id="resume-file-note">Распознанные контакты показываются только вам и не добавляются автоматически.</small>
      <button className="button button-primary" type="submit" disabled={!processingAccepted || !file || loading || saving}>{loading ? 'Читаем резюме…' : 'Проверить резюме'}</button>
    </form>
    {parseError && <div className="error-box" role="alert"><span>{parseError}</span><button className="text-button" type="button" disabled={!processingAccepted || loading || !file} onClick={() => uploadForm.current?.requestSubmit()}>Повторить</button></div>}
    {error && <p className="notice notice-danger" role="alert">{error}</p>}
    {notice && <p className="notice notice-success" role="status">{notice}</p>}
    {draft && <div className="adoption-review">
      <h3>Проверьте и выберите сведения</h3>
      <p className="field-hint">Все поля изначально выключены. Отмеченные сведения будут добавлены к существующему профилю.</p>
      {draft.needs_review && <p className="notice">Это распознанный черновик. Сверьте текст с оригиналом перед сохранением.</p>}
      {draft.warnings.map((warning, index) => <p className="notice" key={`${index}-${warning}`}>{warning}</p>)}
      {editError && <p className="notice notice-danger" role="alert">{editError}</p>}
      {tooManyEntries && <p className="notice notice-danger" role="alert">В профиле может быть не больше 10 записей опыта и 10 проектов. Удалите лишние импортированные записи или снимите отметку.</p>}
      {Object.entries(edited).map(([rawField, value]) => {
        const field = rawField as Field;
        const id = `resume-field-${field}`;
        const checkboxId = `${id}-selected`;
        const plainTextArray = Array.isArray(value) && value.every((item) => typeof item === 'string');
        const change = (next: string) => {
          try { setEdited((current) => ({ ...current, [field]: parseField(field, next) })); setEditError(''); }
          catch { setEditError(`Проверьте значение в поле «${labels[field]}».`); }
        };
        return <div className="adoption-review-field" key={field}>
          <label className="adoption-select-field" htmlFor={checkboxId}><input id={checkboxId} type="checkbox" checked={selected.includes(field)} onChange={(event) => setSelected((current) => event.target.checked ? [...current, field] : current.filter((item) => item !== field))} /><span>Добавить «{labels[field]}»</span></label>
          {field === 'skills' ? <SkillCheckboxes label="Выберите найденные навыки" value={value as SkillId[]} onChange={(skills) => updateField('skills', skills)} /> :
            field === 'allowed_work_modes' ? <fieldset className="adoption-choice-group"><legend>Формат работы</legend><div className="adoption-choice-grid">{(Object.keys(workModeLabels) as WorkMode[]).map((mode) => <label key={mode}><input type="checkbox" checked={(value as WorkMode[]).includes(mode)} onChange={(event) => updateField('allowed_work_modes', event.target.checked ? [...new Set([...(value as WorkMode[]), mode])] : (value as WorkMode[]).filter((item) => item !== mode))} /><span>{workModeLabels[mode]}</span></label>)}</div></fieldset> :
              field === 'experience_entries' ? <ExperienceEditor entries={value as ExperienceInput[]} onChange={(entries) => updateField('experience_entries', entries)} /> :
                field === 'project_entries' ? <ProjectEditor entries={value as ProjectInput[]} onChange={(entries) => updateField('project_entries', entries)} /> :
                  <label className="field adoption-edit-field" htmlFor={id}><span>{labels[field]}</span>{field === 'summary' ? <textarea id={id} value={editorValue(value)} rows={4} onChange={(event) => change(event.target.value)} /> : <input id={id} type={typeof value === 'number' ? 'number' : 'text'} value={editorValue(value)} onChange={(event) => change(event.target.value)} />}{plainTextArray && <small>Перечислите значения через запятую.</small>}</label>}
        </div>;
      })}
      {draft.suggestions.contacts.length > 0 && <div className="adoption-contacts"><h4>Найденные контакты · только для вас</h4><ul>{draft.suggestions.contacts.map((contact, index) => <li key={`${contact.type}-${contact.value}-${index}`}>{contact.type === 'email' ? 'Email' : 'Телефон'}: <strong>{contact.value}</strong></li>)}</ul><p>Если хотите сохранить контакт, перенесите его вручную через форму контактов профиля. Импорт не открывает контакт компаниям.</p></div>}
      <div className="adoption-actions"><button className="button button-primary" type="button" disabled={saving || selected.length === 0 || Boolean(parseError) || Boolean(editError) || tooManyEntries} onClick={() => void apply()}>{saving ? 'Сохраняем…' : `Добавить выбранное (${selected.length})`}</button><button className="button button-secondary" type="button" disabled={saving} onClick={() => { setDraft(undefined); setEdited({}); setSelected([]); setError(''); setParseError(''); setEditError(''); }}>Закрыть черновик</button></div>
    </div>}
  </section>;
}
