import type { Schemas } from '../../api';

export type QueueItem = Schemas['NextStepQueueItem'];
export type QueuePage = Schemas['NextStepQueuePage'];
export type QueueState = {
  identity: string;
  generation: number;
  items: QueueItem[];
  nextCursor: string | null;
  initialStatus: 'idle' | 'loading' | 'ready' | 'error';
  moreStatus: 'idle' | 'loading' | 'error';
  error: string;
};
export type QueueRequest = { identity: string; generation: number; kind: 'refresh' | 'more'; cursor: string | null };

export function createQueueState(identity: string, generation = 0): QueueState {
  return { identity, generation, items: [], nextCursor: null, initialStatus: 'idle', moreStatus: 'idle', error: '' };
}

export function beginQueueRequest(state: QueueState, kind: QueueRequest['kind']): { state: QueueState; request: QueueRequest } {
  const generation = state.generation + 1;
  const cursor = kind === 'more' ? state.nextCursor : null;
  const request = { identity: state.identity, generation, kind, cursor };
  return {
    request,
    state: kind === 'refresh'
      ? { ...createQueueState(state.identity, generation), initialStatus: 'loading' }
      : { ...state, generation, moreStatus: 'loading', error: '' },
  };
}

export function clearQueue(state: QueueState, identity = state.identity): QueueState {
  return createQueueState(identity, state.generation + 1);
}

function isCurrent(state: QueueState, request: QueueRequest): boolean {
  return state.identity === request.identity && state.generation === request.generation;
}

export function denyQueueRequest(state: QueueState, request: QueueRequest): QueueState {
  if (!isCurrent(state, request)) return state;
  return { ...clearQueue(state), initialStatus: 'error', error: 'Очередь действий сейчас недоступна.' };
}

function mergeItems(current: readonly QueueItem[], incoming: readonly QueueItem[]): QueueItem[] {
  const merged = new Map(current.map((item) => [item.invitation_id, item]));
  for (const item of incoming) merged.set(item.invitation_id, item);
  return [...merged.values()];
}

export function applyQueuePage(state: QueueState, request: QueueRequest, page: QueuePage): QueueState {
  if (!isCurrent(state, request)) return state;
  const items = request.kind === 'refresh' ? mergeItems([], page.items) : mergeItems(state.items, page.items);
  return {
    ...state, items, nextCursor: page.next_cursor, initialStatus: 'ready', moreStatus: 'idle', error: '',
  };
}

export function failQueueRequest(state: QueueState, request: QueueRequest, error: string): QueueState {
  if (!isCurrent(state, request)) return state;
  return request.kind === 'refresh'
    ? { ...state, initialStatus: 'error', moreStatus: 'idle', error }
    : { ...state, moreStatus: 'error', error };
}

export function handleQueueFailure(state: QueueState, request: QueueRequest, status: number, code: string | undefined, error: string): QueueState {
  const accessUnavailable = [401, 403, 404].includes(status) || (status === 422 && code === 'EMAIL_UNCONFIRMED');
  return accessUnavailable ? denyQueueRequest(state, request) : failQueueRequest(state, request, error);
}

export function isQueueEmpty(state: QueueState): boolean {
  return state.initialStatus === 'ready' && state.nextCursor === null && state.items.length === 0;
}
