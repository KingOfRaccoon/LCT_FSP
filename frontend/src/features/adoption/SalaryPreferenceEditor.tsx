import { useEffect, useState, type FormEvent } from 'react';
import { api, errorMessage, type CandidateProfile } from '../../api';
import { salaryPreferenceFromForm, type SalaryPreference, type SalaryPreferenceForm } from './types';
import './adoption.css';

type ProfileWithSalary = CandidateProfile & { expected_salary?: SalaryPreference | null };
const emptyForm: SalaryPreferenceForm = { minRubles: '', maxRubles: '', basis: 'gross', workloadType: 'full_time', hoursPerWeek: '40' };
const fromProfile = (salary: SalaryPreference | null | undefined): SalaryPreferenceForm => salary ? {
  minRubles: String(salary.min_kopecks / 100), maxRubles: String(salary.max_kopecks / 100), basis: salary.basis,
  workloadType: salary.workload_type, hoursPerWeek: String(salary.hours_per_week),
} : emptyForm;

export function SalaryPreferenceEditor({ profile, onApplied, processingAccepted }: { profile: CandidateProfile; onApplied: () => void | Promise<void>; processingAccepted: boolean }) {
  const profileWithSalary = profile as ProfileWithSalary;
  const [form, setForm] = useState(() => fromProfile(profileWithSalary.expected_salary));
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const configured = Boolean(profileWithSalary.expected_salary);
  useEffect(() => { setForm(fromProfile(profileWithSalary.expected_salary)); setError(''); setNotice(''); }, [profile.id, profile.version]);

  const persist = async (expected_salary: SalaryPreference | null) => {
    if (saving || !processingAccepted) return;
    setSaving(true); setError(''); setNotice('');
    try {
      await api.patch<CandidateProfile>('/me/profile', { expected_version: profile.version, expected_salary } as never);
      setNotice(expected_salary ? 'Зарплатные ожидания сохранены и будут использоваться только в предварительных расчётах.' : 'Зарплатные ожидания удалены из профиля.');
      try { await onApplied(); } catch { setNotice('Профиль сохранён. Не удалось обновить его отображение; обновите данные страницы.'); }
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setSaving(false); }
  };

  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    try { void persist(salaryPreferenceFromForm(form)); }
    catch (cause) { setError(errorMessage(cause)); }
  };

  return <section className="card adoption-card adoption-salary-editor" aria-labelledby="salary-editor-title">
    <div className="card-head"><div><span className="eyebrow">НАСТРОЙКИ ПРОФИЛЯ</span><h2 id="salary-editor-title">Зарплатные ожидания</h2></div><span className="badge badge-neutral">Только для расчёта</span></div>
    <p className="adoption-intro">Укажите желаемую месячную сумму и формат занятости. Значения будут использованы для предварительной оценки совместимости с условиями компании и останутся заявленными вами сведениями.</p>
    {!processingAccepted && <p className="notice" role="note">Для сохранения требуется разрешение на обработку данных профиля. Проверьте настройки согласий.</p>}
    <form className="adoption-salary-form" onSubmit={submit}>
      <div className="adoption-form-grid"><label className="field"><span>От, ₽ в месяц</span><input type="number" inputMode="numeric" min="1" step="1" value={form.minRubles} onChange={(event) => setForm((current) => ({ ...current, minRubles: event.target.value }))} required /></label><label className="field"><span>До, ₽ в месяц</span><input type="number" inputMode="numeric" min="1" step="1" value={form.maxRubles} onChange={(event) => setForm((current) => ({ ...current, maxRubles: event.target.value }))} required /></label></div>
      <div className="adoption-form-grid"><label className="field"><span>Сумма указана</span><select value={form.basis} onChange={(event) => setForm((current) => ({ ...current, basis: event.target.value as SalaryPreference['basis'] }))}><option value="gross">До вычета налогов</option><option value="net">На руки</option></select></label><label className="field"><span>Занятость</span><select value={form.workloadType} onChange={(event) => { const workloadType = event.target.value as SalaryPreference['workload_type']; setForm((current) => ({ ...current, workloadType, hoursPerWeek: workloadType === 'full_time' ? '40' : current.hoursPerWeek === '40' ? '20' : current.hoursPerWeek })); }}><option value="full_time">Полная</option><option value="part_time">Частичная</option></select></label></div>
      <label className="field"><span>Часов в неделю</span><input type="number" min={form.workloadType === 'full_time' ? 40 : 1} max={form.workloadType === 'full_time' ? 40 : 39} step="1" value={form.hoursPerWeek} onChange={(event) => setForm((current) => ({ ...current, hoursPerWeek: event.target.value }))} required /><small>Полная занятость — 40 часов, частичная — от 1 до 39.</small></label>
      {error && <p className="notice notice-danger" role="alert">{error}</p>}{notice && <p className="notice notice-success" role="status">{notice}</p>}
      <div className="adoption-actions"><button className="button button-primary" type="submit" disabled={!processingAccepted || saving}>{saving ? 'Сохраняем…' : 'Сохранить ожидания'}</button>{configured && <button className="button button-secondary" type="button" disabled={!processingAccepted || saving} onClick={() => void persist(null)}>Удалить ожидания</button>}</div>
    </form>
  </section>;
}
