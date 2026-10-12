import { useEffect, useLayoutEffect, useRef } from 'react';
import { Link } from 'react-router';
import { nextStepStatus } from './hiring-plan';
import type { useNextStepQueue } from './useNextStepQueue';

function date(value: string | null): string {
  return value ? new Date(value).toLocaleString('ru-RU', { timeZoneName: 'short' }) : 'Срок не указан';
}

export function NextStepQueue({ queue, role }: { queue: ReturnType<typeof useNextStepQueue>; role: 'candidate' | 'employer' }) {
  const sectionRef = useRef<HTMLElement | null>(null);
  const focusedInvitation = useRef<string | null>(null);
  const previousStatus = useRef(queue.initialStatus);
  const previousIdentity = useRef(queue.identity);
  const isRefreshing = useRef(queue.initialStatus === 'loading');
  isRefreshing.current = queue.initialStatus === 'loading';

  useEffect(() => {
    const leaveQueue = (event: PointerEvent) => {
      if (event.target instanceof Node && !sectionRef.current?.contains(event.target)) focusedInvitation.current = null;
    };
    document.addEventListener('pointerdown', leaveQueue, true);
    return () => document.removeEventListener('pointerdown', leaveQueue, true);
  }, []);

  useLayoutEffect(() => {
    if (previousIdentity.current !== queue.identity) {
      previousIdentity.current = queue.identity;
      previousStatus.current = queue.initialStatus;
      focusedInvitation.current = null;
      return;
    }
    const wasLoading = previousStatus.current === 'loading';
    previousStatus.current = queue.initialStatus;
    if (!wasLoading || queue.initialStatus === 'loading') return;

    const invitationId = focusedInvitation.current;
    focusedInvitation.current = null;
    if (!invitationId || queue.initialStatus !== 'ready' || document.activeElement !== document.body) return;
    const action = [...(sectionRef.current?.querySelectorAll<HTMLAnchorElement>('[data-queue-invitation]') ?? [])]
      .find((link) => link.dataset.queueInvitation === invitationId);
    action?.focus({ preventScroll: true });
  }, [queue.initialStatus, queue.items]);

  return <section ref={sectionRef} className="card hiring-action-queue" aria-labelledby="hiring-action-queue-title" onFocusCapture={(event) => {
    const target = event.target;
    focusedInvitation.current = target instanceof HTMLElement
      ? target.closest<HTMLElement>('[data-queue-invitation]')?.dataset.queueInvitation ?? null
      : null;
  }} onBlurCapture={(event) => {
    const nextTarget = event.relatedTarget;
    if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
    if (isRefreshing.current && nextTarget === null) return;
    focusedInvitation.current = null;
  }}>
    <div className="hiring-action-queue-heading"><h2 id="hiring-action-queue-title">Действия и сроки</h2>{queue.initialStatus === 'ready' && queue.items.length > 0 && <span>{queue.items.length}{queue.nextCursor ? '+' : ''}</span>}</div>
    {queue.initialStatus === 'loading' && <p role="status">Загружаем актуальные шаги…</p>}
    {queue.initialStatus === 'error' && <div role="alert"><p>{queue.error}</p><button className="text-button" type="button" onClick={queue.refresh}>Повторить</button></div>}
    {queue.initialStatus === 'ready' && queue.empty && <p>Актуальных следующих шагов пока нет.</p>}
    {queue.items.length > 0 && <ul className="hiring-action-queue-list">{queue.items.map((item) => {
      const status = nextStepStatus(item.step);
      const actionOwner = item.step.responsible_party === 'company' ? 'Действие от компании' : 'Действие от кандидата';
      const actorRelation = item.is_my_action ? 'ваша сторона по плану' : 'ожидается по плану';
      const publicCandidateName = item.candidate_visibility === 'public' ? item.candidate_display_name : null;
      const party = role === 'candidate' ? item.company_name : publicCandidateName ?? `Обсуждение · ${item.invitation_id.slice(0, 8)}`;
      const title = role === 'candidate' ? item.company_name : party;
      return <li key={item.invitation_id}>
        <div><strong>{title}</strong><p>{item.step.action}</p><small>{actionOwner} · {actorRelation}</small></div>
        <div className="hiring-action-queue-deadline">{item.step.due_at ? <time dateTime={item.step.due_at}>{date(item.step.due_at)}</time> : <span>Срок не указан</span>}{status === 'overdue' && <span className="hiring-overdue">Срок по плану прошёл</span>}<Link data-queue-invitation={item.invitation_id} to={`/messages/${encodeURIComponent(item.invitation_id)}`}>Открыть обсуждение →</Link></div>
      </li>;
    })}</ul>}
    {queue.moreStatus === 'loading' && <p role="status">Загружаем следующую страницу…</p>}
    {queue.moreStatus === 'error' && <div role="alert"><p>{queue.error}</p><button className="text-button" type="button" onClick={queue.loadMore}>Повторить загрузку</button></div>}
    {queue.nextCursor && queue.moreStatus !== 'loading' && <button type="button" className="button button-secondary button-small" onClick={queue.loadMore}>Показать ещё</button>}
    {queue.depthLimitReached && queue.nextCursor && <p role="status">Показаны первые 100 актуальных действий. Нажмите «Показать ещё», чтобы продолжить очередь.</p>}
    <p className="hiring-action-queue-note">Это предложенный компанией план; дата не подтверждает согласование сторон.</p>
  </section>;
}
