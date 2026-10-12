import type { Schemas } from '../../api';

export function readHiringPlan(data: FormData): Schemas['HiringPlan'] | null {
  if (!data.has('hiring_plan_enabled')) return null;
  const titles = data.getAll('hiring_stage_title').map(String);
  const minutes = data.getAll('hiring_stage_minutes').map(String);
  const criteria = data.getAll('hiring_stage_criteria').map(String);
  const responseDays = Number(data.get('hiring_response_days'));
  if (!Number.isInteger(responseDays) || responseDays < 1 || responseDays > 30) throw new Error('Укажите срок обратной связи от 1 до 30 календарных дней.');
  if (!titles.length || titles.length > 5 || minutes.length !== titles.length || criteria.length !== titles.length) throw new Error('План должен содержать от одного до пяти полных этапов.');
  const stages = titles.map((title, index) => {
    const effort = Number(minutes[index]);
    const criterion = criteria[index].trim();
    if (!title.trim() || title.trim().length > 120 || !criterion || criterion.length > 1000 || !Number.isInteger(effort) || effort < 1 || effort > 480) throw new Error(`Проверьте этап ${index + 1}: название, критерии и время от 1 до 480 минут.`);
    return { title: title.trim(), effort_minutes: effort, criteria: criterion };
  });
  return { stages, response_time_days: responseDays };
}

export function hiringEffortMinutes(plan: Schemas['HiringPlan']): number {
  return plan.stages.reduce((total, stage) => total + stage.effort_minutes, 0);
}

export function toStepInstant(value: string): string | null {
  if (!value.trim()) return null;
  const fields = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})?$/.exec(value);
  if (!fields) throw new Error('Проверьте дату и время следующего шага.');
  const [year, month, day, hour, minute, second] = fields.slice(1, 7).map((field) => Number(field ?? 0));
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  if (month < 1 || month > 12 || day < 1 || day > days[month - 1] || hour > 23 || minute > 59 || second > 59) throw new Error('Проверьте дату и время следующего шага.');
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) throw new Error('Проверьте дату и время следующего шага.');
  if (!fields[7] && (instant.getFullYear() !== year || instant.getMonth() + 1 !== month || instant.getDate() !== day || instant.getHours() !== hour || instant.getMinutes() !== minute)) throw new Error('Это местное время отсутствует в вашем часовом поясе. Выберите другое время.');
  return instant.toISOString();
}

export function nextStepStatus(step: Pick<Schemas['HiringNextStep'], 'completed' | 'due_at'>, now = Date.now()): 'completed' | 'overdue' | 'planned' {
  if (step.completed) return 'completed';
  return step.due_at && Date.parse(step.due_at) <= now ? 'overdue' : 'planned';
}
