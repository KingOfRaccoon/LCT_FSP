const retryDelay = (attempt: number) => 500 * 2 ** attempt;

export function isRetryableSaveError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false;
  const status = 'status' in error ? error.status : undefined;
  if (typeof status === 'number') return status >= 500 && status < 600;
  return error instanceof Error && error.message.startsWith('Нет связи с сервером.');
}

export function snapshotMatches(server: unknown, draft: unknown): boolean {
  const stable = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(stable);
    if (!value || typeof value !== 'object') return value;
    return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)).map(([key, item]) => [key, stable(item)]));
  };
  return JSON.stringify(stable(server)) === JSON.stringify(stable(draft));
}

export function reconcileAnswerDraft<T>({ initialized, draft, acknowledged, server }: { initialized: boolean; draft: T; acknowledged: T; server: T }): { answers: T; shouldAdoptServer: boolean; conflict: boolean } {
  if (!initialized) return { answers: server, shouldAdoptServer: true, conflict: false };
  if (snapshotMatches(draft, server)) return { answers: server, shouldAdoptServer: true, conflict: false };
  const hasLocalEdits = !snapshotMatches(draft, acknowledged);
  const serverChanged = !snapshotMatches(server, acknowledged);
  return {
    answers: hasLocalEdits ? draft : server,
    shouldAdoptServer: !hasLocalEdits,
    conflict: hasLocalEdits && serverChanged,
  };
}

export function selectedAnswerCount(answers: readonly { selected_option_id: string | null }[]): number {
  return answers.reduce((count, answer) => count + Number(Boolean(answer.selected_option_id)), 0);
}

export function isContactResponseCurrent(state: {
  requestId: number;
  currentRequestId: number;
  revealed: boolean;
  visible: boolean;
  authenticated: boolean;
}): boolean {
  return state.requestId === state.currentRequestId && state.revealed && state.visible && state.authenticated;
}

export async function readContactIfAuthorized<T>({
  clear,
  read,
  revealed,
  visible,
  authenticated,
}: {
  clear: () => void;
  read: () => Promise<T>;
  revealed: boolean;
  visible: boolean;
  authenticated: boolean;
}): Promise<{ status: 'skipped' } | { status: 'success'; value: T } | { status: 'error'; error: unknown }> {
  if (!revealed || !visible || !authenticated) return { status: 'skipped' };
  clear();
  try {
    return { status: 'success', value: await read() };
  } catch (error) {
    return { status: 'error', error };
  }
}

export async function runBoundedSave<T>({
  save,
  reconcile,
  matches,
  wait,
  isTerminal,
  maxRetries = 2,
}: {
  save: () => Promise<T>;
  reconcile: () => Promise<T | null>;
  matches: (state: T) => boolean;
  wait: (delayMs: number) => Promise<void>;
  isTerminal?: (state: T) => boolean;
  maxRetries?: number;
}): Promise<T> {
  let retries = 0;
  while (true) {
    try {
      return await save();
    } catch (error) {
      try {
        const serverState = await reconcile();
        if (serverState && (matches(serverState) || isTerminal?.(serverState))) return serverState;
      } catch {
        // Reconciliation is best-effort while the server may still be unreachable.
      }
      if (!isRetryableSaveError(error)) throw error;
      if (retries >= maxRetries) throw error;
      await wait(retryDelay(retries));
      retries += 1;
    }
  }
}
