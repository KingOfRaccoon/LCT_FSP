import { useState } from 'react';
import type { Schemas } from '../../api';
import { hiringEffortMinutes } from './hiring-plan';
import './hiring.css';

export function HiringPlanEditor({ initialPlan }: { initialPlan?: Schemas['HiringPlan'] | null }) {
  const [enabled, setEnabled] = useState(Boolean(initialPlan));
  const [count, setCount] = useState(initialPlan?.stages.length ?? 1);
  return <section className="hiring-plan-editor" aria-label="Настройка плана найма">
    <label className="hiring-checkbox"><input name="hiring_plan_enabled" type="checkbox" checked={enabled} onChange={(event) => setEnabled(event.target.checked)} /><span>Добавить план найма</span></label>
    <p className="field-hint">Кандидат увидит этапы, затраты времени и срок обратной связи до принятия приглашения.</p>
    <div hidden={!enabled}>
      {Array.from({ length: count }, (_, index) => <fieldset className="hiring-stage-editor" key={index} disabled={!enabled}>
        <legend>Этап {index + 1}</legend>
        <div className="form-grid">
          <label className="field"><span>Название этапа</span><input name="hiring_stage_title" defaultValue={initialPlan?.stages[index]?.title} maxLength={120} required /></label>
          <label className="field"><span>Время кандидата, минут</span><input name="hiring_stage_minutes" type="number" min={1} max={480} step={1} defaultValue={initialPlan?.stages[index]?.effort_minutes} required /></label>
          <label className="field span-all"><span>Что проверяем и по каким критериям</span><textarea name="hiring_stage_criteria" defaultValue={initialPlan?.stages[index]?.criteria} rows={2} maxLength={1000} required /></label>
        </div>
      </fieldset>)}
      <div className="hiring-stage-actions"><button className="button button-secondary button-small" type="button" disabled={count >= 5} onClick={() => setCount((value) => value + 1)}>Добавить этап</button>{count > 1 && <button className="text-button" type="button" onClick={() => setCount((value) => value - 1)}>Убрать последний этап</button>}</div>
      <label className="field hiring-response-field"><span>Ответ после каждого этапа, календарных дней</span><input name="hiring_response_days" type="number" min={1} max={30} step={1} defaultValue={initialPlan?.response_time_days} required disabled={!enabled} /></label>
      <p className="field-hint">Это объявленный план компании. Изменение отправленного плана требует новой версии приглашения.</p>
    </div>
  </section>;
}

export function HiringPlanView({ plan }: { plan?: Schemas['HiringPlan'] | null }) {
  if (!plan) return <p className="field-hint hiring-plan-absent">Компания пока не указала план найма.</p>;
  return <section className="hiring-plan-view" aria-label="План найма компании">
    <h3>План найма</h3>
    <p>Ожидаемое время кандидата: <strong>{hiringEffortMinutes(plan)} мин</strong>. Ответ после каждого этапа — в течение <strong>{plan.response_time_days} календарных дней</strong>.</p>
    <ol>{plan.stages.map((stage, index) => <li key={index}><strong>{stage.title}</strong><span className="hiring-stage-effort">{stage.effort_minutes} мин</span><p>{stage.criteria}</p></li>)}</ol>
    <small className="field-hint">План объявлен компанией и сохранён в этой версии приглашения. Он не означает согласие кандидата на все этапы.</small>
  </section>;
}
