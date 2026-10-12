import { useEffect, useRef } from 'react';
import './proctoring.css';

interface ProctoringOverlayProps {
  cameraStream: MediaStream | null;
  isRecording: boolean;
  warning?: string | null;
}

export function ProctoringOverlay({ cameraStream, isRecording, warning }: ProctoringOverlayProps) {
  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    if (cameraStream && videoRef.current) {
      videoRef.current.srcObject = cameraStream;
      videoRef.current.play().catch(() => {});
    }
  }, [cameraStream]);

  if (!cameraStream) return null;

  return (
    <aside className="proctoring-floating-overlay" aria-label="Локальная DEMO-запись">
      <div className="proctoring-overlay-bar">
        <span>DEMO · локально</span>
        {isRecording && (
          <span className="proctoring-rec-indicator">
            <span className="proctoring-rec-dot" /> DEMO REC
          </span>
        )}
      </div>

      <video ref={videoRef} className="proctoring-overlay-cam" autoPlay muted playsInline />

      {warning && (
        <div className="proctoring-overlay-alert" role="alert">
          ⚠ {warning}
        </div>
      )}
      <small>Не отправляется, не сохраняется между входами и не влияет на результат.</small>
    </aside>
  );
}
