import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import './proctoring.css';

interface ProctoringSetupModalProps {
  open: boolean;
  onReady: (media: { cameraStream: MediaStream; screenStream: MediaStream }) => void;
  onCancel?: () => void;
}

export function ProctoringSetupModal({ open, onReady, onCancel }: ProctoringSetupModalProps) {
  const navigate = useNavigate();
  const [cameraStream, setCameraStream] = useState<MediaStream | null>(null);
  const [screenStream, setScreenStream] = useState<MediaStream | null>(null);
  const [consent, setConsent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isRequesting, setIsRequesting] = useState(false);
  const [multiMonitorDetected, setMultiMonitorDetected] = useState(false);

  const videoRef = useRef<HTMLVideoElement | null>(null);

  useEffect(() => {
    const checkScreens = () => {
      try {
        const isExtended = Boolean((window.screen as any)?.isExtended);
        const virtualSpan = window.screen.availWidth > window.screen.width * 1.4;
        setMultiMonitorDetected(isExtended || virtualSpan);
      } catch {
        // ignore
      }
    };
    checkScreens();
    const interval = setInterval(checkScreens, 2500);
    return () => clearInterval(interval);
  }, []);

  useEffect(() => {
    if (cameraStream && videoRef.current) {
      videoRef.current.srcObject = cameraStream;
      videoRef.current.play().catch(() => {});
    }
  }, [cameraStream]);

  // Clean up streams if user refuses and exits
  const handleRefuseAndExit = () => {
    cameraStream?.getTracks().forEach((t) => t.stop());
    screenStream?.getTracks().forEach((t) => t.stop());
    if (onCancel) {
      onCancel();
    } else {
      navigate('/assessment');
    }
  };

  const handleRequestCamera = async () => {
    setError(null);
    setIsRequesting(true);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' },
        audio: true,
      });
      setCameraStream(stream);
    } catch (err) {
      setError(
        err instanceof Error
          ? `Ошибка доступа к камере: ${err.message}`
          : 'Не удалось получить доступ к веб-камере. Без камеры прохождение теста запрещено.'
      );
    } finally {
      setIsRequesting(false);
    }
  };

  const handleRequestScreen = async () => {
    setError(null);
    setIsRequesting(true);
    try {
      const stream = await navigator.mediaDevices.getDisplayMedia({
        video: { displaySurface: 'monitor' } as MediaTrackConstraints,
        audio: false,
      });
      setScreenStream(stream);
    } catch (err) {
      setError(
        err instanceof Error
          ? `Ошибка захвата экрана: ${err.message}`
          : 'Не удалось включить захват экрана. Без демонстрации экрана прохождение теста запрещено.'
      );
    } finally {
      setIsRequesting(false);
    }
  };

  const handleStart = () => {
    if (cameraStream && screenStream && consent) {
      onReady({ cameraStream, screenStream });
    }
  };

  if (!open) return null;

  const canProceed = Boolean(cameraStream && screenStream && consent);

  return (
    <div className="proctoring-setup-modal-backdrop" role="dialog" aria-modal="true" style={{ zIndex: 9999 }}>
      <div className="proctoring-setup-card">
        <div className="proctoring-setup-header">
          <span className="eyebrow" style={{ color: '#ec1d35' }}>
            СТРОГИЙ КОНТРОЛЬ ЧЕСТНОСТИ
          </span>
          <h2>Обязательная проверка оборудования</h2>
          <p className="field-hint" style={{ color: '#1b1c21', fontWeight: 500 }}>
            В соответствии с регламентом платформы, прохождение зачетного экзамена возможно <strong>только</strong> при одновременном включении веб-камеры, захвата экрана и подписании согласия. Без любого из этих условий допуск к экзамену блокируется.
          </p>
        </div>

        {error && <div className="notice notice-danger">{error}</div>}

        <div className="proctoring-preview-box">
          {cameraStream ? (
            <video ref={videoRef} className="proctoring-preview-video" autoPlay muted playsInline />
          ) : (
            <div className="proctoring-preview-placeholder">
              📷 Камера не подключена. Нажмите «Разрешить камеру» ниже.
            </div>
          )}
        </div>

        <div className="proctoring-checks-grid">
          <div className="proctoring-check-item">
            <div className="proctoring-check-info">
              <span>📷</span>
              <div>
                <strong>1. Веб-камера и микрофон (Обязательно)</strong>
                <small>Непрерывный видеоконтроль присутствия и взгляда</small>
              </div>
            </div>
            {cameraStream ? (
              <span className="proctoring-check-status-badge ok">✓ Камера активна</span>
            ) : (
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={handleRequestCamera}
                disabled={isRequesting}
              >
                Разрешить камеру
              </button>
            )}
          </div>

          <div className="proctoring-check-item">
            <div className="proctoring-check-info">
              <span>🖥️</span>
              <div>
                <strong>2. Захват экрана (Обязательно)</strong>
                <small>Фиксация всего рабочего стола на время попытки</small>
              </div>
            </div>
            {screenStream ? (
              <span className="proctoring-check-status-badge ok">✓ Экран подключен</span>
            ) : (
              <button
                type="button"
                className="button button-secondary button-small"
                onClick={handleRequestScreen}
                disabled={isRequesting}
              >
                Выбрать экран
              </button>
            )}
          </div>

          <div className="proctoring-check-item">
            <div className="proctoring-check-info">
              <span>📺</span>
              <div>
                <strong>3. Контроль внешних дисплеев (Anti-Cheat)</strong>
                <small>
                  {multiMonitorDetected
                    ? 'Обнаружен 2-й экран! Рекомендуется отключить (Win + P).'
                    : 'Один монитор — регламент соблюден'}
                </small>
              </div>
            </div>
            {multiMonitorDetected ? (
              <span className="proctoring-check-status-badge" style={{ background: '#fef2f2', color: '#991b1b', border: '1px solid #fecaca' }}>
                ⚠️ 2 монитора
              </span>
            ) : (
              <span className="proctoring-check-status-badge ok">✓ 1 монитор (ОК)</span>
            )}
          </div>
        </div>

        <div className="proctoring-consent-box">
          <label>
            <input
              type="checkbox"
              checked={consent}
              onChange={(e) => setConsent(e.target.checked)}
            />
            <span>
              <strong>3. Я подтверждаю согласие</strong> на обработку персональных данных, непрерывную видео- и аудиозапись попытки, захват экрана, анализ направления взгляда и передачу протокола на экспертную проверку менеджером при фиксации нарушений.
            </span>
          </label>
        </div>

        {!canProceed && (
          <p style={{ margin: 0, fontSize: '0.85rem', color: '#b76e00', background: '#fff4e5', padding: '0.6rem 0.8rem', borderRadius: '6px' }}>
            ⚠ Для допуска к вопросам необходимо выполнить все 3 пункта выше.
          </p>
        )}

        <div className="submit-bar" style={{ marginTop: '0.5rem' }}>
          <button
            type="button"
            className="button button-secondary"
            onClick={handleRefuseAndExit}
          >
            Отказаться и выйти из теста
          </button>
          <button
            type="button"
            className="button button-primary"
            disabled={!canProceed}
            onClick={handleStart}
          >
            Подтвердить условия и открыть тест →
          </button>
        </div>
      </div>
    </div>
  );
}
