import { useEffect, useRef, useState } from 'react';
import { canOfferDemoCapture } from './proctoring-policy.ts';
import './proctoring.css';

interface ProctoringSetupModalProps {
  open: boolean;
  attemptDemo?: boolean;
  processingAccepted?: boolean;
  onReady: (media: { cameraStream: MediaStream; screenStream: MediaStream }) => void;
  onCancel?: () => void;
}

function stopStreams(streams: Array<MediaStream | null>): void {
  const stopped = new Set<MediaStreamTrack>();
  streams.forEach((stream) => stream?.getTracks().forEach((track) => {
    if (stopped.has(track)) return;
    stopped.add(track);
    try { track.stop(); } catch { /* track already ended */ }
  }));
}

export function ProctoringSetupModal({
  open,
  attemptDemo = false,
  processingAccepted = false,
  onReady,
  onCancel,
}: ProctoringSetupModalProps) {
  const [cameraStream, setCameraStream] = useState<MediaStream | null>(null);
  const [screenStream, setScreenStream] = useState<MediaStream | null>(null);
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isRequesting, setIsRequesting] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const cameraRef = useRef<MediaStream | null>(null);
  const screenRef = useRef<MediaStream | null>(null);
  const openRef = useRef(open);
  const consentRef = useRef(consent);
  const transferredRef = useRef(false);
  const requestGeneration = useRef(0);
  openRef.current = open;
  consentRef.current = consent;

  const mediaApisAvailable = typeof navigator !== 'undefined'
    && typeof navigator.mediaDevices?.getUserMedia === 'function'
    && typeof navigator.mediaDevices?.getDisplayMedia === 'function';
  const captureAvailable = canOfferDemoCapture({
    isDemoAttempt: attemptDemo,
    processingAccepted,
    secureContext: typeof window !== 'undefined' && window.isSecureContext,
    mediaApisAvailable,
  });

  const releaseStreams = () => {
    requestGeneration.current++;
    setIsRequesting(false);
    stopStreams([cameraRef.current, screenRef.current]);
    cameraRef.current = null;
    screenRef.current = null;
    setCameraStream(null);
    setScreenStream(null);
  };

  useEffect(() => {
    if (open) {
      transferredRef.current = false;
      return;
    }
    if (!transferredRef.current) releaseStreams();
  }, [open]);

  useEffect(() => {
    if (cameraStream && videoRef.current) {
      videoRef.current.srcObject = cameraStream;
      videoRef.current.play().catch(() => {});
    }
  }, [cameraStream]);

  useEffect(() => () => {
    if (!transferredRef.current) stopStreams([cameraRef.current, screenRef.current]);
  }, []);

  const handleConsentChange = (checked: boolean) => {
    setConsent(checked);
    consentRef.current = checked;
    if (!checked) releaseStreams();
  };

  const handleRequestCamera = async () => {
    if (!captureAvailable || !consentRef.current) return;
    setError(null);
    setIsRequesting(true);
    const generation = ++requestGeneration.current;
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' },
        audio: false,
      });
      if (generation !== requestGeneration.current || !openRef.current || !consentRef.current) {
        stopStreams([stream]);
        return;
      }
      stopStreams([cameraRef.current]);
      cameraRef.current = stream;
      setCameraStream(stream);
    } catch {
      if (generation === requestGeneration.current) setError('Не удалось открыть камеру для локальной демонстрации.');
    } finally {
      if (generation === requestGeneration.current) setIsRequesting(false);
    }
  };

  const handleRequestScreen = async () => {
    if (!captureAvailable || !consentRef.current) return;
    setError(null);
    setIsRequesting(true);
    const generation = ++requestGeneration.current;
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { displaySurface: 'monitor' } as MediaTrackConstraints,
        audio: false,
      });
      if (generation !== requestGeneration.current || !openRef.current || !consentRef.current) {
        stopStreams([stream]);
        return;
      }
      stopStreams([screenRef.current]);
      screenRef.current = stream;
      setScreenStream(stream);
    } catch {
      if (generation === requestGeneration.current) setError('Не удалось открыть экран для локальной демонстрации.');
    } finally {
      if (generation === requestGeneration.current) setIsRequesting(false);
    }
  };

  const handleStart = () => {
    if (!captureAvailable || !consent || !cameraRef.current || !screenRef.current) return;
    const media = { cameraStream: cameraRef.current, screenStream: screenRef.current };
    transferredRef.current = true;
    cameraRef.current = null;
    screenRef.current = null;
    setCameraStream(null);
    setScreenStream(null);
    onReady(media);
  };

  const handleCancel = () => {
    transferredRef.current = false;
    releaseStreams();
    setConsent(false);
    if (onCancel) onCancel();
  };

  if (!open) return null;
  const canProceed = Boolean(captureAvailable && consent && cameraStream && screenStream);

  return (
    <div className="proctoring-setup-modal-backdrop" role="dialog" aria-modal="true" style={{ zIndex: 9999 }}>
      <div className="proctoring-setup-card">
        <div className="proctoring-setup-header">
          <span className="eyebrow">ЛОКАЛЬНАЯ DEMO-ФУНКЦИЯ</span>
          <h2>Необязательная демонстрация захвата медиа</h2>
          <p className="field-hint">
            Это тестовый режим только для demo-попытки. Камера и экран считываются в этой вкладке и остаются только в памяти до выхода. Данные не отправляются на сервер и не сохраняются между входами. Экспериментальный RGB-сигнал не подтверждает лицо, взгляд или нарушение и не влияет на оценку, грейд или доступ к пересдаче.
          </p>
        </div>

        {!captureAvailable && (
          <div className="notice notice-info" role="status">
            DEMO-захват недоступен: нужны включённая локальная demo-сборка, demo-попытка, принятое согласие на обработку и защищённый контекст с API камеры.
          </div>
        )}
        {error && <div className="notice notice-danger" role="alert">{error}</div>}

        <div className="proctoring-preview-box">
          {cameraStream ? (
            <video ref={videoRef} className="proctoring-preview-video" autoPlay muted playsInline />
          ) : (
            <div className="proctoring-preview-placeholder">Камера не включена.</div>
          )}
        </div>

        <div className="proctoring-checks-grid">
          <div className="proctoring-check-item">
            <div className="proctoring-check-info"><span>📷</span><div><strong>Камера · DEMO</strong><small>Не сохраняется и не передаётся</small></div></div>
            {cameraStream ? <span className="proctoring-check-status-badge ok">Подключена локально</span> : (
              <button type="button" className="button button-secondary button-small" onClick={handleRequestCamera} disabled={!captureAvailable || !consent || isRequesting}>Выбрать камеру</button>
            )}
          </div>
          <div className="proctoring-check-item">
            <div className="proctoring-check-info"><span>🖥️</span><div><strong>Экран · DEMO</strong><small>Не сохраняется и не передаётся</small></div></div>
            {screenStream ? <span className="proctoring-check-status-badge ok">Подключён локально</span> : (
              <button type="button" className="button button-secondary button-small" onClick={handleRequestScreen} disabled={!captureAvailable || !consent || isRequesting}>Выбрать экран</button>
            )}
          </div>
        </div>

        <div className="proctoring-consent-box">
          <label>
            <input type="checkbox" checked={consent} disabled={!captureAvailable} onChange={(event) => handleConsentChange(event.target.checked)} />
            <span>Я добровольно разрешаю разовый захват камеры и выбранного экрана для локальной demo-сессии. Захват прекращается при отказе, смене попытки и выходе.</span>
          </label>
        </div>

        <div className="submit-bar" style={{ marginTop: '0.5rem' }}>
          <button type="button" className="button button-secondary" onClick={handleCancel}>Отказаться</button>
          <button type="button" className="button button-primary" disabled={!canProceed || isRequesting} onClick={handleStart}>Продолжить demo →</button>
        </div>
      </div>
    </div>
  );
}
