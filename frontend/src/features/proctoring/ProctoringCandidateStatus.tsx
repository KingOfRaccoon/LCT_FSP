import type { ServerProctoringStatus } from './server-proctoring-client.ts';

export function ProctoringCandidateStatus({
  status,
  publishedGrade,
  onRevoke,
}: {
  status?: ServerProctoringStatus | null;
  publishedGrade?: string | null;
  onRevoke?: () => void;
}) {
  if (!status) return null;
  const revokeAction = onRevoke && !['revoked', 'expired'].includes(status)
    ? <button type="button" className="button button-secondary button-small" onClick={onRevoke}>{publishedGrade ? 'Удалить запись и закрыть доступ' : 'Отозвать согласие и удалить запись'}</button>
    : null;

  if (status === 'review_pending') return <section className="card" role="status" aria-label="Результат ожидает серверной проверки">
    <strong>Попытка ожидает решения оператора</strong>
    <p>Сервер хранит оценку как предварительную. Балл и грейд не опубликованы и появятся только после решения уполномоченного оператора.</p>
    {revokeAction}
  </section>;
  if (status === 'approved') return <section className="card" role="status" aria-label="Проверка серверной записи завершена">
    <strong>Проверка серверной записи завершена</strong>
    {publishedGrade && <p>Опубликованный сервером грейд {publishedGrade} останется без изменений; удаление записи закроет дальнейший доступ к медиа.</p>}
    {revokeAction}
  </section>;
  if (status === 'technical_failure') return <section className="card" role="status" aria-label="Техническая ошибка серверной записи">
    <strong>Сервер отметил техническую ошибку записи</strong>
    <p>Этот статус не меняет ранее подтверждённую квалификацию.</p>
    {revokeAction}
  </section>;
  if (status === 'invalidated') return <section className="card" role="status" aria-label="Сервер аннулировал попытку">
    <strong>Попытка аннулирована решением сервера</strong>
    <p>Предыдущая подтверждённая квалификация не меняется.</p>
    {revokeAction}
  </section>;
  if (status === 'revoked') return <section className="card" role="status" aria-label="Доступ к серверной записи закрыт">
    <strong>Согласие отозвано; доступ к серверной записи закрыт</strong>
  </section>;
  if (status === 'expired') return <section className="card" role="status" aria-label="Срок хранения серверной записи истёк">
    <strong>Срок хранения серверной записи истёк</strong>
  </section>;
  return null;
}
