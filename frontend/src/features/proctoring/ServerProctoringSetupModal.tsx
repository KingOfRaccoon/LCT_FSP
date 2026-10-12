import { useEffect, useRef, useState } from 'react';
import type { ServerProctoringConfig } from './server-proctoring-policy.ts';
import './proctoring.css';

interface PreparedSession {
  session_id: string;
  active_capture_id: string | null;
}

interface ServerProctoringSetupModalProps {
  open: boolean;
  captureAllowed: boolean;
  preparedSessionExists?: boolean;
  consent: ServerProctoringConfig;
  consentGiven: boolean;
  onConsentChange: (accepted: boolean) => void;
  prepareSession: () => Promise<PreparedSession>;
  onReady: (session: PreparedSession, media: { cameraStream: MediaStream; screenStream: MediaStream }) => void;
  onCancel: () => void;
}

function stopStreams(streams: Array<MediaStream | null>): void {
  const stopped = new Set<MediaStreamTrack>();
  streams.forEach((stream) => stream?.getTracks().forEach((track) => {
    if (stopped.has(track)) return;
    stopped.add(track);
    try { track.stop(); } catch { /* track may already be ended */ }
  }));
}

function assertLiveVideoTrack(stream: MediaStream, label: string): void {
  if (!stream.getVideoTracks().some((track) => track.kind === 'video' && track.readyState === 'live')) {
    throw new Error(`Поток ${label} не содержит активную видеодорожку.`);
  }
}

export function ServerProctoringSetupModal({
  open,
  captureAllowed,
  preparedSessionExists = false,
  consent,
  consentGiven,
  onConsentChange,
  prepareSession,
  onReady,
  onCancel,
}: ServerProctoringSetupModalProps) {
  const [isStarting, setIsStarting] = useState(false);
  const [error, setError] = useState('');
  const [sessionPrepared, setSessionPrepared] = useState(preparedSessionExists);
  const cameraRef = useRef<MediaStream | null>(null);
  const screenRef = useRef<MediaStream | null>(null);
  const openRef = useRef(open);
  const consentRef = useRef(consentGiven);
  const transferredRef = useRef(false);
  openRef.current = open;
  consentRef.current = consentGiven;

  useEffect(() => {
    if (!open || !consentGiven) releaseStreams();
  }, [open, consentGiven]);
  useEffect(() => { if (preparedSessionExists) setSessionPrepared(true); }, [preparedSessionExists]);
  useEffect(() => () => { openRef.current = false; if (!transferredRef.current) releaseStreams(); }, []);

  const releaseStreams = () => {
    stopStreams([cameraRef.current, screenRef.current]);
    cameraRef.current = null;
    screenRef.current = null;
  };

  const start = async () => {
    if (!open || !captureAllowed || !consentGiven || isStarting) return;
    setError(''); setIsStarting(true);
    let camera: MediaStream | null = null;
    let screen: MediaStream | null = null;
    try {
      camera = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: 'user' }, audio: false });
      cameraRef.current = camera;
      assertLiveVideoTrack(camera, 'камеры');
      if (!openRef.current || !consentRef.current) throw new Error('Согласие на запись отозвано.');
      // The browser controls the picker; request no system audio and accept only an explicitly
      // selected browser tab/window because the consent copy is scoped to that surface.
      screen = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: false });
      screenRef.current = screen;
      const displaySurface = screen.getVideoTracks()[0]?.getSettings?.().displaySurface;
      if (displaySurface !== 'browser' && displaySurface !== 'window') {
        throw new Error('Для этой записи выберите окно браузера или приложения, а не весь монитор.');
      }
      assertLiveVideoTrack(screen, 'экрана');
      if (!openRef.current || !consentRef.current) throw new Error('Согласие на запись отозвано.');
      const session = await prepareSession();
      setSessionPrepared(true);
      if (!session.active_capture_id) throw new Error('Сервер не создал активный фрагмент записи.');
      if (!openRef.current || !consentRef.current) throw new Error('Согласие на запись отозвано.');
      assertLiveVideoTrack(camera, 'камеры');
      assertLiveVideoTrack(screen, 'экрана');
      transferredRef.current = true;
      cameraRef.current = null; screenRef.current = null;
      onReady(session, { cameraStream: camera, screenStream: screen });
    } catch (reason) {
      stopStreams([camera, screen, cameraRef.current, screenRef.current]);
      cameraRef.current = null; screenRef.current = null;
      setError(reason instanceof Error ? reason.message : 'Не удалось начать серверную запись.');
    } finally {
      setIsStarting(false);
    }
  };

  const cancel = () => {
    transferredRef.current = false;
    releaseStreams();
    onConsentChange(false);
    onCancel();
  };

  if (!open) return null;
  return <div className="proctoring-setup-modal-backdrop" role="presentation">
    <section className="proctoring-setup-card" role="dialog" aria-modal="true" aria-labelledby="server-proctoring-title">
      <div className="proctoring-setup-header">
        <span className="eyebrow">НЕОБЯЗАТЕЛЬНАЯ ЗАПИСЬ · ПРОВЕРКА ОПЕРАТОРОМ</span>
        <h2 id="server-proctoring-title">Запись для проверки этой попытки</h2>
        <p>{consent.consent_text}</p>
      </div>
      <dl className="proctoring-server-consent-facts">
        <div><dt>Версия согласия</dt><dd>{consent.consent_version}</dd></div>
        <div><dt>SHA-256 документа</dt><dd><code>{consent.consent_document_sha256}</code></dd></div>
        <div><dt>Хранение медиа</dt><dd>До {consent.retention_days} дней</dd></div>
      </dl>
      {!captureAllowed && <div className="notice notice-warm" role="status">Запись сейчас недоступна. Можно продолжить обычную оценку без камеры и экрана. {consent.reason}</div>}
      {sessionPrepared && <div className="notice notice-warm" role="status">Серверная сессия этой попытки уже создана. Для отправки этой попытки необходимо завершить запись камеры и выбранной вкладки или окна. Повторите настройку записи; если продолжить запись невозможно, закройте это окно и явно отзовите согласие на странице — текущая попытка будет аннулирована.</div>}
      {error && <div className="notice notice-danger" role="alert">{error}</div>}
      <div className="proctoring-consent-box">
        <label>
          <input type="checkbox" checked={consentGiven} disabled={!captureAllowed || isStarting} onChange={(event) => onConsentChange(event.target.checked)} />
          <span>Я добровольно соглашаюсь на запись камеры и выбранной вкладки браузера или окна приложения без микрофона и системного аудио для проверки этой попытки согласно указанной версии документа.</span>
        </label>
      </div>
      <p className="field-hint">Сигналы браузера не подтверждают нарушение. Решение и результат оценки принимает сервер после проверки уполномоченным оператором.</p>
      <div className="submit-bar" style={{ marginTop: '0.5rem' }}>
        <button type="button" className="button button-secondary" onClick={cancel} disabled={isStarting}>{sessionPrepared ? 'Закрыть настройку' : 'Продолжить без записи'}</button>
        <button type="button" className="button button-primary" disabled={!captureAllowed || !consentGiven || isStarting} onClick={() => void start()}>{isStarting ? 'Запускаем…' : 'Согласиться и начать запись'}</button>
      </div>
    </section>
  </div>;
}
