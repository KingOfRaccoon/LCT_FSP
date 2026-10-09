import { useCallback, useEffect, useRef, useState } from 'react';
import { api, errorMessage, RequestError, type User } from '../../api';
import { applyQueuePage, beginQueueRequest, clearQueue, createQueueState, handleQueueFailure, isQueueEmpty, type QueuePage, type QueueState } from './queue-state';

const pageLimit = 5;

export function useNextStepQueue(user: Pick<User, 'account_type' | 'user_id'>) {
  const role = user.account_type === 'candidate' ? 'candidate' : 'employer';
  const identity = `${role}:${user.user_id}`;
  const [snapshot, setSnapshot] = useState(() => createQueueState(identity));
  const stateRef = useRef(snapshot);
  const commit = useCallback((update: (current: QueueState) => QueueState) => {
    const next = update(stateRef.current);
    stateRef.current = next;
    setSnapshot(next);
    return next;
  }, []);
  const visibleState = snapshot.identity === identity ? snapshot : createQueueState(identity, snapshot.generation + 1);

  const load = useCallback(async (kind: 'refresh' | 'more') => {
    let request: ReturnType<typeof beginQueueRequest>['request'];
    const current = stateRef.current.identity === identity ? stateRef.current : createQueueState(identity, stateRef.current.generation + 1);
    if (kind === 'more' && (!current.nextCursor || current.moreStatus === 'loading' || current.initialStatus !== 'ready')) return;
    const started = beginQueueRequest(current, kind);
    request = started.request;
    commit(() => started.state);
    const cursor = request.cursor;
    const path = `/me/next-steps?limit=${pageLimit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`;
    try {
      const page = await api.get<QueuePage>(path);
      commit((state) => applyQueuePage(state, request, page));
    } catch (reason) {
      const status = reason instanceof RequestError ? reason.status : 0;
      const code = reason instanceof RequestError ? reason.code : undefined;
      commit((state) => handleQueueFailure(state, request, status, code, errorMessage(reason)));
    }
  }, [commit, identity]);

  useEffect(() => {
    commit((state) => clearQueue(state, identity));
    void load('refresh');
    const refreshWhenVisible = () => { if (document.visibilityState === 'visible') void load('refresh'); };
    const interval = window.setInterval(refreshWhenVisible, 30_000);
    window.addEventListener('focus', refreshWhenVisible);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.clearInterval(interval);
      window.removeEventListener('focus', refreshWhenVisible);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
      stateRef.current = clearQueue(stateRef.current, identity);
    };
  }, [commit, identity, load]);

  const loadMore = useCallback(() => void load('more'), [load]);
  const refresh = useCallback(() => void load('refresh'), [load]);
  return { ...visibleState, empty: isQueueEmpty(visibleState), loadMore, refresh };
}
