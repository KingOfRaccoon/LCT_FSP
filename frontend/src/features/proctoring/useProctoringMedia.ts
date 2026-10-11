import { useCallback, useEffect, useRef, useState } from 'react';
import type { ProctoringEventType } from './proctoring-types';
import { isDemoProctoringEnabled } from './proctoring-policy.ts';

interface UseProctoringMediaOptions {
  onEvent?: (type: ProctoringEventType, meta?: Record<string, string | number | boolean>) => void;
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

function startStreamRecorder(stream: MediaStream, chunksRef: { current: Blob[] }, intervalMs: number): MediaRecorder | null {
  chunksRef.current = [];
  if (typeof MediaRecorder === 'undefined') return null;
  const candidates = [
    'video/webm;codecs=vp8,opus',
    'video/webm;codecs=vp9,opus',
    'video/webm;codecs=vp8',
    'video/webm;codecs=vp9',
    'video/webm',
    'video/mp4',
  ];
  let recorder: MediaRecorder | null = null;
  for (const mime of candidates) {
    if (!MediaRecorder.isTypeSupported(mime)) continue;
    try {
      recorder = new MediaRecorder(stream, { mimeType: mime });
      break;
    } catch { /* try the next supported container */ }
  }
  if (!recorder) {
    try { recorder = new MediaRecorder(stream); } catch (error) {
      console.error('Could not create the local demo recorder:', error);
      return null;
    }
  }
  recorder.ondataavailable = (event) => {
    if (event.data?.size) chunksRef.current.push(event.data);
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
  const consentRef = useRef(consentGranted);
  const attemptIdRef = useRef(attemptId);
  onEventRef.current = onEvent;
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
    if (!isDemoProctoringEnabled() || !consentRef.current) return false;
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
  }, [setCameraStream]);

  const requestScreen = useCallback(async (): Promise<boolean> => {
    if (!isDemoProctoringEnabled() || !consentRef.current) return false;
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
  }, [setScreenStream]);

  const startRecording = useCallback((activeCamera?: MediaStream | null, activeScreen?: MediaStream | null) => {
    if (!isDemoProctoringEnabled() || !consentRef.current) {
      setRecordingError('Локальная demo-съёмка отключена или согласие отозвано.');
      return;
    }
    const camera = activeCamera || cameraStreamRef.current;
    const screen = activeScreen || screenStreamRef.current;
    if (activeCamera) setCameraStream(activeCamera);
    if (activeScreen) setScreenStream(activeScreen);
    setRecordedVideoBlob(null);
    setRecordedScreenBlob(null);
    const cameraRecorder = camera?.active ? startStreamRecorder(camera, cameraChunksRef, chunkIntervalMs) : null;
    const screenRecorder = screen?.active ? startStreamRecorder(screen, screenChunksRef, chunkIntervalMs) : null;
    cameraRecorderRef.current = cameraRecorder;
    screenRecorderRef.current = screenRecorder;
    if (cameraRecorder || screenRecorder) {
      setIsRecording(true);
      setRecordingError(null);
    } else {
      setRecordingError('Активные потоки локальной demo-съёмки недоступны.');
      void stopRecording();
    }
  }, [chunkIntervalMs, setCameraStream, setScreenStream, stopRecording]);

  useEffect(() => {
    const attemptChanged = attemptIdRef.current !== attemptId;
    attemptIdRef.current = attemptId;
    if (attemptChanged || !consentGranted || !isDemoProctoringEnabled()) void stopRecording();
  }, [attemptId, consentGranted, stopRecording]);

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
