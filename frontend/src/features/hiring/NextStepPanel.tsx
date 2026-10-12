import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { api, errorMessage, RequestError, type Schemas } from '../../api';
import { createPendingRequest } from '../extensions/task-helpers';
import { HiringPlanView } from './HiringPlan';
import { nextStepStatus, toStepInstant } from './hiring-plan';

type Model = Schemas['InvitationNextStep'];
type Payload = Schemas['InvitationNextStepPutRequest'];
type Draft = { action: string; responsible_party: 'company' | 'candidate'; due_at: string; completed: boolean };
type Pending = { payload: Payload; idempotencyKey: string };
const emptyDraft: Draft = { action: '', responsible_party: 'company', due_at: '', completed: false };
const date = (value: string) => new Date(value).toLocaleString('ru-RU', { timeZoneName: 'short' });
function localTime(value: string | null) {
  if (!value) return '';
  const instant = new Date(value);
  return new Date(instant.getTime() - instant.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
}

export function NextStepPanel({ invitationId }: { invitationId: string }) {
  const [data, setData] = useState<Model>();
  const [loading, setLoading] = useState(true), [closed, setClosed] = useState(false);
  const [error, setError] = useState(''), [notice, setNotice] = useState('');
  const [draft, setDraft] = useState<Draft>(emptyDraft), [baseVersion, setBaseVersion] = useState(0);
  const [editing, setEditing] = useState(false), [saving, setSaving] = useState(false);
  const [pending, setPending] = useState<Pending>(), [needsRefresh, setNeedsRefresh] = useState(false);
  const requestGeneration = useRef(0), accessGeneration = useRef(0), saveGeneration = useRef(0), conflict = useRef(false);
  const path = `/invitations/${encodeURIComponent(invitationId)}/next-step`;
  const denyAccess = useCallback(() => {
    accessGeneration.current += 1;
    setData(undefined); setClosed(true); setEditing(false); setPending(undefined); setDraft(emptyDraft); setNotice(''); setSaving(false);
  }, []);
  const refresh = useCallback(async () => {
    const generation = ++requestGeneration.current, access = accessGeneration.current;
    const current = () => generation === requestGeneration.current && access === accessGeneration.current;
    try {
      const result = await api.get<Model>(path);
      if (!current()) return undefined;
      setData(result); setClosed(false);
      if (!result.can_update) { setEditing(false); setPending(undefined); }
      if (conflict.current) { setBaseVersion(result.step?.version ?? 0); conflict.current = false; setNeedsRefresh(false); }
      return result;
    } catch (reason) {
      if (!current()) return undefined;
      const status = reason instanceof RequestError ? reason.status : 0;
      if ([401, 403, 404].includes(status)) { denyAccess(); setError('Доступ к следующему шагу закрыт.'); }
      else setError(errorMessage(reason));
      return undefined;
    } finally { if (generation === requestGeneration.current) setLoading(false); }
  }, [path, denyAccess]);
  useEffect(() => {
    setLoading(true); setData(undefined); setClosed(false); setEditing(false); setPending(undefined); setError(''); setNotice(''); setDraft(emptyDraft); setNeedsRefresh(false); conflict.current = false;
    void refresh();
    const onFocus = () => { if (document.visibilityState === 'visible') void refresh(); };
    const timer = window.setInterval(onFocus, 30_000);
    window.addEventListener('focus', onFocus); document.addEventListener('visibilitychange', onFocus);
    return () => { requestGeneration.current += 1; accessGeneration.current += 1; saveGeneration.current += 1; window.clearInterval(timer); window.removeEventListener('focus', onFocus); document.removeEventListener('visibilitychange', onFocus); };
  }, [refresh]);
  const edit = () => {
    const step = data?.step;
    setDraft(step ? { action: step.action, responsible_party: step.responsible_party, due_at: localTime(step.due_at), completed: step.completed } : emptyDraft);
    setBaseVersion(step?.version ?? 0); setPending(undefined); setEditing(true); setError(''); setNotice(''); setNeedsRefresh(false); conflict.current = false;
  };
  const change = (patch: Partial<Draft>) => { setDraft((value) => ({ ...value, ...patch })); setPending(undefined); setNotice(''); };
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving || needsRefresh || !data?.can_update || closed) return;
    let payload: Payload;
    try {
      if (!draft.action.trim() || draft.action.trim().length > 500) throw new Error('Опишите следующий шаг: от 1 до 500 символов.');
      const dueAt = String(new FormData(event.currentTarget).get('next_step_due_at') ?? '');
      payload = { expected_version: baseVersion, action: draft.action.trim(), responsible_party: draft.responsible_party, due_at: toStepInstant(dueAt), completed: draft.completed };
    } catch (reason) { setError(errorMessage(reason)); return; }
    const request = createPendingRequest(payload, () => crypto.randomUUID(), pending);
    setPending(request); setSaving(true); setError(''); setNotice('');
    const access = accessGeneration.current, generation = ++saveGeneration.current;
    const current = () => access === accessGeneration.current && generation === saveGeneration.current;
    try {
      const result = await api.put<Model>(path, request.payload, request.idempotencyKey);
      if (!current()) return;
      requestGeneration.current += 1;
      setData(result); setPending(undefined); setEditing(false); setNotice('Следующий шаг сохранён и виден кандидату.');
    } catch (reason) {
      if (!current()) return;
      const status = reason instanceof RequestError ? reason.status : 0;
      if ([401, 403, 404].includes(status)) { denyAccess(); setError('Доступ к следующему шагу закрыт.'); }
      else if (status === 409) {
        setPending(undefined); conflict.current = true; setNeedsRefresh(true);
        const latest = await refresh();
        if (!current()) return;
        setError(latest ? 'Шаг изменился. Свежая версия показана выше, ваш черновик сохранён. Сравните их перед повторным сохранением.' : 'Не удалось загрузить свежую версию. Обновите данные перед повторным сохранением.');
      } else setError(errorMessage(reason));
    } finally { if (current()) setSaving(false); }
  };
  const step = data?.step, status = step ? nextStepStatus(step) : undefined;
  return <section className="hiring-next-step" aria-label="Общий следующий шаг">
    <h2>Следующий шаг</h2>
    {loading && <p role="status">Загружаем план и следующий шаг…</p>}
    {error && <div className="extension-alert" role="alert">{error}<button className="text-button" type="button" onClick={() => { setError(''); void refresh(); }}>Обновить данные</button></div>}
    {notice && <p role="status">{notice}</p>}
    {closed && <p role="status">Сервер закрыл доступ. Прежние сведения скрыты.</p>}
    {data && <>
      {data.archived && <p className="field-hint">Архив после отзыва разрешения. Компания больше не может читать или менять следующий шаг.</p>}
      {step ? <><p><strong>{step.action}</strong></p><dl><div><dt>Действие ожидается от</dt><dd>{step.responsible_party === 'company' ? 'Компании' : 'Кандидата'}</dd></div><div><dt>Срок по плану компании</dt><dd>{step.due_at ? date(step.due_at) : 'Срок не указан'}</dd></div><div><dt>Состояние</dt><dd className={status === 'overdue' ? 'hiring-overdue' : undefined}>{status === 'completed' ? 'Компания отметила выполнение' : status === 'overdue' ? 'Указанный срок прошёл' : 'Предложен компанией'}</dd></div><div><dt>Обновлено компанией</dt><dd>{date(step.updated_at)}</dd></div></dl></> : <p>Компания пока не указала следующий шаг.</p>}
      <p className="field-hint">Это предложение компании. Оно не подтверждает согласование времени, прохождение интервью или трудоустройство.</p>
      {data.hiring_plan ? <details className="hiring-plan-details"><summary>Сохранённый план найма</summary><HiringPlanView plan={data.hiring_plan} /></details> : <HiringPlanView plan={null} />}
      {data.can_update && !editing && <button type="button" className="button button-secondary button-small" onClick={edit}>{step ? 'Обновить следующий шаг' : 'Указать следующий шаг'}</button>}
      {data.can_update && editing && <form onSubmit={(event) => void save(event)}>
        <label className="field"><span>Что будет дальше</span><textarea value={draft.action} onChange={(event) => change({ action: event.target.value })} required maxLength={500} rows={3} disabled={saving} /></label>
        <div className="form-grid"><label className="field"><span>От кого ждём действия</span><select value={draft.responsible_party} onChange={(event) => change({ responsible_party: event.target.value as Draft['responsible_party'] })} disabled={saving}><option value="company">От компании</option><option value="candidate">От кандидата</option></select></label><label className="field"><span>Дата и время · {Intl.DateTimeFormat().resolvedOptions().timeZone}</span><input name="next_step_due_at" type="datetime-local" value={draft.due_at} onInput={(event) => change({ due_at: event.currentTarget.value })} disabled={saving} /></label></div>
        <label className="hiring-checkbox"><input type="checkbox" checked={draft.completed} onChange={(event) => change({ completed: event.target.checked })} disabled={saving} /><span>Компания отмечает шаг выполненным</span></label>
        <div className="hiring-stage-actions"><button type="submit" className="button button-primary button-small" disabled={saving || needsRefresh || !draft.action.trim()}>{saving ? 'Сохраняем…' : pending ? 'Повторить сохранение' : 'Сохранить общий шаг'}</button><button className="text-button" type="button" disabled={saving} onClick={() => { setEditing(false); setPending(undefined); }}>Отменить</button></div>
      </form>}
    </>}
  </section>;
}
