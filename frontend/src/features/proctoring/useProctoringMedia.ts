import { useCallback, useEffect, useRef, useState } from 'react';
import type { ProctoringEventType } from './proctoring-types';
import { isDemoProctoringEnabled } from './proctoring-policy.ts';

interface UseProctoringMediaOptions {
  onEvent?: (type: ProctoringEventType, meta?: Record<string, string | number | boolean>) => void;
  onChunk?: (channel: 'camera' | 'screen', chunk: Blob, sequence: number) => void;
  onCaptureError?: (message: string) => void;
  captureMode?: 'demo' | 'server';
  chunkIntervalMs?: number;
  attemptId?: string;
  consentGranted?: boolean;
}

export interface ProctoringRecordedMedia {
  cameraBlob: Blob | null;
  screenBlob: Blob | null;
}

function stopTracks(streams: Array<MediaStream | null>): void {
  const stopped = new Set<MediaStreamTrack>();
  streams.forEach((stream) => stream?.getTracks().forEach((track) => {
    if (stopped.has(track)) return;
    stopped.add(track);
    try { track.stop(); } catch { /* tracks may already be ended */ }
  }));
}

function startStreamRecorder(
  stream: MediaStream,
  chunksRef: { current: Blob[] },
  intervalMs: number,
  channel: 'camera' | 'screen',
  captureMode: 'demo' | 'server',
  onChunk?: (channel: 'camera' | 'screen', chunk: Blob, sequence: number) => void,
): MediaRecorder | null {
  chunksRef.current = [];
  if (typeof MediaRecorder === 'undefined') return null;
  const webmCandidates = [
    'video/webm;codecs=vp8,opus',
    'video/webm;codecs=vp9,opus',
    'video/webm;codecs=vp8',
    'video/webm;codecs=vp9',
    'video/webm',
  ];
  const candidates = captureMode === 'server' ? webmCandidates : [...webmCandidates, 'video/mp4'];
  let recorder: MediaRecorder | null = null;
  for (const mime of candidates) {
    if (!MediaRecorder.isTypeSupported(mime)) continue;
    try {
      const options: MediaRecorderOptions = captureMode === 'server'
        ? { mimeType: mime, videoBitsPerSecond: channel === 'camera' ? 320_000 : 500_000 }
        : { mimeType: mime };
      recorder = new MediaRecorder(stream, options);
      break;
    } catch { /* try the next supported container */ }
  }
  if (!recorder) {
    if (captureMode === 'server') return null;
    try { recorder = new MediaRecorder(stream); } catch (error) {
      console.error('Could not create the local demo recorder:', error);
      return null;
    }
  }
  let sequence = 0;
  recorder.ondataavailable = (event) => {
    if (!event.data?.size) return;
    if (captureMode === 'server' && onChunk) {
      onChunk(channel, event.data, sequence++);
    } else if (captureMode === 'demo') {
      chunksRef.current.push(event.data);
    }
  };
  try {
    recorder.start(intervalMs);
    return recorder;
  } catch (error) {
    console.error('Could not start the local demo recorder:', error);
    return null;
  }
}

export function useProctoringMedia({
  onEvent,
  onChunk,
  onCaptureError,
  captureMode = 'demo',
  chunkIntervalMs = 2000,
  attemptId,
  consentGranted = false,
}: UseProctoringMediaOptions = {}) {
  const [cameraStream, setCameraStreamState] = useState<MediaStream | null>(null);
  const [screenStream, setScreenStreamState] = useState<MediaStream | null>(null);
  const [isRecording, setIsRecording] = useState(false);
  const [recordingError, setRecordingError] = useState<string | null>(null);
  const [recordedVideoBlob, setRecordedVideoBlob] = useState<Blob | null>(null);
  const [recordedScreenBlob, setRecordedScreenBlob] = useState<Blob | null>(null);

  const cameraStreamRef = useRef<MediaStream | null>(null);
  const screenStreamRef = useRef<MediaStream | null>(null);
  const cameraRecorderRef = useRef<MediaRecorder | null>(null);
  const screenRecorderRef = useRef<MediaRecorder | null>(null);
  const cameraChunksRef = useRef<Blob[]>([]);
  const screenChunksRef = useRef<Blob[]>([]);
  const onEventRef = useRef(onEvent);
  const onChunkRef = useRef(onChunk);
  const onCaptureErrorRef = useRef(onCaptureError);
  const captureModeRef = useRef(captureMode);
  const consentRef = useRef(consentGranted);
  const attemptIdRef = useRef(attemptId);
  onEventRef.current = onEvent;
  onChunkRef.current = onChunk;
  onCaptureErrorRef.current = onCaptureError;
  captureModeRef.current = captureMode;
  consentRef.current = consentGranted;

  const setCameraStream = useCallback((stream: MediaStream | null) => {
    cameraStreamRef.current = stream;
    setCameraStreamState(stream);
  }, []);

  const setScreenStream = useCallback((stream: MediaStream | null) => {
    screenStreamRef.current = stream;
    setScreenStreamState(stream);
  }, []);

  const stopRecording = useCallback((): Promise<ProctoringRecordedMedia> => new Promise((resolve) => {
    const cameraRecorder = cameraRecorderRef.current;
    const screenRecorder = screenRecorderRef.current;
    let cameraDone = !cameraRecorder || cameraRecorder.state === 'inactive';
    let screenDone = !screenRecorder || screenRecorder.state === 'inactive';
    let finished = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;

    const finish = () => {
      if (finished) return;
      finished = true;
      if (timeout !== undefined) clearTimeout(timeout);
      stopTracks([cameraStreamRef.current, screenStreamRef.current]);
      cameraStreamRef.current = null;
      screenStreamRef.current = null;
      cameraRecorderRef.current = null;
      screenRecorderRef.current = null;
      setCameraStreamState(null);
      setScreenStreamState(null);
      setIsRecording(false);
      const cameraBlob = cameraChunksRef.current.length
        ? new Blob(cameraChunksRef.current, { type: cameraRecorder?.mimeType || 'video/webm' })
        : null;
      const screenBlob = screenChunksRef.current.length
        ? new Blob(screenChunksRef.current, { type: screenRecorder?.mimeType || 'video/webm' })
        : null;
      cameraChunksRef.current = [];
      screenChunksRef.current = [];
      setRecordedVideoBlob(cameraBlob);
      setRecordedScreenBlob(screenBlob);
      resolve({ cameraBlob, screenBlob });
    };
    const checkStopped = () => { if (cameraDone && screenDone) finish(); };
    const stopRecorder = (recorder: MediaRecorder | null, markDone: () => void) => {
      if (!recorder || recorder.state === 'inactive') {
        markDone();
        return;
      }
      recorder.addEventListener('stop', () => { markDone(); checkStopped(); }, { once: true });
      try {
        if (typeof recorder.requestData === 'function') recorder.requestData();
        recorder.stop();
      } catch {
        markDone();
      }
    };
    if (cameraDone && screenDone) {
      finish();
      return;
    }
    timeout = setTimeout(finish, 1200);
    stopRecorder(cameraRecorder, () => { cameraDone = true; });
    stopRecorder(screenRecorder, () => { screenDone = true; });
    checkStopped();
  }), []);

  const requestCamera = useCallback(async (): Promise<boolean> => {
    if ((captureMode === 'demo' && !isDemoProctoringEnabled()) || !consentRef.current) return false;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' },
        audio: false,
      });
      if (!consentRef.current) {
        stopTracks([stream]);
        return false;
      }
      stream.getVideoTracks().forEach((track) => {
        track.onended = () => onEventRef.current?.('camera_disabled', { demoSignal: 'camera_track_ended' });
      });
      setCameraStream(stream);
      return true;
    } catch {
      setRecordingError('Не удалось открыть камеру для локальной демонстрации.');
      return false;
    }
  }, [captureMode, setCameraStream]);

  const requestScreen = useCallback(async (): Promise<boolean> => {
    if ((captureMode === 'demo' && !isDemoProctoringEnabled()) || !consentRef.current) return false;
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { displaySurface: 'monitor' } as MediaTrackConstraints,
        audio: false,
      });
      if (!consentRef.current) {
        stopTracks([stream]);
        return false;
      }
      stream.getVideoTracks().forEach((track) => {
        track.onended = () => onEventRef.current?.('screen_share_stopped', { demoSignal: 'screen_track_ended' });
      });
      setScreenStream(stream);
      return true;
    } catch {
      setRecordingError('Не удалось открыть захват экрана для локальной демонстрации.');
      return false;
    }
  }, [captureMode, setScreenStream]);

  const startRecording = useCallback((activeCamera?: MediaStream | null, activeScreen?: MediaStream | null) => {
    const mode = captureModeRef.current;
    if ((mode === 'demo' && !isDemoProctoringEnabled()) || !consentRef.current || (mode === 'server' && !onChunkRef.current)) {
      const message = mode === 'demo' ? 'Локальная demo-съёмка отключена или согласие отозвано.' : 'Серверный захват недоступен или согласие отозвано.';
      setRecordingError(message);
      if (mode === 'server') onCaptureErrorRef.current?.(`Запись не началась: ${message}`);
      return;
    }
    const camera = activeCamera || cameraStreamRef.current;
    const screen = activeScreen || screenStreamRef.current;
    if (activeCamera) setCameraStream(activeCamera);
    if (activeScreen) setScreenStream(activeScreen);
    setRecordedVideoBlob(null);
    setRecordedScreenBlob(null);
    cameraChunksRef.current = [];
    screenChunksRef.current = [];
    const cameraRecorder = camera?.active ? startStreamRecorder(camera, cameraChunksRef, chunkIntervalMs, 'camera', mode, onChunkRef.current) : null;
    const screenRecorder = screen?.active ? startStreamRecorder(screen, screenChunksRef, chunkIntervalMs, 'screen', mode, onChunkRef.current) : null;
    cameraRecorderRef.current = cameraRecorder;
    screenRecorderRef.current = screenRecorder;
    const recorderReady = mode === 'server' ? Boolean(cameraRecorder && screenRecorder) : Boolean(cameraRecorder || screenRecorder);
    if (recorderReady) {
      setIsRecording(true);
      setRecordingError(null);
      if (mode === 'server') {
        let interruptionReported = false;
        const interrupt = (message: string, eventType?: 'camera_disabled' | 'screen_share_stopped') => {
          if (interruptionReported) return;
          interruptionReported = true;
          if (eventType) onEventRef.current?.(eventType);
          setRecordingError(message);
          onCaptureErrorRef.current?.(`Запись прервана: ${message}`);
          void stopRecording();
        };
        camera?.getVideoTracks().forEach((track) => track.addEventListener('ended', () => {
          interrupt('Захват камеры прерван.', 'camera_disabled');
        }, { once: true }));
        screen?.getVideoTracks().forEach((track) => track.addEventListener('ended', () => {
          interrupt('Захват экрана прерван.', 'screen_share_stopped');
        }, { once: true }));
        cameraRecorder?.addEventListener('error', () => interrupt('Ошибка записи видео с камеры.'));
        screenRecorder?.addEventListener('error', () => interrupt('Ошибка записи видео с экрана.'));
      }
    } else {
      const message = mode === 'demo' ? 'Активные потоки локальной demo-съёмки недоступны.' : 'Активные потоки серверного захвата недоступны.';
      setRecordingError(message);
      void stopRecording();
      if (mode === 'server') onCaptureErrorRef.current?.(`Запись не началась: ${message}`);
    }
  }, [captureMode, chunkIntervalMs, setCameraStream, setScreenStream, stopRecording]);

  useEffect(() => {
    const attemptChanged = attemptIdRef.current !== attemptId;
    attemptIdRef.current = attemptId;
    if (attemptChanged || !consentGranted || (captureMode === 'demo' && !isDemoProctoringEnabled())) void stopRecording();
  }, [attemptId, captureMode, consentGranted, stopRecording]);

  useEffect(() => () => { void stopRecording(); }, [stopRecording]);

  return {
    cameraStream,
    setCameraStream,
    screenStream,
    setScreenStream,
    isRecording,
    recordingError,
    recordedVideoBlob,
    recordedScreenBlob,
    requestCamera,
    requestScreen,
    startRecording,
    stopRecording,
  };
}
