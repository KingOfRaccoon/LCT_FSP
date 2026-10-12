import assert from 'node:assert/strict';
import test from 'node:test';
import path from 'node:path';
import { componentHarness, deferred, nodes, textContent } from './helpers/component-harness.mjs';

test('binary upload uses CSRF, idempotency, digest headers, and sends the original Blob body', async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (String(url).endsWith('/auth/csrf')) return { ok: true, status: 200, json: async () => ({ data: { csrf_token: 'csrf-test' } }) };
    return { ok: true, status: 200, json: async () => ({ data: { accepted: true } }) };
  };
  try {
    const { api } = await import('../src/api.ts');
    const chunk = new Blob(['capture bytes'], { type: 'video/webm' });
    const receipt = await api.uploadBinary('/proctoring/sessions/session-1/media/camera/chunks/0?capture_id=capture-1', chunk, {
      sha256: 'a'.repeat(64),
      idempotencyKey: 'chunk-key',
    });

    assert.deepEqual(receipt, { accepted: true });
    assert.equal(calls[1].url, '/api/v1/proctoring/sessions/session-1/media/camera/chunks/0?capture_id=capture-1');
    assert.equal(calls[1].options.body, chunk);
    assert.equal(calls[1].options.headers['Content-Type'], 'video/webm');
    assert.equal(calls[1].options.headers['X-CSRF-Token'], 'csrf-test');
    assert.equal(calls[1].options.headers['X-Content-SHA256'], 'a'.repeat(64));
    assert.equal(calls[1].options.headers['Idempotency-Key'], 'chunk-key');
    assert.equal(calls[1].options.headers['Content-Type'].includes('application/json'), false);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('media access probe uses authenticated byte-range requests and cancels full-response bodies', async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  let canceled = 0;
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options });
    return { ok: true, status: 200, body: { cancel: async () => { canceled++; } } };
  };
  try {
    const { probeProctoringMedia } = await import('../src/features/proctoring/server-proctoring-client.ts');
    await probeProctoringMedia('session/1', 'capture 1', 'camera');
    assert.equal(calls[0].url, '/api/v1/proctoring/sessions/session%2F1/media/camera?capture_id=capture+1');
    assert.equal(calls[0].options.credentials, 'include');
    assert.equal(calls[0].options.cache, 'no-store');
    assert.equal(calls[0].options.headers.Range, 'bytes=0-0');
    assert.equal(canceled, 1);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('media access probe returns HTTP denial for protected-state cleanup', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () => ({ ok: false, status: 403, body: { cancel: async () => {} } });
  try {
    const { probeProctoringMedia } = await import('../src/features/proctoring/server-proctoring-client.ts');
    await assert.rejects(probeProctoringMedia('session-1', 'capture-1', 'screen'), (error) => error.status === 403);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('capture policy never prompts for unsupported or insecure origins', async () => {
  const { canStartServerCapture } = await import('../src/features/proctoring/server-proctoring-policy.ts');
  assert.equal(canStartServerCapture({ available: false, secureContext: true, mediaApisAvailable: true }), false);
  assert.equal(canStartServerCapture({ available: true, secureContext: false, mediaApisAvailable: true }), false);
  assert.equal(canStartServerCapture({ available: true, secureContext: true, mediaApisAvailable: false }), false);
  assert.equal(canStartServerCapture({ available: true, secureContext: true, mediaApisAvailable: true }), true);
});

test('fresh session adoption keeps its prepared capture while reload adoption clears stale tokens', async () => {
  const { preparedCaptureAfterSessionAdoption } = await import('../src/features/proctoring/server-proctoring-policy.ts');
  assert.equal(preparedCaptureAfterSessionAdoption(null, 'session-1', '', 'capture-1'), 'session-1:capture-1');
  assert.equal(preparedCaptureAfterSessionAdoption('session-1', 'session-1', 'session-1:capture-1'), 'session-1:capture-1');
  assert.equal(preparedCaptureAfterSessionAdoption('session-1', 'session-2', 'session-1:capture-1'), '');
  assert.equal(preparedCaptureAfterSessionAdoption('session-1', null, 'session-1:capture-1'), '');
});

test('real-session events retain only the minimal observation type, ID, and timestamp', async () => {
  const { toServerProctoringEvents } = await import('../src/features/proctoring/server-proctoring-client.ts');
  const input = [
    { id: 'event-1', event_type: 'tab_hidden', timestamp: '2026-10-11T12:00:00Z', metadata: { any: 'ignored' } },
    { id: 'event-2', event_type: 'gaze_deviation', timestamp: '2026-10-11T12:00:01Z', metadata: { direction: 'left' } },
    { id: 'event-3', event_type: 'paste_attempt', timestamp: '2026-10-11T12:00:02Z', metadata: { clipboard: 'private text' } },
  ];
  const result = toServerProctoringEvents(input);
  assert.deepEqual(result, [{ event_id: 'event-1', type: 'tab_hidden', occurred_at: '2026-10-11T12:00:00Z' }]);
  assert.equal(JSON.stringify(result).includes('private text'), false);
});

test('server browser events are sent in bounded versioned batches', async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  let version = 1;
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).endsWith('/auth/csrf')) return { ok: true, status: 200, json: async () => ({ data: { csrf_token: 'csrf-test' } }) };
    const body = JSON.parse(options.body);
    calls.push(body);
    version++;
    return { ok: true, status: 200, json: async () => ({ data: { session_id: 'session-1', version } }) };
  };
  try {
    const { appendAllProctoringEvents } = await import('../src/features/proctoring/server-proctoring-client.ts');
    const events = Array.from({ length: 205 }, (_, index) => ({ event_id: `event-${index}`, type: 'tab_hidden', occurred_at: '2026-10-11T12:00:00Z' }));
    const result = await appendAllProctoringEvents({ session_id: 'session-1', version: 1 }, events, () => `events-key-${calls.length}`);
    assert.deepEqual(calls.map((call) => call.events.length), [100, 100, 5]);
    assert.deepEqual(calls.map((call) => call.expected_version), [1, 2, 3]);
    assert.equal(result.version, 4);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('chunk upload queue serializes channels, retries with one key, and enforces byte caps', async () => {
  const { SerialChunkUploadQueue } = await import('../src/features/proctoring/proctoring-upload-queue.ts');
  const attempts = [];
  let active = 0;
  let maxActive = 0;
  let keyCount = 0;
  const queue = new SerialChunkUploadQueue({
    maxChunkBytes: 8,
    maxSessionBytes: 12,
    maxRetries: 1,
    createIdempotencyKey: () => `chunk-key-${++keyCount}`,
    upload: async (item, { idempotencyKey }) => {
      attempts.push({ item, idempotencyKey });
      active++;
      maxActive = Math.max(maxActive, active);
      await Promise.resolve();
      active--;
      if (item.sequence === 0 && attempts.filter((entry) => entry.item.sequence === 0).length === 1) {
        throw Object.assign(new Error('temporary'), { retryable: true });
      }
      return { capture_id: item.captureId, channel: item.channel, sequence: item.sequence, bytes: item.blob.size };
    },
  });

  const camera = queue.enqueue({ captureId: 'capture-1', channel: 'camera', sequence: 0, blob: new Blob(['123456']) });
  const screen = queue.enqueue({ captureId: 'capture-1', channel: 'screen', sequence: 0, blob: new Blob(['123456']) });
  await assert.rejects(queue.enqueue({ captureId: 'capture-1', channel: 'camera', sequence: 1, blob: new Blob(['1234567']) }), /session limit|byte limit/iu);
  const receipts = await Promise.all([camera, screen]);

  assert.equal(maxActive, 1);
  assert.deepEqual(receipts.map((receipt) => receipt.channel), ['camera', 'screen']);
  assert.deepEqual(queue.lastAcceptedSequences, { camera: 0, screen: 0 });
  assert.equal(attempts.filter((entry) => entry.item.sequence === 0).length, 3);
  assert.deepEqual(attempts.filter((entry) => entry.item.channel === 'camera').map((entry) => entry.idempotencyKey), ['chunk-key-1', 'chunk-key-1']);
  assert.deepEqual(attempts.filter((entry) => entry.item.channel === 'screen').map((entry) => entry.idempotencyKey), ['chunk-key-2']);
  queue.abort();
  await assert.rejects(queue.enqueue({ captureId: 'capture-1', channel: 'camera', sequence: 2, blob: new Blob(['x']) }), /aborted|closed/iu);
});

test('aborting a capture cancels the active upload and prevents queued chunks from starting', async () => {
  const { SerialChunkUploadQueue } = await import('../src/features/proctoring/proctoring-upload-queue.ts');
  let beginUpload;
  const uploadStarted = new Promise((resolve) => { beginUpload = resolve; });
  let calls = 0;
  const queue = new SerialChunkUploadQueue({
    maxChunkBytes: 10,
    maxSessionBytes: 20,
    upload: async (_chunk, { signal }) => {
      calls++;
      beginUpload();
      return new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true }));
    },
  });
  const first = queue.enqueue({ captureId: 'capture-1', channel: 'camera', sequence: 0, blob: new Blob(['one']) });
  const second = queue.enqueue({ captureId: 'capture-1', channel: 'screen', sequence: 0, blob: new Blob(['two']) });
  await uploadStarted;
  queue.abort();
  await assert.rejects(first);
  await assert.rejects(second);
  assert.equal(calls, 1);
});

test('server capture streams MediaRecorder chunks without retaining full Blobs', async () => {
  const originalMediaRecorder = globalThis.MediaRecorder;
  const originalWindow = globalThis.window;
  const emitted = [];
  class FakeMediaRecorder {
    static instances = [];
    state = 'inactive';
    mimeType = 'video/webm';
    listeners = new Map();
    constructor(stream, options) { this.stream = stream; this.options = options; FakeMediaRecorder.instances.push(this); }
    static isTypeSupported() { return true; }
    start() { this.state = 'recording'; }
    requestData() {}
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    emit(blob) { this.ondataavailable?.({ data: blob }); }
    stop() { this.state = 'inactive'; this.listeners.get('stop')?.(); }
  }
  globalThis.MediaRecorder = FakeMediaRecorder;
  globalThis.window = { setTimeout, clearTimeout };
  const tracks = [{ stop() {}, addEventListener() {} }, { stop() {}, addEventListener() {} }];
  const makeStream = (id) => ({ id, active: true, getTracks: () => tracks, getVideoTracks: () => tracks.slice(0, 1) });
  const chunks = [];
  const harness = componentHarness(path.resolve('tests/server-proctoring-media-harness.tsx'), 'ServerProctoringMediaHarness', {
    './proctoring-policy.ts': { isDemoProctoringEnabled: () => true },
  });
  try {
    let media = harness.render({
      attemptId: 'attempt-1', consentGranted: true, camera: makeStream('camera'), screen: makeStream('screen'),
      onChunk: (channel, blob, sequence) => chunks.push({ channel, blob, sequence }),
    });
    await media.start();
    const cameraRecorder = FakeMediaRecorder.instances.find((recorder) => recorder.stream.id === 'camera');
    const screenRecorder = FakeMediaRecorder.instances.find((recorder) => recorder.stream.id === 'screen');
    assert.equal(cameraRecorder.options.videoBitsPerSecond, 320_000);
    assert.equal(screenRecorder.options.videoBitsPerSecond, 500_000);
    cameraRecorder.emit(new Blob(['camera-frame']));
    screenRecorder.emit(new Blob(['screen-frame']));
    assert.deepEqual(chunks.map(({ channel, sequence }) => [channel, sequence]), [['camera', 0], ['screen', 0]]);
    const recorded = await media.stop();
    assert.equal(recorded.cameraBlob, null);
    assert.equal(recorded.screenBlob, null);
    assert.deepEqual(chunks.map(({ channel, sequence }) => [channel, sequence]), [['camera', 0], ['screen', 0]]);
    harness.unmount();
  } finally {
    globalThis.MediaRecorder = originalMediaRecorder;
    globalThis.window = originalWindow;
  }
});

test('server capture exposes recorder startup failure instead of appearing active', async () => {
  const originalMediaRecorder = globalThis.MediaRecorder;
  const originalWindow = globalThis.window;
  class UnsupportedRecorder {
    static isTypeSupported() { return true; }
    state = 'inactive';
    listeners = new Map();
    constructor(stream) { if (stream.id === 'screen') throw new Error('screen recorder unsupported'); }
    start() { this.state = 'recording'; }
    requestData() {}
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    stop() { this.state = 'inactive'; this.listeners.get('stop')?.(); }
  }
  globalThis.MediaRecorder = UnsupportedRecorder;
  globalThis.window = { setTimeout, clearTimeout };
  const track = { stop() {} };
  const stream = (id) => ({ id, active: true, getTracks: () => [track], getVideoTracks: () => [track] });
  const captureErrors = [];
  const harness = componentHarness(path.resolve('tests/server-proctoring-media-harness.tsx'), 'ServerProctoringMediaHarness', {
    './proctoring-policy.ts': { isDemoProctoringEnabled: () => true },
  });
  try {
    let media = harness.render({ attemptId: 'attempt-1', consentGranted: true, camera: stream('camera'), screen: stream('screen'), onChunk: () => {}, onCaptureError: (error) => captureErrors.push(error) });
    await media.start();
    media = await harness.flush();
    assert.match(media.recordingError, /Активные потоки серверного захвата недоступны/u);
    assert.equal(captureErrors.length, 1);
    assert.match(captureErrors[0], /Запись не началась/u);
    harness.unmount();
  } finally {
    globalThis.MediaRecorder = originalMediaRecorder;
    globalThis.window = originalWindow;
  }
});

test('ending a transferred media track emits only its allowed signal and stops both tracks', async () => {
  const originalMediaRecorder = globalThis.MediaRecorder;
  const originalWindow = globalThis.window;
  class FakeRecorder {
    static isTypeSupported() { return true; }
    state = 'inactive';
    listeners = new Map();
    constructor(stream) { this.stream = stream; }
    start() { this.state = 'recording'; }
    requestData() {}
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    stop() { this.state = 'inactive'; this.listeners.get('stop')?.(); }
  }
  globalThis.MediaRecorder = FakeRecorder;
  globalThis.window = { setTimeout, clearTimeout };
  const makeTrack = () => {
    const listeners = new Map();
    return { kind: 'video', readyState: 'live', stopped: 0, addEventListener: (type, fn) => listeners.set(type, fn), end: () => listeners.get('ended')?.(), stop() { this.stopped++; } };
  };
  const cameraTrack = makeTrack();
  const screenTrack = makeTrack();
  const stream = (track) => ({ active: true, getTracks: () => [track], getVideoTracks: () => [track] });
  const emitted = [];
  const failures = [];
  const harness = componentHarness(path.resolve('tests/server-proctoring-media-harness.tsx'), 'ServerProctoringMediaHarness', {
    './proctoring-policy.ts': { isDemoProctoringEnabled: () => true },
  });
  try {
    let media = harness.render({ attemptId: 'attempt-1', consentGranted: true, camera: stream(cameraTrack), screen: stream(screenTrack), onChunk: () => {}, onEvent: (type) => emitted.push(type), onCaptureError: (message) => failures.push(message) });
    await media.start();
    screenTrack.end();
    media = await harness.flush();
    assert.deepEqual(emitted, ['screen_share_stopped']);
    assert.match(failures[0], /захват экрана прерван/iu);
    assert.equal(cameraTrack.stopped, 1);
    assert.equal(screenTrack.stopped, 1);
    assert.equal(media.isRecording, false);
    harness.unmount();
  } finally {
    globalThis.MediaRecorder = originalMediaRecorder;
    globalThis.window = originalWindow;
  }
});

test('MediaRecorder errors surface as technical interruptions without inventing a browser event', async () => {
  const originalMediaRecorder = globalThis.MediaRecorder;
  const originalWindow = globalThis.window;
  class FakeRecorder {
    static instances = [];
    static isTypeSupported() { return true; }
    state = 'inactive';
    listeners = new Map();
    constructor(stream) { this.stream = stream; FakeRecorder.instances.push(this); }
    start() { this.state = 'recording'; }
    requestData() {}
    addEventListener(type, listener) { this.listeners.set(type, listener); }
    stop() { this.state = 'inactive'; this.listeners.get('stop')?.(); }
    fail() { this.listeners.get('error')?.({ error: new Error('encoder failed') }); }
  }
  globalThis.MediaRecorder = FakeRecorder;
  globalThis.window = { setTimeout, clearTimeout };
  const makeTrack = () => ({ kind: 'video', readyState: 'live', stopped: 0, addEventListener() {}, stop() { this.stopped++; } });
  const cameraTrack = makeTrack(); const screenTrack = makeTrack();
  const stream = (track) => ({ active: true, getTracks: () => [track], getVideoTracks: () => [track] });
  const emitted = []; const failures = [];
  const harness = componentHarness(path.resolve('tests/server-proctoring-media-harness.tsx'), 'ServerProctoringMediaHarness', {
    './proctoring-policy.ts': { isDemoProctoringEnabled: () => true },
  });
  try {
    const media = harness.render({ attemptId: 'attempt-1', consentGranted: true, camera: stream(cameraTrack), screen: stream(screenTrack), onChunk: () => {}, onEvent: (type) => emitted.push(type), onCaptureError: (message) => failures.push(message) });
    await media.start();
    FakeRecorder.instances[0].fail();
    await harness.flush();
    assert.deepEqual(emitted, []);
    assert.match(failures[0], /ошибка записи/iu);
    assert.equal(cameraTrack.stopped, 1);
    assert.equal(screenTrack.stopped, 1);
    harness.unmount();
  } finally {
    globalThis.MediaRecorder = originalMediaRecorder;
    globalThis.window = originalWindow;
  }
});

test('server consent gates media prompts, and session creation follows live media approval', async () => {
  const setupPath = path.resolve('src/features/proctoring/ServerProctoringSetupModal.tsx');
  const oldNavigator = globalThis.navigator;
  const order = [];
  let cameraOptions;
  let screenOptions;
  const cameraTrack = { kind: 'video', readyState: 'live', stop() {} };
  const screenTrack = { kind: 'video', readyState: 'live', stop() {}, getSettings: () => ({ displaySurface: 'browser' }) };
  const cameraStream = { getTracks: () => [cameraTrack], getVideoTracks: () => [cameraTrack] };
  const screenStream = { getTracks: () => [screenTrack], getVideoTracks: () => [screenTrack] };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    mediaDevices: {
      getUserMedia: async (options) => { order.push('camera'); cameraOptions = options; return cameraStream; },
      getDisplayMedia: async (options) => { order.push('screen'); screenOptions = options; return screenStream; },
    },
  } });
  const harness = componentHarness(setupPath, 'ServerProctoringSetupModal');
  const consent = {
    available: true,
    reason: null,
    consent_version: '1.0',
    consent_document_sha256: 'b'.repeat(64),
    consent_text: 'Запись добровольна и будет проверена оператором.',
    retention_days: 7,
    max_chunk_bytes: 1024,
    max_session_bytes: 4096,
    channels: ['camera', 'screen'],
  };
  const props = {
    open: true,
    captureAllowed: true,
    consent,
    consentGiven: false,
    onConsentChange: () => {},
    prepareSession: async () => { order.push('session'); return { session_id: 'session-1', active_capture_id: 'capture-1' }; },
    onReady: () => { order.push('ready'); },
    onCancel: () => {},
  };
  try {
    let tree = harness.render(props);
    assert.match(textContent(tree), /Запись добровольна/u);
    assert.match(textContent(tree), /b{64}/u);
    assert.deepEqual(order, []);
    assert.equal(nodes(tree, (node) => node.type === 'button' && /Согласиться и начать запись/u.test(textContent(node)))[0].props.disabled, true);

    tree = harness.render({ ...props, consentGiven: true });
    const start = nodes(tree, (node) => node.type === 'button' && /Согласиться и начать запись/u.test(textContent(node)))[0];
    await start.props.onClick();

    tree = await harness.flush();
    assert.deepEqual(order, ['camera', 'screen', 'session', 'ready'], textContent(tree));
    assert.equal(cameraOptions.audio, false);
    assert.equal(screenOptions.audio, false);
    assert.equal(screenOptions.video, true);
    harness.unmount();
  } finally {
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: oldNavigator });
  }
});

test('permission denial before creating a session preserves the ordinary no-recording path', async () => {
  const setupPath = path.resolve('src/features/proctoring/ServerProctoringSetupModal.tsx');
  const oldNavigator = globalThis.navigator;
  const order = [];
  let cancelled = 0;
  let consentChanges = 0;
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    mediaDevices: {
      getUserMedia: async () => { order.push('camera'); throw new DOMException('Permission denied', 'NotAllowedError'); },
      getDisplayMedia: async () => { order.push('screen'); throw new Error('must not request screen after camera denial'); },
    },
  } });
  const harness = componentHarness(setupPath, 'ServerProctoringSetupModal');
  const props = {
    open: true,
    captureAllowed: true,
    consent: { available: true, reason: null, consent_version: '1', consent_document_sha256: 'd'.repeat(64), consent_text: 'Запись камеры и окна.', retention_days: 7, max_chunk_bytes: 1024, max_session_bytes: 4096, channels: ['camera', 'screen'] },
    consentGiven: true,
    onConsentChange: () => { consentChanges++; },
    prepareSession: async () => { order.push('session'); return { session_id: 'session-1', active_capture_id: 'capture-1' }; },
    onReady: () => assert.fail('denied media must not become ready'),
    onCancel: () => { cancelled++; },
  };
  try {
    let tree = harness.render(props);
    const start = nodes(tree, (node) => node.type === 'button' && /Согласиться и начать запись/u.test(textContent(node)))[0];
    await start.props.onClick();
    tree = await harness.flush();
    assert.deepEqual(order, ['camera']);
    assert.doesNotMatch(textContent(tree), /сессия уже создана/u);
    const close = nodes(tree, (node) => node.type === 'button' && /Продолжить без записи/u.test(textContent(node)))[0];
    assert.ok(close);
    close.props.onClick();
    assert.equal(cancelled, 1);
    assert.equal(consentChanges, 1);
  } finally {
    harness.unmount();
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: oldNavigator });
  }
});

test('a resumed server session explains retry or explicit revoke when permissions are denied', async () => {
  const setupPath = path.resolve('src/features/proctoring/ServerProctoringSetupModal.tsx');
  const oldNavigator = globalThis.navigator;
  const order = [];
  let cancelled = 0;
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    mediaDevices: {
      getUserMedia: async () => { order.push('camera'); throw new DOMException('Permission denied', 'NotAllowedError'); },
      getDisplayMedia: async () => { order.push('screen'); throw new Error('must not request screen after camera denial'); },
    },
  } });
  const harness = componentHarness(setupPath, 'ServerProctoringSetupModal');
  const props = {
    open: true,
    captureAllowed: true,
    preparedSessionExists: true,
    consent: { available: true, reason: null, consent_version: '1', consent_document_sha256: 'd'.repeat(64), consent_text: 'Запись камеры и окна.', retention_days: 7, max_chunk_bytes: 1024, max_session_bytes: 4096, channels: ['camera', 'screen'] },
    consentGiven: true,
    onConsentChange: () => {},
    prepareSession: async () => { order.push('session'); return { session_id: 'session-1', active_capture_id: 'capture-1' }; },
    onReady: () => assert.fail('denied media must not become ready'),
    onCancel: () => { cancelled++; },
  };
  try {
    let tree = harness.render(props);
    const start = nodes(tree, (node) => node.type === 'button' && /Согласиться и начать запись/u.test(textContent(node)))[0];
    await start.props.onClick();
    tree = await harness.flush();
    assert.deepEqual(order, ['camera']);
    assert.match(textContent(tree), /сессия этой попытки уже создана/u);
    assert.match(textContent(tree), /Повторите настройку записи|повторить запись/iu);
    assert.match(textContent(tree), /отзовите согласие/u);
    assert.doesNotMatch(textContent(tree), /продолжить без записи/iu);
    const close = nodes(tree, (node) => node.type === 'button' && /Закрыть настройку/u.test(textContent(node)))[0];
    assert.ok(close);
    close.props.onClick();
    assert.equal(cancelled, 1);
  } finally {
    harness.unmount();
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: oldNavigator });
  }
});

test('a capture without acknowledged camera and screen media cannot be sealed', async () => {
  const { assertServerCaptureReceiptsComplete } = await import('../src/features/proctoring/server-proctoring-policy.ts');
  assert.throws(() => assertServerCaptureReceiptsComplete({ camera: -1, screen: -1 }), /обоих каналов/u);
  assert.throws(() => assertServerCaptureReceiptsComplete({ camera: 0, screen: -1 }), /обоих каналов/u);
  assert.doesNotThrow(() => assertServerCaptureReceiptsComplete({ camera: 0, screen: 3 }));
});

test('chunk writes are followed by a fresh session version before event append and seal', async () => {
  const originalFetch = globalThis.fetch;
  const calls = [];
  const session = (version) => ({
    session_id: 'session-1', attempt_id: 'attempt-1', version, active_capture_id: 'capture-1', status: 'recording',
    captures: [], consent_version: '1', consent_document_sha256: 'a'.repeat(64), consented_at: '2026-10-11T10:00:00Z',
    created_at: '2026-10-11T10:00:00Z', updated_at: '2026-10-11T10:00:00Z', review_due_at: null, decided_at: null,
    decision: null, decision_reason_code: null, decision_note: null, decided_by: null, attempt_status: 'in_progress',
    attempt_version: 1, consent_document_sha256: 'a'.repeat(64),
  });
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).endsWith('/auth/csrf')) return { ok: true, status: 200, json: async () => ({ data: { csrf_token: 'csrf-test' } }) };
    calls.push({ url: String(url), body: options.body ? JSON.parse(options.body) : null });
    if (options.method === 'GET') return { ok: true, status: 200, json: async () => ({ data: session(5) }) };
    const expected = JSON.parse(options.body).expected_version;
    if (String(url).endsWith('/events')) return { ok: true, status: 200, json: async () => ({ data: session(expected + 1) }) };
    if (String(url).endsWith('/seal')) return { ok: true, status: 200, json: async () => ({ data: session(expected + 1) }) };
    throw new Error(`Unexpected request ${url}`);
  };
  try {
    const { appendProctoringEvents, refreshOwnedProctoringSession, sealProctoringSession } = await import('../src/features/proctoring/server-proctoring-client.ts');
    const latest = await refreshOwnedProctoringSession('attempt-1', 'session-1');
    const afterEvents = await appendProctoringEvents(latest, [{ event_id: 'event-1', type: 'window_blur', occurred_at: '2026-10-11T10:01:00Z' }]);
    await sealProctoringSession(afterEvents, 'capture-1', { camera: 1, screen: 1 });
    const eventsCall = calls.find((call) => call.url.endsWith('/events'));
    const sealCall = calls.find((call) => call.url.endsWith('/seal'));
    assert.equal(eventsCall.body.expected_version, 5);
    assert.equal(sealCall.body.expected_version, 6);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('revocation refreshes a stale session version and retries one concurrent version conflict', async () => {
  const originalFetch = globalThis.fetch;
  const revokeBodies = [];
  let gets = 0;
  globalThis.fetch = async (url, options = {}) => {
    if (String(url).endsWith('/auth/csrf')) return { ok: true, status: 200, json: async () => ({ data: { csrf_token: 'csrf-test' } }) };
    if (options.method === 'GET') {
      gets++;
      return { ok: true, status: 200, json: async () => ({ data: { session_id: 'session-1', attempt_id: 'attempt-1', version: gets === 1 ? 8 : 9, status: 'recording' } }) };
    }
    revokeBodies.push(JSON.parse(options.body));
    if (revokeBodies.length === 1) return { ok: false, status: 409, json: async () => ({ error: { code: 'VERSION_CONFLICT', message: 'version changed' } }) };
    return { ok: true, status: 200, json: async () => ({ data: { session_id: 'session-1', attempt_id: 'attempt-1', version: 10, status: 'revoked' } }) };
  };
  try {
    const { revokeProctoringSessionFresh } = await import('../src/features/proctoring/server-proctoring-client.ts');
    const result = await revokeProctoringSessionFresh('attempt-1', { session_id: 'session-1', attempt_id: 'attempt-1', version: 2, status: 'recording' }, (latest) => `revoke-${latest.version}`);
    assert.deepEqual(revokeBodies.map((body) => body.expected_version), [8, 9]);
    assert.equal(result.version, 10);
    assert.equal(gets, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('candidate server-pending notice says score and grade remain withheld', async () => {
  const statusPath = path.resolve('src/features/proctoring/ProctoringCandidateStatus.tsx');
  const harness = componentHarness(statusPath, 'ProctoringCandidateStatus');
  const tree = harness.render({ status: 'review_pending' });
  assert.match(textContent(tree), /ожидает решения оператора/iu);
  assert.match(textContent(tree), /балл и грейд не опубликованы/iu);
  assert.doesNotMatch(textContent(tree), /Junior|Middle|\b\d+ \/ 24\b/u);
  harness.unmount();
});

test('a published terminal result still offers media revocation without implying the grade changes', async () => {
  const statusPath = path.resolve('src/features/proctoring/ProctoringCandidateStatus.tsx');
  const harness = componentHarness(statusPath, 'ProctoringCandidateStatus');
  let revokeCalls = 0;
  const tree = harness.render({ status: 'approved', publishedGrade: 'Middle', onRevoke: () => { revokeCalls++; } });
  assert.match(textContent(tree), /проверка .*завершена/iu);
  assert.match(textContent(tree), /Middle.*останется без изменений/iu);
  const revoke = nodes(tree, (node) => node.type === 'button' && /удалить запись/iu.test(textContent(node)))[0];
  assert.ok(revoke);
  revoke.props.onClick();
  assert.equal(revokeCalls, 1);
  harness.unmount();
});

test('operator decision notice remains visible after the queue refresh callback completes', async () => {
  const pagePath = path.resolve('src/features/proctoring/OperationsProctoringPage.tsx');
  let decisions = 0;
  let queueLoads = 0;
  const detail = {
    session_id: 'session-review-1', attempt_id: 'attempt-1', version: 3, attempt_version: 4,
    status: 'review_pending', provisional_result: null,
    attempt: { attempt_id: 'attempt-1', version: 4, requested_grade: 'junior', status: 'submitted', started_at: null, deadline_at: null, submitted_at: null, tasks: [] },
    recordings: [],
  };
  const harness = componentHarness(pagePath, 'OperationsProctoringPage', {
    '../../api.ts': { RequestError: class RequestError extends Error {}, errorMessage: (error) => String(error) },
    './server-proctoring-client.ts': {
      getProctoringReviewQueue: async () => {
        queueLoads++;
        return { items: queueLoads === 1 ? [{ session_id: detail.session_id, attempt_id: detail.attempt_id, version: detail.version, status: detail.status, requested_grade: 'junior', submitted_at: null }] : [], next_cursor: null };
      },
      getProctoringReview: async () => detail,
      decideProctoringReview: async () => { decisions++; return { session_id: detail.session_id, status: 'approved' }; },
      fetchProctoringMedia: async () => new Blob(),
    },
  });
  try {
    let tree = harness.render();
    await harness.flush();
    assert.equal(queueLoads, 1);
    tree = harness.render();
    assert.match(textContent(tree), /Видео и сведения о попытке доступны/u);
    assert.doesNotMatch(textContent(tree), /Видео и ответы доступны/u);
    const review = nodes(tree, (node) => node.type === 'button' && textContent(node).includes(detail.session_id))[0];
    assert.ok(review);
    review.props.onClick();
    tree = await harness.flush();
    const comment = nodes(tree, (node) => node.type === 'textarea')[0];
    assert.ok(comment);
    comment.props.onChange({ target: { value: 'Видео проверено, решение подтверждено.' } });
    tree = await harness.flush();
    const form = nodes(tree, (node) => node.type === 'form')[0];
    form.props.onSubmit({ preventDefault() {} });
    tree = await harness.flush();
    assert.equal(decisions, 1);
    assert.equal(queueLoads, 2);
    assert.match(textContent(tree), /Решение сохранено на сервере/u);
  } finally {
    harness.unmount();
  }
});

test('selecting a review row while queue refresh is pending releases the refresh spinner', async () => {
  const pagePath = path.resolve('src/features/proctoring/OperationsProctoringPage.tsx');
  const queueRefresh = deferred();
  let queueLoads = 0;
  const review = {
    session_id: 'session-loading-race', attempt_id: 'attempt-race', version: 1, attempt_version: 1, status: 'review_pending', provisional_result: null,
    attempt: { attempt_id: 'attempt-race', version: 1, requested_grade: 'junior', status: 'submitted', started_at: null, deadline_at: null, submitted_at: null, tasks: [] },
    recordings: [],
  };
  const queueItem = { session_id: review.session_id, attempt_id: review.attempt_id, version: review.version, status: review.status, requested_grade: 'junior', submitted_at: null };
  const harness = componentHarness(pagePath, 'OperationsProctoringPage', {
    '../../api.ts': { RequestError: class RequestError extends Error {}, errorMessage: (error) => String(error) },
    './server-proctoring-client.ts': {
      getProctoringReviewQueue: async () => { queueLoads++; return queueLoads === 1 ? { items: [queueItem], next_cursor: null } : queueRefresh.promise; },
      getProctoringReview: async () => review,
      probeProctoringMedia: async () => {},
      proctoringMediaUrl: () => '',
      decideProctoringReview: async () => review,
    },
  });
  try {
    let tree = harness.render();
    await harness.flush();
    tree = harness.render();
    nodes(tree, (node) => node.type === 'button' && /Обновить очередь/u.test(textContent(node)))[0].props.onClick();
    tree = await harness.flush();
    assert.equal(nodes(tree, (node) => node.type === 'button' && /Обновляем…/u.test(textContent(node)))[0].props.disabled, true);
    nodes(tree, (node) => node.type === 'button' && textContent(node).includes(review.session_id))[0].props.onClick();
    tree = await harness.flush();
    const refreshButton = nodes(tree, (node) => node.type === 'button' && /Обновить очередь|Обновляем…/u.test(textContent(node)))[0];
    assert.equal(refreshButton.props.disabled, false);
    queueRefresh.resolve({ items: [queueItem], next_cursor: null });
    tree = await harness.flush();
    assert.match(textContent(tree), /Сессия session-loading-race/iu);
  } finally {
    harness.unmount();
  }
});

test('operator decision notice is withheld when the queue refresh closes protected access', async () => {
  const pagePath = path.resolve('src/features/proctoring/OperationsProctoringPage.tsx');
  let queueLoads = 0;
  const detail = {
    session_id: 'session-review-2', attempt_id: 'attempt-2', version: 3, attempt_version: 4,
    status: 'review_pending', provisional_result: null,
    attempt: { attempt_id: 'attempt-2', version: 4, requested_grade: 'junior', status: 'submitted', started_at: null, deadline_at: null, submitted_at: null, tasks: [] },
    recordings: [],
  };
  class RequestError extends Error { constructor(status) { super('Access denied'); this.status = status; } }
  const harness = componentHarness(pagePath, 'OperationsProctoringPage', {
    '../../api.ts': { RequestError, errorMessage: (error) => String(error) },
    './server-proctoring-client.ts': {
      getProctoringReviewQueue: async () => {
        queueLoads++;
        if (queueLoads > 1) throw new RequestError(403);
        return { items: [{ session_id: detail.session_id, attempt_id: detail.attempt_id, version: detail.version, status: detail.status, requested_grade: 'junior', submitted_at: null }], next_cursor: null };
      },
      getProctoringReview: async () => detail,
      decideProctoringReview: async () => ({ session_id: detail.session_id, status: 'approved' }),
      fetchProctoringMedia: async () => new Blob(),
    },
  });
  try {
    let tree = harness.render();
    await harness.flush();
    tree = harness.render();
    nodes(tree, (node) => node.type === 'button' && textContent(node).includes(detail.session_id))[0].props.onClick();
    tree = await harness.flush();
    nodes(tree, (node) => node.type === 'textarea')[0].props.onChange({ target: { value: 'Проверка завершена.' } });
    tree = await harness.flush();
    nodes(tree, (node) => node.type === 'form')[0].props.onSubmit({ preventDefault() {} });
    tree = await harness.flush();
    assert.match(textContent(tree), /Доступ к проверке закрыт/u);
    assert.doesNotMatch(textContent(tree), /Решение сохранено на сервере/u);
  } finally {
    harness.unmount();
  }
});

test('switching channels aborts the previous media probe and ignores its late response', async () => {
  const pagePath = path.resolve('src/features/proctoring/OperationsProctoringPage.tsx');
  const cameraProbe = deferred();
  const cameraSignals = [];
  const review = {
    session_id: 'session-switch-1', attempt_id: 'attempt-1', version: 3, attempt_version: 4, status: 'review_pending', provisional_result: null,
    attempt: { attempt_id: 'attempt-1', version: 4, requested_grade: 'junior', status: 'submitted', started_at: null, deadline_at: null, submitted_at: null, tasks: [] },
    recordings: [{ capture_id: 'capture-1', status: 'sealed', started_at: null, media: { camera: { bytes: 500, chunks: 2 }, screen: { bytes: 600, chunks: 2 } } }],
  };
  const harness = componentHarness(pagePath, 'OperationsProctoringPage', {
    '../../api.ts': { RequestError: class RequestError extends Error {}, errorMessage: (error) => String(error) },
    './server-proctoring-client.ts': {
      getProctoringReviewQueue: async () => ({ items: [{ session_id: review.session_id, attempt_id: review.attempt_id, version: review.version, status: review.status, requested_grade: 'junior', submitted_at: null }], next_cursor: null }),
      getProctoringReview: async () => review,
      probeProctoringMedia: (sessionId, captureId, channel, signal) => {
        if (channel === 'camera') {
          cameraSignals.push(signal);
          return cameraSignals.length === 1 ? cameraProbe.promise : Promise.resolve();
        }
        return Promise.resolve();
      },
      proctoringMediaUrl: (sessionId, captureId, channel) => `/api/v1/proctoring/sessions/${sessionId}/media/${channel}?capture_id=${captureId}`,
      decideProctoringReview: async () => review,
    },
  });
  try {
    let tree = harness.render();
    await harness.flush();
    tree = harness.render();
    nodes(tree, (node) => node.type === 'button' && textContent(node).includes(review.session_id))[0].props.onClick();
    tree = await harness.flush();
    nodes(tree, (node) => node.type === 'button' && /Открыть камеру/u.test(textContent(node)))[0].props.onClick();
    tree = await harness.flush();
    nodes(tree, (node) => node.type === 'button' && /Открыть экран/u.test(textContent(node)))[0].props.onClick();
    tree = await harness.flush();
    assert.equal(cameraSignals[0].aborted, true);
    let video = nodes(tree, (node) => node.type === 'video')[0];
    assert.match(video.props.src, /\/media\/screen\?/u);
    const makeFakeVideo = () => ({ pauses: 0, removed: [], loads: 0, pause() { this.pauses++; }, removeAttribute(name) { this.removed.push(name); }, load() { this.loads++; } });
    const screenVideo = makeFakeVideo();
    video.props.ref.current = screenVideo;
    cameraProbe.resolve();
    tree = await harness.flush();
    video = nodes(tree, (node) => node.type === 'video')[0];
    assert.match(video.props.src, /\/media\/screen\?/u);
    nodes(tree, (node) => node.type === 'button' && /Открыть камеру/u.test(textContent(node)))[0].props.onClick();
    tree = await harness.flush();
    assert.equal(screenVideo.pauses, 1);
    assert.deepEqual(screenVideo.removed, ['src']);
    assert.equal(screenVideo.loads, 1);
    video = nodes(tree, (node) => node.type === 'video')[0];
    assert.match(video.props.src, /\/media\/camera\?/u);
    const cameraVideo = makeFakeVideo();
    video.props.ref.current = cameraVideo;
    nodes(tree, (node) => node.type === 'button' && /Обновить очередь/u.test(textContent(node)))[0].props.onClick();
    tree = await harness.flush();
    assert.equal(cameraVideo.pauses, 1);
    assert.deepEqual(cameraVideo.removed, ['src']);
    assert.equal(cameraVideo.loads, 1);
    assert.equal(nodes(tree, (node) => node.type === 'video').length, 0);
  } finally {
    harness.unmount();
  }
});

test('operator playback uses a same-origin video URL and clears detail after native HTTP denial', async () => {
  const pagePath = path.resolve('src/features/proctoring/OperationsProctoringPage.tsx');
  let blobFetches = 0;
  let probes = 0;
  class RequestError extends Error { constructor(status) { super('Access denied'); this.status = status; } }
  const review = {
    session_id: 'session-stream-1', attempt_id: 'attempt-1', version: 3, attempt_version: 4, status: 'review_pending', provisional_result: null,
    attempt: { attempt_id: 'attempt-1', version: 4, requested_grade: 'junior', status: 'submitted', started_at: null, deadline_at: null, submitted_at: null, tasks: [] },
    recordings: [{ capture_id: 'capture-1', status: 'sealed', started_at: null, media: { camera: { bytes: 500, chunks: 2 }, screen: { bytes: 0, chunks: 0 } } }],
  };
  const harness = componentHarness(pagePath, 'OperationsProctoringPage', {
    '../../api.ts': { RequestError, errorMessage: (error) => String(error) },
    './server-proctoring-client.ts': {
      getProctoringReviewQueue: async () => ({ items: [{ session_id: review.session_id, attempt_id: review.attempt_id, version: review.version, status: review.status, requested_grade: 'junior', submitted_at: null }], next_cursor: null }),
      getProctoringReview: async () => review,
      probeProctoringMedia: async () => { probes++; if (probes > 1) throw new RequestError(403); },
      proctoringMediaUrl: (sessionId, captureId, channel) => `/api/v1/proctoring/sessions/${sessionId}/media/${channel}?capture_id=${captureId}`,
      fetchProctoringMedia: async () => { blobFetches++; return new Blob(); },
      decideProctoringReview: async () => review,
    },
  });
  try {
    let tree = harness.render();
    await harness.flush();
    tree = harness.render();
    nodes(tree, (node) => node.type === 'button' && textContent(node).includes(review.session_id))[0].props.onClick();
    tree = await harness.flush();
    nodes(tree, (node) => node.type === 'button' && /Открыть камеру/u.test(textContent(node)))[0].props.onClick();
    tree = await harness.flush();
    let video = nodes(tree, (node) => node.type === 'video')[0];
    assert.ok(video);
    assert.equal(video.props.src, '/api/v1/proctoring/sessions/session-stream-1/media/camera?capture_id=capture-1');
    assert.equal(blobFetches, 0);
    assert.equal(probes, 1);
    const nativeVideo = { pauses: 0, removed: [], loads: 0, pause() { this.pauses++; }, removeAttribute(name) { this.removed.push(name); }, load() { this.loads++; } };
    video.props.ref.current = nativeVideo;
    video.props.onError();
    tree = await harness.flush();
    assert.equal(probes, 2);
    assert.equal(nativeVideo.pauses, 1);
    assert.deepEqual(nativeVideo.removed, ['src']);
    assert.equal(nativeVideo.loads, 1);
    assert.equal(nodes(tree, (node) => node.type === 'video').length, 0);
    assert.match(textContent(tree), /Доступ к записи закрыт/u);
    assert.equal(blobFetches, 0);
  } finally {
    harness.unmount();
  }
});

test('server capture rejects whole-monitor sharing and stops the rejected screen track', async () => {
  const setupPath = path.resolve('src/features/proctoring/ServerProctoringSetupModal.tsx');
  const oldNavigator = globalThis.navigator;
  let screenStopped = 0;
  let ready = false;
  const order = [];
  const screenTrack = { kind: 'video', readyState: 'live', stop() { screenStopped++; order.push('screen-stopped'); }, getSettings: () => ({ displaySurface: 'monitor' }) };
  const cameraTrack = { kind: 'video', readyState: 'live', stop() { order.push('camera-stopped'); } };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {
    mediaDevices: {
      getUserMedia: async () => { order.push('camera'); return { getTracks: () => [cameraTrack], getVideoTracks: () => [cameraTrack] }; },
      getDisplayMedia: async () => { order.push('screen'); return { getTracks: () => [screenTrack], getVideoTracks: () => [screenTrack] }; },
    },
  } });
  const harness = componentHarness(setupPath, 'ServerProctoringSetupModal');
  const props = {
    open: true,
    captureAllowed: true,
    consent: { available: true, reason: null, consent_version: '1', consent_document_sha256: 'c'.repeat(64), consent_text: 'Согласие', retention_days: 7, max_chunk_bytes: 1024, max_session_bytes: 4096, channels: ['camera', 'screen'] },
    consentGiven: true,
    onConsentChange: () => {},
    prepareSession: async () => { order.push('session'); return { session_id: 'session-1', active_capture_id: 'capture-1' }; },
    onReady: () => { ready = true; },
    onCancel: () => {},
  };
  try {
    const tree = harness.render(props);
    const start = nodes(tree, (node) => node.type === 'button' && /Согласиться и начать запись/u.test(textContent(node)))[0];
    await start.props.onClick();
    const result = await harness.flush();
    assert.equal(ready, false);
    assert.equal(screenStopped, 1);
    assert.deepEqual(order, ['camera', 'screen', 'camera-stopped', 'screen-stopped']);
    assert.match(textContent(result), /выберите окно браузера или приложения/u);
  } finally {
    harness.unmount();
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: oldNavigator });
  }
});
