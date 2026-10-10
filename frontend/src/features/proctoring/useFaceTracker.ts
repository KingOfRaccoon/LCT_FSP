import { useEffect, useRef, useState } from 'react';
import type { ProctoringEventType } from './proctoring-types.ts';

interface UseFaceTrackerOptions {
  stream: MediaStream | null;
  enabled: boolean;
  checkIntervalMs?: number;
  onEvent?: (type: ProctoringEventType, meta?: Record<string, string | number | boolean>) => void;
}

export function useFaceTracker({
  stream,
  enabled,
  checkIntervalMs = 2000,
  onEvent,
}: UseFaceTrackerOptions) {
  const [faceDetected, setFaceDetected] = useState<boolean>(true);
  const [faceWarning, setFaceWarning] = useState<string | null>(null);

  const videoRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const missingCountRef = useRef(0);
  const gazeDeviationCountRef = useRef(0);
  const prevFrameLumaRef = useRef<number | null>(null);
  const onEventRef = useRef(onEvent);
  onEventRef.current = onEvent;

  useEffect(() => {
    if (!enabled || !stream) {
      setFaceDetected(true);
      setFaceWarning(null);
      return;
    }

    if (!videoRef.current) {
      videoRef.current = document.createElement('video');
      videoRef.current.muted = true;
      videoRef.current.playsInline = true;
    }

    if (!canvasRef.current) {
      canvasRef.current = document.createElement('canvas');
      canvasRef.current.width = 160;
      canvasRef.current.height = 120;
    }

    const video = videoRef.current;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });

    video.srcObject = stream;
    video.play().catch(() => {});

    const intervalId = window.setInterval(() => {
      if (!ctx || video.readyState < 2) return;

      try {
        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const frameData = ctx.getImageData(0, 0, canvas.width, canvas.height);
        const data = frameData.data;

        let skinPixels = 0;
        let weightedX = 0;
        let weightedY = 0;
        let totalLuma = 0;
        const totalPixels = data.length / 4;

        for (let i = 0; i < data.length; i += 4) {
          const r = data[i];
          const g = data[i + 1];
          const b = data[i + 2];
          totalLuma += (r * 299 + g * 587 + b * 114) / 1000;

          // Skin tone heuristic in RGB space
          const isSkin =
            r > 60 &&
            g > 40 &&
            b > 20 &&
            r > g &&
            r > b &&
            Math.abs(r - g) > 10 &&
            r - b > 15;

          if (isSkin) {
            skinPixels++;
            const pixelIndex = i / 4;
            const x = pixelIndex % canvas.width;
            const y = Math.floor(pixelIndex / canvas.width);
            weightedX += x;
            weightedY += y;
          }
        }

        const skinRatio = skinPixels / totalPixels;
        const currentLuma = totalLuma / totalPixels;

        // 1. Face presence check
        const hasFace = skinRatio > 0.04;
        const hasMultiple = skinRatio > 0.68;

        if (!hasFace) {
          missingCountRef.current += 1;
          gazeDeviationCountRef.current = 0;
          if (missingCountRef.current >= 2) {
            setFaceDetected(false);
            setFaceWarning('Лицо не обнаружено в кадре! Пожалуйста, оставайтесь перед камерой.');
            onEventRef.current?.('face_missing', {
              consecutiveFails: missingCountRef.current,
              skinRatio: Math.round(skinRatio * 100),
            });
          }
          return;
        }

        if (hasMultiple) {
          setFaceWarning('Обнаружен посторонний человек или объект в кадре!');
          onEventRef.current?.('multiple_faces', {
            skinRatio: Math.round(skinRatio * 100),
          });
          return;
        }

        // Face is present, reset missing counter
        missingCountRef.current = 0;
        setFaceDetected(true);

        // 2. Gaze Tracking & Head Pose Analysis (центр тяжести лица и направление взгляда)
        const centerX = weightedX / skinPixels;
        const centerY = weightedY / skinPixels;

        // Normalized relative to canvas center (-1.0 .. +1.0)
        const normOffsetX = (centerX - canvas.width / 2) / (canvas.width / 2);
        const normOffsetY = (centerY - canvas.height / 2) / (canvas.height / 2);

        // Порог: отвод взгляда в сторону (на второй экран/телефон) или вниз (взгляд под стол)
        const isLookingAway = Math.abs(normOffsetX) > 0.42 || normOffsetY > 0.38;

        if (isLookingAway) {
          gazeDeviationCountRef.current += 1;
          if (gazeDeviationCountRef.current >= 2) {
            const direction =
              normOffsetX > 0.42 ? 'вправо' : normOffsetX < -0.42 ? 'влево' : 'вниз';
            setFaceWarning(`Внимание: ваш взгляд отведен ${direction}! Смотрите на экран теста.`);
            onEventRef.current?.('gaze_deviation', {
              direction,
              offsetX: Math.round(normOffsetX * 100),
              offsetY: Math.round(normOffsetY * 100),
              duration_seconds: gazeDeviationCountRef.current * 2,
            });
          }
        } else {
          gazeDeviationCountRef.current = 0;
          setFaceWarning(null);
        }

        prevFrameLumaRef.current = currentLuma;
      } catch {
        // Fallback
      }
    }, checkIntervalMs);

    return () => {
      window.clearInterval(intervalId);
      if (video) {
        video.srcObject = null;
      }
    };
  }, [enabled, stream, checkIntervalMs]);

  return {
    faceDetected,
    faceWarning,
  };
}
