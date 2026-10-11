import assert from 'node:assert/strict';
import test from 'node:test';
import path from 'node:path';
import { componentHarness } from './helpers/component-harness.mjs';
import { nodes, textContent } from './helpers/component-harness.mjs';
import { loadConfigFromFile } from 'vite';

const harnessPath = path.resolve('tests/proctoring-media-harness.tsx');
const setupPath = path.resolve('src/features/proctoring/ProctoringSetupModal.tsx');

test('proctoring data and captured media stay in ephemeral memory and clear by identity', async () => {
  const {
    buildProctoringReport,
    clearDemoProctoringData,
    loadManagerReviewQueue,
    loadProctoringVideoBlob,
    loadStoredProctoringReport,
    saveProctoringVideoBlob,
  } = await import('../src/features/proctoring/proctoring-storage.ts');
  const oldFetch = globalThis.fetch;
  const oldStorage = globalThis.localStorage;
  const oldIndexedDb = globalThis.indexedDB;
  let fetchCalls = 0;
  let storageCalls = 0;
  let indexedDbCalls = 0;
  globalThis.fetch = async () => { fetchCalls++; return { ok: false }; };
  globalThis.localStorage = {
    getItem() { storageCalls++; return null; },
    setItem() { storageCalls++; },
    removeItem() { storageCalls++; },
  };
  Object.defineProperty(globalThis, 'indexedDB', {
    configurable: true,
    value: { open() { indexedDbCalls++; throw new Error('IndexedDB must stay unused'); } },
  });

  try {
    clearDemoProctoringData();
    const blob = new Blob(['local demo'], { type: 'video/webm' });
    await saveProctoringVideoBlob('attempt-private', blob);
    assert.equal(await loadProctoringVideoBlob('attempt-private'), blob);
    const report = await buildProctoringReport({
      sessionId: 'session-private',
      attemptId: 'attempt-private',
      candidateName: 'Private Candidate',
      startedAt: new Date(0).toISOString(),
      completedAt: new Date(1000).toISOString(),
      events: [],
      videoBlob: blob,
    });
    assert.equal(report.demo, true);
    loadManagerReviewQueue();
    assert.equal(fetchCalls, 0);
    assert.equal(storageCalls, 0);
    assert.equal(indexedDbCalls, 0);

    clearDemoProctoringData();
    assert.equal(loadStoredProctoringReport('attempt-private'), null);
    assert.equal(await loadProctoringVideoBlob('attempt-private'), null);
    assert.ok(loadManagerReviewQueue().every((item) => item.demo === true));
  } finally {
    clearDemoProctoringData();
    globalThis.fetch = oldFetch;
    globalThis.localStorage = oldStorage;
    Object.defineProperty(globalThis, 'indexedDB', { configurable: true, value: oldIndexedDb });
  }
});

test('stopping recording, revoking consent, changing attempt, and unmounting stop every media track', async () => {
  const oldMediaRecorder = globalThis.MediaRecorder;
  const oldWindow = globalThis.window;
  class FakeMediaRecorder {
    state = 'inactive';
    mimeType = 'video/webm';
    listeners = new Map();
    static isTypeSupported() { return true; }
    start() { this.state = 'recording'; }
    requestData() {}
    addEventListener(type, handler) { this.listeners.set(type, handler); }
    stop() {
      this.state = 'inactive';
      this.listeners.get('stop')?.();
    }
  }
  globalThis.MediaRecorder = FakeMediaRecorder;
  globalThis.window = { setTimeout, clearTimeout };
  const tracks = { camera: [0, 1].map(() => ({ stopCalls: 0, stop() { this.stopCalls++; } })), screen: [0, 1].map(() => ({ stopCalls: 0, stop() { this.stopCalls++; } })) };
  const stream = (kind) => ({ active: true, getTracks: () => tracks[kind], getVideoTracks: () => tracks[kind].slice(0, 1) });
  const camera = stream('camera');
  const screen = stream('screen');
  try {
    const harness = componentHarness(harnessPath, 'ProctoringMediaHarness', {
      './proctoring-policy.ts': { isDemoProctoringEnabled: () => true },
    });
    let props = { attemptId: 'attempt-one', consentGranted: true, camera, screen };
    let actions = harness.render(props);
    await actions.start();
    await actions.stop();
    assert.deepEqual([...tracks.camera, ...tracks.screen].map((track) => track.stopCalls), [1, 1, 1, 1]);

    const nextTracks = { camera: [0, 1].map(() => ({ stopCalls: 0, stop() { this.stopCalls++; } })), screen: [0, 1].map(() => ({ stopCalls: 0, stop() { this.stopCalls++; } })) };
    const nextStream = (kind) => ({ active: true, getTracks: () => nextTracks[kind], getVideoTracks: () => nextTracks[kind].slice(0, 1) });
    props = { attemptId: 'attempt-two', consentGranted: true, camera: nextStream('camera'), screen: nextStream('screen'), attachStreams: false };
    harness.render(props);
    assert.deepEqual([...tracks.camera, ...tracks.screen].map((track) => track.stopCalls), [1, 1, 1, 1]);
    props = { ...props, attachStreams: true };
    actions = harness.render(props);
    await actions.start();
    harness.render({ ...props, consentGranted: false });
    assert.deepEqual([...nextTracks.camera, ...nextTracks.screen].map((track) => track.stopCalls), [1, 1, 1, 1]);
    harness.unmount();
  } finally {
    globalThis.MediaRecorder = oldMediaRecorder;
    globalThis.window = oldWindow;
  }
});

test('demo media prompts are disclosed and disabled until explicit opt-in checkbox and click', async () => {
  const oldNavigator = globalThis.navigator;
  let cameraRequests = 0;
  const cameraTrack = { stop() {} };
  const cameraStream = { getTracks: () => [cameraTrack], getVideoTracks: () => [cameraTrack] };
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { mediaDevices: { getUserMedia: async () => { cameraRequests++; return cameraStream; }, getDisplayMedia: async () => cameraStream } },
  });
  let harness;
  try {
    harness = componentHarness(setupPath, 'ProctoringSetupModal', {
      'react-router': { useNavigate: () => () => {} },
      './proctoring-policy.ts': {
        isDemoProctoringEnabled: () => true,
        canOfferDemoCapture: () => true,
      },
    });
    let tree = harness.render({ open: true, attemptDemo: true, processingAccepted: true, onReady() {} });
    assert.match(textContent(tree), /локальная DEMO-функция/i);
    let cameraButton = nodes(tree, (node) => node.type === 'button' && /Выбрать камеру/.test(textContent(node)))[0];
    assert.equal(cameraButton.props.disabled, true);
    assert.equal(cameraRequests, 0);

    nodes(tree, (node) => node.type === 'input' && node.props.type === 'checkbox')[0]
      .props.onChange({ target: { checked: true } });
    tree = await harness.flush();
    cameraButton = nodes(tree, (node) => node.type === 'button' && /Выбрать камеру/.test(textContent(node)))[0];
    await cameraButton.props.onClick();
    assert.equal(cameraRequests, 1);
    harness.unmount();
  } finally {
    harness?.unmount();
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: oldNavigator });
  }
});

test('Vite keeps real API proxying by default and requires explicit loopback offline demo opt-in', async () => {
  const configPath = path.resolve('vite.config.ts');
  const previous = process.env.FSP_OFFLINE_DEMO;
  try {
    delete process.env.FSP_OFFLINE_DEMO;
    const normal = await loadConfigFromFile({ command: 'serve', mode: 'development' }, configPath);
    assert.equal(normal?.config.server?.proxy?.['/api'], 'http://localhost:8000');
    assert.ok(!(normal?.config.plugins ?? []).some((plugin) => plugin.name === 'fsp-offline-demo'));

    process.env.FSP_OFFLINE_DEMO = '1';
    const demo = await loadConfigFromFile({ command: 'serve', mode: 'development' }, configPath);
    assert.ok((demo?.config.plugins ?? []).some((plugin) => plugin.name === 'fsp-offline-demo'));
    const { offlineMockPlugin } = await import('../vite-mock-plugin.ts');
    const plugin = offlineMockPlugin();
    assert.equal(plugin.apply, 'serve');
    assert.throws(() => plugin.configResolved({ server: { host: '0.0.0.0' } }), /loopback/u);
  } finally {
    if (previous === undefined) delete process.env.FSP_OFFLINE_DEMO;
    else process.env.FSP_OFFLINE_DEMO = previous;
  }
});
