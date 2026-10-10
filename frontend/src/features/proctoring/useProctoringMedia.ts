import { useCallback, useEffect, useRef, useState } from 'react';
import type { ProctoringEventType } from './proctoring-types';

interface UseProctoringMediaOptions {
  onEvent?: (type: ProctoringEventType, meta?: Record<string, string | number | boolean>) => void;
  chunkIntervalMs?: number;
}

export interface ProctoringRecordedMedia {
  cameraBlob: Blob | null;
  screenBlob: Blob | null;
}

function startStreamRecorder(
  stream: MediaStream,
  chunksRef: { current: Blob[] },
  intervalMs: number
): MediaRecorder | null {
  chunksRef.current = [];
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
    if (typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(mime)) {
      try {
        recorder = new MediaRecorder(stream, { mimeType: mime });
        break;
      } catch {
        // try next
      }
    }
  }
  if (!recorder) {
    try {
      recorder = new MediaRecorder(stream);
    } catch (err) {
      console.error('Failed to create MediaRecorder:', err);
      return null;
    }
  }

  recorder.ondataavailable = (event) => {
    if (event.data && event.data.size > 0) {
      chunksRef.current.push(event.data);
    }
  };

  try {
    recorder.start(intervalMs);
    return recorder;
  } catch (err) {
    console.error('Failed to start MediaRecorder:', err);
    return null;
  }
}

export function useProctoringMedia({ onEvent, chunkIntervalMs = 1000 }: UseProctoringMediaOptions = {}) {
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
  onEventRef.current = onEvent;

  const setCameraStream = useCallback((stream: MediaStream | null) => {
    cameraStreamRef.current = stream;
    setCameraStreamState(stream);
  }, []);

  const setScreenStream = useCallback((stream: MediaStream | null) => {
    screenStreamRef.current = stream;
    setScreenStreamState(stream);
  }, []);

  // Request camera and microphone
  const requestCamera = useCallback(async (): Promise<boolean> => {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' },
        audio: true,
      });

      stream.getVideoTracks().forEach((track) => {
        track.onended = () => {
          onEventRef.current?.('camera_disabled', { reason: 'track_ended' });
        };
      });

      setCameraStream(stream);
      return true;
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Не удалось получить доступ к веб-камере';
      setRecordingError(msg);
      return false;
    }
  }, [setCameraStream]);

  // Request screen sharing
  const requestScreen = useCallback(async (): Promise<boolean> => {
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { displaySurface: 'monitor' } as MediaTrackConstraints,
        audio: false,
      });

      stream.getVideoTracks().forEach((track) => {
        track.onended = () => {
          onEventRef.current?.('screen_share_stopped', { reason: 'user_stopped_share' });
        };
      });

      setScreenStream(stream);
      return true;
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Не удалось включить запись экрана';
      setRecordingError(msg);
      return false;
    }
  }, [setScreenStream]);

  // Start recording both streams
  const startRecording = useCallback((activeCam?: MediaStream | null, activeScr?: MediaStream | null) => {
    const cam = activeCam || cameraStreamRef.current;
    const scr = activeScr || screenStreamRef.current;

    if (activeCam) setCameraStream(activeCam);
    if (activeScr) setScreenStream(activeScr);

    setRecordedVideoBlob(null);
    setRecordedScreenBlob(null);

    let camStarted = false;
    let scrStarted = false;

    // 1. Start Camera recorder
    if (cam && cam.active) {
      const recorder = startStreamRecorder(cam, cameraChunksRef, chunkIntervalMs);
      if (recorder) {
        cameraRecorderRef.current = recorder;
        camStarted = true;
      }
    }

    // 2. Start Screen recorder
    if (scr && scr.active) {
      const recorder = startStreamRecorder(scr, screenChunksRef, chunkIntervalMs);
      if (recorder) {
        screenRecorderRef.current = recorder;
        scrStarted = true;
      }
    }

    if (camStarted || scrStarted) {
      setIsRecording(true);
      setRecordingError(null);
    } else {
      setRecordingError('Потоки камеры или экрана не активны');
    }
  }, [chunkIntervalMs, setCameraStream, setScreenStream]);

  // Stop recording both streams asynchronously
  const stopRecording = useCallback((): Promise<ProctoringRecordedMedia> => {
    return new Promise((resolve) => {
      const camRecorder = cameraRecorderRef.current;
      const scrRecorder = screenRecorderRef.current;

      let camDone = !camRecorder || camRecorder.state === 'inactive';
      let scrDone = !scrRecorder || scrRecorder.state === 'inactive';

      let resolved = false;
      let timeoutId: number | null = null;

      const finish = () => {
        if (resolved) return;
        resolved = true;
        if (timeoutId !== null) window.clearTimeout(timeoutId);
        setIsRecording(false);

        const camMime = camRecorder?.mimeType || 'video/webm';
        const scrMime = scrRecorder?.mimeType || 'video/webm';

        const cameraBlob = cameraChunksRef.current.length > 0
          ? new Blob(cameraChunksRef.current, { type: camMime })
          : null;
        const screenBlob = screenChunksRef.current.length > 0
          ? new Blob(screenChunksRef.current, { type: scrMime })
          : null;

        setRecordedVideoBlob(cameraBlob);
        setRecordedScreenBlob(screenBlob);

        resolve({ cameraBlob, screenBlob });
      };

      const checkFinished = () => {
        if (camDone && scrDone) {
          finish();
        }
      };

      // Safety timeout: never hang the submit button for more than 1.5 seconds
      timeoutId = window.setTimeout(() => {
        finish();
      }, 1500);

      if (camRecorder && camRecorder.state !== 'inactive') {
        camRecorder.addEventListener('stop', () => {
          camDone = true;
          checkFinished();
        }, { once: true });
        try {
          if (typeof camRecorder.requestData === 'function') camRecorder.requestData();
          camRecorder.stop();
        } catch {
          camDone = true;
          checkFinished();
        }
      } else {
        camDone = true;
      }

      if (scrRecorder && scrRecorder.state !== 'inactive') {
        scrRecorder.addEventListener('stop', () => {
          scrDone = true;
          checkFinished();
        }, { once: true });
        try {
          if (typeof scrRecorder.requestData === 'function') scrRecorder.requestData();
          scrRecorder.stop();
        } catch {
          scrDone = true;
          checkFinished();
        }
      } else {
        scrDone = true;
      }

      checkFinished();
    });
  }, []);

  // Stop and release all streams ONLY on unmount
  useEffect(() => {
    return () => {
      if (cameraStreamRef.current) {
        cameraStreamRef.current.getTracks().forEach((t) => t.stop());
      }
      if (screenStreamRef.current) {
        screenStreamRef.current.getTracks().forEach((t) => t.stop());
      }
      if (cameraRecorderRef.current && cameraRecorderRef.current.state !== 'inactive') {
        try { cameraRecorderRef.current.stop(); } catch { /* ignore */ }
      }
      if (screenRecorderRef.current && screenRecorderRef.current.state !== 'inactive') {
        try { screenRecorderRef.current.stop(); } catch { /* ignore */ }
      }
    };
  }, []);

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
