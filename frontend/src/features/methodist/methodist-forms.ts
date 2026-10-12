import type { DraftExport, DraftForm, DraftPayload, PilotObservation, PilotSource } from './types.ts';

export function buildDraftPayload(form: DraftForm): DraftPayload {
  const sourceReference = form.source_reference.trim();
  if (!sourceReference) throw new Error('Добавьте ссылку или текстовый источник.');
  if (sourceReference.length > 2048) throw new Error('Ссылка или текстовый источник не должны превышать 2048 символов.');
  const options = form.options.map((option) => option.trim());
  if (options.length !== 4 || options.some((option) => !option)) throw new Error('Нужно указать четыре непустых варианта ответа.');
  if (new Set(options.map((option) => option.toLocaleLowerCase())).size !== 4) throw new Error('Варианты ответа должны различаться.');
  const correctOption = Number(form.correct_option);
  if (!Number.isInteger(correctOption) || correctOption < 0 || correctOption > 3) throw new Error('Выберите правильный вариант ответа.');
  const required = [form.source_title, form.source_excerpt, form.competency_id, form.question, form.rubric];
  if (required.some((value) => !value.trim())) throw new Error('Заполните источник, компетенцию, вопрос и критерий ответа.');
  return {
    source_title: form.source_title.trim(), source_reference: sourceReference, source_excerpt: form.source_excerpt.trim(),
    competency_id: form.competency_id.trim(), grade: form.grade, question: form.question.trim(),
    options: options as DraftPayload['options'], correct_option: correctOption, rubric: form.rubric.trim(),
  };
}

const pilotSources = new Set<PilotSource>(['synthetic', 'pilot_record']);
export function parsePilotObservations(csv: string): PilotObservation[] {
  const lines = csv.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  if (!lines.length) throw new Error('Добавьте строки source,correct без имён и других данных участников.');
  const first = lines[0].toLowerCase().replace(/\s/g, '');
  const rows = first === 'source,correct' ? lines.slice(1) : lines;
  if (!rows.length) throw new Error('Добавьте хотя бы одно наблюдение.');
  return rows.map((line) => {
    const fields = line.split(',').map((field) => field.trim());
    if (fields.length !== 2) throw new Error('Можно передать только source и correct; личные данные участников запрещены.');
    const [source, rawCorrect] = fields;
    if (!pilotSources.has(source as PilotSource)) throw new Error('source должен быть synthetic или pilot_record.');
    const value = rawCorrect.toLowerCase();
    if (!['true', 'false', '1', '0'].includes(value)) throw new Error('correct должен содержать true/false или 1/0.');
    return { source: source as PilotSource, correct: value === 'true' || value === '1' };
  });
}

export function pilotAccuracy(correct: number, total: number): number | null {
  return total >= 10 && Number.isFinite(correct) && Number.isFinite(total) && correct >= 0 && correct <= total ? correct / total : null;
}

export function candidateFormArtifact(exported: DraftExport): DraftExport['candidate_form'] {
  return exported.candidate_form;
}

export function privateAnswerKeyArtifact(exported: DraftExport): DraftExport['private_answer_key'] {
  return exported.private_answer_key;
}

export function serializeJsonArtifact(value: unknown): string {
  return `${JSON.stringify(value, null, 2)}\n`;
}
