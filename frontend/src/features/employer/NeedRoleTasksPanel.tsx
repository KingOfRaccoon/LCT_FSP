import { useState } from 'react';
import { api, errorMessage, skillLabels, type ApiPayload, type Need, type Schemas } from '../../api';
import { RoleTaskEditor } from './EmployerWorkflow';

/** Editable role context that returns active needs to draft before search can use changed tasks. */
export function NeedRoleTasksPanel({ need, onSaved }: { need: Need; onSaved: (need: Need) => void }) {
  const [editing, setEditing] = useState(false);
  const [tasks, setTasks] = useState<Schemas['RoleTask'][]>(need.role_tasks ?? []);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const save = async () => {
    if (tasks.some((task) => !task.description.trim())) {
      setError('Заполните описание каждой задачи или удалите пустую.');
      return;
    }
    if (!window.confirm('Изменение задач переведёт потребность обратно в черновик. Перед новым поиском потребуется подтвердить компетенции.')) return;
    setSaving(true); setError('');
    try {
      const updated = await api.patch<Need>(`/needs/${encodeURIComponent(need.id)}`, {
        expected_version: need.version,
        status: 'draft',
        role_tasks: tasks,
      } as ApiPayload);
      onSaved(updated); setEditing(false);
    } catch (reason) { setError(errorMessage(reason)); }
    finally { setSaving(false); }
  };

  return <div className="need-role-task-panel">
    <div className="need-role-task-head"><strong>Задачи роли</strong><button type="button" className="text-button" onClick={() => { setTasks(need.role_tasks ?? []); setError(''); setEditing((current) => !current); }}>{editing ? 'Закрыть' : 'Изменить задачи'}</button></div>
    {error && <div className="error-box" role="alert">{error}</div>}
    {editing ? <><RoleTaskEditor requirements={need.requirements} value={tasks} onChange={setTasks} /><div className="card-actions"><button type="button" className="button button-primary button-small" disabled={saving} onClick={() => void save()}>{saving ? 'Сохраняем…' : 'Сохранить и вернуть в черновик'}</button></div></> : need.role_tasks?.length ? <ul>{need.role_tasks.map((task) => <li key={task.id}><span>{task.description}</span><small>{task.skill_ids.map((id) => skillLabels[id as keyof typeof skillLabels] ?? id).join(' · ')}</small></li>)}</ul> : <p className="field-hint">Задачи ещё не добавлены.</p>}
  </div>;
}
