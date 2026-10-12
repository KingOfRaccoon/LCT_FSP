export type ProctoringChannel = 'camera' | 'screen';

export interface PendingProctoringChunk {
  captureId: string;
  channel: ProctoringChannel;
  sequence: number;
  blob: Blob;
}

export interface ProctoringChunkReceipt {
  session_id: string;
  capture_id: string;
  channel: ProctoringChannel;
  sequence: number;
  sha256: string;
  bytes: number;
}

export interface ProctoringChunkUploadOptions {
  idempotencyKey: string;
  signal: AbortSignal;
}

export class ProctoringUploadLimitError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ProctoringUploadLimitError';
  }
}

export class ProctoringUploadQueueClosedError extends Error {
  constructor() {
    super('Proctoring upload queue is closed.');
    this.name = 'ProctoringUploadQueueClosedError';
  }
}

export class SerialChunkUploadQueue {
  private readonly abortController = new AbortController();
  private readonly createIdempotencyKey: () => string;
  private readonly options: {
    maxChunkBytes: number;
    maxSessionBytes: number;
    maxRetries?: number;
    createIdempotencyKey?: () => string;
    upload: (chunk: PendingProctoringChunk, options: ProctoringChunkUploadOptions) => Promise<ProctoringChunkReceipt>;
  };
  private tail: Promise<void> = Promise.resolve();
  private reservedBytes = 0;
  private pendingBytes = 0;
  private readonly acceptedSequences: Record<ProctoringChannel, number> = { camera: -1, screen: -1 };
  private failedError: unknown;
  private closed = false;

  constructor(options: {
    maxChunkBytes: number;
    maxSessionBytes: number;
    maxRetries?: number;
    createIdempotencyKey?: () => string;
    upload: (chunk: PendingProctoringChunk, options: ProctoringChunkUploadOptions) => Promise<ProctoringChunkReceipt>;
  }) {
    this.options = options;
    this.createIdempotencyKey = options.createIdempotencyKey ?? (() => crypto.randomUUID());
  }

  get queuedBytes(): number { return this.pendingBytes; }
  get totalReservedBytes(): number { return this.reservedBytes; }
  get signal(): AbortSignal { return this.abortController.signal; }
  get lastAcceptedSequences(): Record<ProctoringChannel, number> { return { ...this.acceptedSequences }; }

  enqueue(chunk: PendingProctoringChunk): Promise<ProctoringChunkReceipt> {
    if (this.closed || this.signal.aborted) return Promise.reject(new ProctoringUploadQueueClosedError());
    if (chunk.blob.size > this.options.maxChunkBytes) {
      return Promise.reject(new ProctoringUploadLimitError('Chunk exceeds the server byte limit.'));
    }
    if (this.reservedBytes + chunk.blob.size > this.options.maxSessionBytes) {
      return Promise.reject(new ProctoringUploadLimitError('Session media exceeds the server byte limit.'));
    }

    const idempotencyKey = this.createIdempotencyKey();
    this.reservedBytes += chunk.blob.size;
    this.pendingBytes += chunk.blob.size;
    const operation = this.tail.then(() => this.uploadWithBoundedRetry(chunk, idempotencyKey));
    this.tail = operation.then(() => undefined, (error) => { this.failedError ??= error; });
    return operation.then((receipt) => {
      this.pendingBytes -= chunk.blob.size;
      this.acceptedSequences[chunk.channel] = Math.max(this.acceptedSequences[chunk.channel], chunk.sequence);
      return receipt;
    }, (error) => {
      this.pendingBytes -= chunk.blob.size;
      this.reservedBytes -= chunk.blob.size;
      throw error;
    });
  }

  async drain(): Promise<void> {
    await this.tail;
    if (this.failedError) throw this.failedError;
    if (this.signal.aborted) throw new ProctoringUploadQueueClosedError();
  }

  abort(): void {
    if (this.closed) return;
    this.closed = true;
    this.abortController.abort();
  }

  private async uploadWithBoundedRetry(chunk: PendingProctoringChunk, idempotencyKey: string): Promise<ProctoringChunkReceipt> {
    let retry = 0;
    while (true) {
      if (this.signal.aborted) throw new ProctoringUploadQueueClosedError();
      try {
        return await this.options.upload(chunk, { idempotencyKey, signal: this.signal });
      } catch (error) {
        const retryable = Boolean(error && typeof error === 'object' && 'retryable' in error && error.retryable === true);
        if (!retryable || retry >= (this.options.maxRetries ?? 2) || this.signal.aborted) {
          this.abort();
          throw error;
        }
        retry++;
        await new Promise<void>((resolve, reject) => {
          const timer = setTimeout(resolve, 250 * retry);
          this.signal.addEventListener('abort', () => { clearTimeout(timer); reject(new ProctoringUploadQueueClosedError()); }, { once: true });
        });
      }
    }
  }
}
