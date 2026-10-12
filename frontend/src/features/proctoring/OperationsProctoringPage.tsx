import { useCallback, useEffect, useRef, useState } from 'react';
import { RequestError, errorMessage } from '../../api.ts';
import {
  decideProctoringReview,
  getProctoringReview,
  getProctoringReviewQueue,
  probeProctoringMedia,
  proctoringMediaUrl,
  type ProctoringReviewDTO,
  type ProctoringReviewListItem,
  type ServerProctoringStatus,
} from './server-proctoring-client.ts';
import type { ProctoringChannel } from './proctoring-upload-queue.ts';
import './proctoring.css';

type Outcome = 'uphold' | 'invalidate' | 'technical';
type ReasonCode = 'approved' | 'unauthorized_assistance' | 'identity_mismatch' | 'recording_incomplete' | 'technical_failure';
type PendingDecision = { fingerprint: string; idempotencyKey: string };
type MediaSelection = { sessionId: string; captureId: string; channel: ProctoringChannel; request: number };

const statusLabel: Record<ServerProctoringStatus, string> = {
  recording: 'Записывается', sealed: 'Запись завершена', review_pending: 'Ожидает проверки', approved: 'Проверена',
  invalidated: 'Аннулирована оператором', technical_failure: 'Техническая ошибка', revoked: 'Согласие отозвано', expired: 'Срок хранения истёк',
};
const outcomeReasons: Record<Outcome, Array<{ value: ReasonCode; label: string }>> = {
  uphold: [{ value: 'approved', label: 'Нарушение не установлено' }],
  invalidate: [{ value: 'unauthorized_assistance', label: 'Нужна дополнительная проверка' }, { value: 'identity_mismatch', label: 'Невозможно подтвердить целостность попытки' }],
  technical: [{ value: 'recording_incomplete', label: 'Запись неполная' }, { value: 'technical_failure', label: 'Технический сбой' }],
};

export function OperationsProctoringPage() {
  const [queue, setQueue] = useState<ProctoringReviewListItem[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [detail, setDetail] = useState<ProctoringReviewDTO | null>(null);
  const [outcome, setOutcome] = useState<Outcome>('uphold');
  const [reasonCode, setReasonCode] = useState<ReasonCode>('approved');
  const [note, setNote] = useState('');
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [mediaUrl, setMediaUrl] = useState('');
  const [mediaLabel, setMediaLabel] = useState('');
  const [mediaSelection, setMediaSelection] = useState<MediaSelection | null>(null);
  const generation = useRef(0);
  const mediaAbort = useRef<AbortController | null>(null);
  const activeMediaSelection = useRef<MediaSelection | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const pendingDecision = useRef<PendingDecision | undefined>(undefined);

  const stopMediaPlayback = useCallback(() => {
    mediaAbort.current?.abort(); mediaAbort.current = null;
    activeMediaSelection.current = null;
    const video = videoRef.current;
    if (video) {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }
    setMediaUrl(''); setMediaLabel(''); setMediaSelection(null);
  }, []);

  const clearProtectedState = useCallback(() => {
    generation.current++;
    stopMediaPlayback();
    setQueue([]); setSelectedId(''); setDetail(null); setNote('');
    setLoading(false); setSaving(false); pendingDecision.current = undefined;
  }, [stopMediaPlayback]);

  const loadQueue = useCallback(async (): Promise<boolean> => {
    const request = ++generation.current;
    stopMediaPlayback();
    setLoading(true); setError(''); setNotice(''); setDetail(null);
    try {
      const result = await getProctoringReviewQueue(50);
      if (request !== generation.current) return false;
      setQueue(result.items); setSelectedId((current) => result.items.some((item) => item.session_id === current) ? current : '');
      return true;
    } catch (reason) {
      if (request !== generation.current) return false;
      if (reason instanceof RequestError && [401, 403, 404].includes(reason.status)) {
        clearProtectedState(); setError('Доступ к проверке закрыт. Чувствительные данные очищены.');
        return false;
      }
      setError(errorMessage(reason));
      return true;
    } finally { if (request === generation.current) setLoading(false); }
  }, [clearProtectedState, stopMediaPlayback]);

  const loadDetail = useCallback(async (sessionId: string) => {
    const request = ++generation.current;
    stopMediaPlayback();
    setLoading(false); setDetail(null); setSelectedId(sessionId); setError(''); setNote('');
    try {
      const result = await getProctoringReview(sessionId);
      if (request !== generation.current) return;
      setDetail(result);
    } catch (reason) {
      if (request !== generation.current) return;
      if (reason instanceof RequestError && [401, 403, 404].includes(reason.status)) {
        clearProtectedState(); setError('Запись больше недоступна для этой учётной записи.');
      } else setError(errorMessage(reason));
    }
  }, [clearProtectedState, stopMediaPlayback]);

  useEffect(() => { void loadQueue(); return () => { clearProtectedState(); }; }, [loadQueue, clearProtectedState]);

  const openMedia = async (captureId: string, channel: ProctoringChannel) => {
    if (!detail) return;
    stopMediaPlayback(); setError('');
    const controller = new AbortController(); mediaAbort.current = controller;
    const request = generation.current;
    const selection: MediaSelection = { sessionId: detail.session_id, captureId, channel, request };
    setMediaLabel(`${channel === 'camera' ? 'Камера' : 'Экран'} · ${captureId}`);
    try {
      await probeProctoringMedia(detail.session_id, captureId, channel, controller.signal);
      if (request !== generation.current || controller.signal.aborted) return;
      activeMediaSelection.current = selection;
      setMediaSelection(selection);
      setMediaUrl(proctoringMediaUrl(detail.session_id, captureId, channel));
    } catch (reason) {
      if (controller.signal.aborted || request !== generation.current) return;
      if (reason instanceof RequestError && [401, 403, 404].includes(reason.status)) {
        clearProtectedState(); setError('Доступ к записи закрыт. Медиа и сведения проверки очищены.');
      } else setError(errorMessage(reason));
    } finally { if (mediaAbort.current === controller) mediaAbort.current = null; }
  };

  const handleMediaError = async (selection: MediaSelection) => {
    if (activeMediaSelection.current !== selection || selection.request !== generation.current) return;
    const controller = new AbortController();
    mediaAbort.current?.abort(); mediaAbort.current = controller;
    try {
      await probeProctoringMedia(selection.sessionId, selection.captureId, selection.channel, controller.signal);
      if (controller.signal.aborted || activeMediaSelection.current !== selection || selection.request !== generation.current) return;
      setError('Не удалось воспроизвести серверную запись. Попробуйте открыть файл ещё раз.');
    } catch (reason) {
      if (controller.signal.aborted || selection.request !== generation.current || activeMediaSelection.current !== selection) return;
      if (reason instanceof RequestError && [401, 403, 404].includes(reason.status)) {
        clearProtectedState(); setError('Доступ к записи закрыт. Медиа и сведения проверки очищены.');
      } else setError(errorMessage(reason));
    } finally { if (mediaAbort.current === controller) mediaAbort.current = null; }
  };

  const decide = async () => {
    if (!detail || note.trim().length < 1 || note.trim().length > 2000 || saving) return;
    const body = {
      expected_version: detail.version,
      expected_attempt_version: detail.attempt_version,
      outcome,
      reason_code: reasonCode,
      note: note.trim(),
    };
    const fingerprint = JSON.stringify(body);
    if (pendingDecision.current?.fingerprint !== fingerprint) pendingDecision.current = { fingerprint, idempotencyKey: crypto.randomUUID() };
    setSaving(true); setError(''); setNotice('');
    try {
      await decideProctoringReview(detail.session_id, body, pendingDecision.current.idempotencyKey);
      pendingDecision.current = undefined;
      stopMediaPlayback();
      setDetail(null);
      const queueIsSafeToUpdate = await loadQueue();
      if (queueIsSafeToUpdate) setNotice('Решение сохранено на сервере. Обновите очередь для следующей записи.');
    } catch (reason) {
      if (reason instanceof RequestError && [401, 403, 404].includes(reason.status)) {
        clearProtectedState(); setError('Доступ к проверке закрыт. Решение не было подтверждено.');
      } else {
        if (reason instanceof RequestError && reason.status === 409) pendingDecision.current = undefined;
        setError(errorMessage(reason));
      }
    } finally { setSaving(false); }
  };

  return <section className="proctoring-queue-page" aria-label="Очередь серверной проверки">
    <header className="page-header"><div><div className="eyebrow">ТОЛЬКО ОПЕРАТОР · СЕРВЕРНАЯ ПРОВЕРКА</div><h1>Очередь серверной проверки</h1><p>Сервер удерживает результат попытки до явного решения оператора. Браузерные события — только контекст; основанием решения служит проверка записи.</p></div><button type="button" className="button button-secondary" onClick={() => void loadQueue()} disabled={loading}>{loading ? 'Обновляем…' : 'Обновить очередь'}</button></header>
    {error && <div className="notice notice-danger" role="alert">{error}</div>}{notice && <div className="notice notice-info" role="status">{notice}</div>}
    <div className="proctoring-server-review-layout">
      <section className="card" aria-label="Попытки, ожидающие проверки"><h2>Ожидают решения · {queue.length}</h2>
        {queue.length === 0 ? <p>{loading ? 'Загружаем очередь…' : 'Сервер не вернул попыток, ожидающих проверки.'}</p> : <ul className="proctoring-server-review-list">{queue.map((item) => <li key={item.session_id}><button type="button" className="button button-secondary" aria-current={selectedId === item.session_id ? 'true' : undefined} onClick={() => void loadDetail(item.session_id)}><strong>{item.requested_grade}</strong><span>{item.submitted_at ? new Date(item.submitted_at).toLocaleString('ru-RU') : 'Дата отправки неизвестна'}</span><small>{item.session_id}</small></button></li>)}</ul>}
      </section>
      <div className="proctoring-server-review-detail">
        {detail ? <>
          <section className="card"><div className="card-head"><div><span className="eyebrow">СЕССИЯ {detail.session_id}</span><h2>{statusLabel[detail.status]}</h2></div><span className="badge badge-warm">Решение оператора обязательно</span></div>
            <p>Попытка {detail.attempt.attempt_id} · {detail.attempt.requested_grade} · версия попытки {detail.attempt_version} · согласие сессии подтверждено при создании.</p>
            {detail.provisional_result && <p>Предварительный балл сервера: {detail.provisional_result.total_score} / {detail.provisional_result.max_score}. Этот балл не является опубликованным результатом.</p>}
            <p>События браузера сами по себе не подтверждают нарушение; решение основывайте на проверке записи и серверного результата.</p>
            <section aria-label="Задания попытки"><h3>Задания попытки</h3><p>Этот обзор не содержит ключи и выбор ответов. Проверьте запись и предварительную оценку сервера.</p>{detail.attempt.tasks.map((task) => <article className="card" key={task.task_id}><strong>{task.competency_block}</strong><p>{task.prompt}</p>{task.subquestions.map((question) => <div key={question.subquestion_id}><h4>{question.prompt}</h4><ul>{question.options.map((option) => <li key={option.id}>{option.text}</li>)}</ul></div>)}</article>)}</section>
          </section>
          <section className="card"><h3>Записи медиа</h3>{detail.recordings.map((capture) => <article key={capture.capture_id}><p>Фрагмент {capture.capture_id} · {capture.status} · {capture.started_at ? new Date(capture.started_at).toLocaleString('ru-RU') : 'Дата неизвестна'}</p><div className="button-row">{(['camera', 'screen'] as const).filter((channel) => capture.media[channel].bytes > 0).map((channel) => <button key={channel} type="button" className="button button-secondary button-small" onClick={() => void openMedia(capture.capture_id, channel)}>Открыть {channel === 'camera' ? 'камеру' : 'экран'} · {capture.media[channel].chunks} фрагм.</button>)}</div></article>)}{mediaUrl && mediaSelection && <div><p>{mediaLabel}</p><video key={`${mediaSelection.sessionId}:${mediaSelection.captureId}:${mediaSelection.channel}`} ref={videoRef} controls preload="metadata" src={mediaUrl} onError={() => void handleMediaError(mediaSelection)} style={{ width: '100%', maxHeight: '65vh' }} /></div>}</section>
          <form className="card" onSubmit={(event) => { event.preventDefault(); void decide(); }}><h3>Решение оператора</h3><label className="field"><span>Решение</span><select value={outcome} onChange={(event) => { const next = event.target.value as Outcome; setOutcome(next); setReasonCode(outcomeReasons[next][0].value); }}><option value="uphold">Подтвердить серверный результат</option><option value="invalidate">Аннулировать попытку</option><option value="technical">Техническая ошибка</option></select></label><label className="field"><span>Основание</span><select value={reasonCode} onChange={(event) => setReasonCode(event.target.value as ReasonCode)}>{outcomeReasons[outcome].map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label><label className="field"><span>Комментарий оператора</span><textarea value={note} onChange={(event) => setNote(event.target.value)} minLength={1} maxLength={2000} rows={4} required /></label><button type="submit" className="button button-primary" disabled={saving || !note.trim()}>{saving ? 'Сохраняем…' : 'Сохранить решение на сервере'}</button></form>
        </> : <section className="card"><h2>Выберите попытку</h2><p>Видео и сведения о попытке доступны только при действующем серверном разрешении оператора.</p></section>}
      </div>
    </div>
  </section>;
}
