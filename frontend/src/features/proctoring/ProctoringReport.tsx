import { useEffect, useRef, useState } from 'react';
import type { ProctoringReportData, ProctoringEvent } from './proctoring-types.ts';
import { submitManagerVerdict, getActiveFraudBan, loadProctoringVideoBlob } from './proctoring-storage.ts';
import './proctoring.css';

interface ProctoringReportProps {
  report: ProctoringReportData;
  isManagerView?: boolean;
  onVerdictSubmitted?: (updatedReport: ProctoringReportData) => void;
}

export function ProctoringReport({
  report: initialReport,
  isManagerView = false,
  onVerdictSubmitted,
}: ProctoringReportProps) {
  const [report, setReport] = useState<ProctoringReportData>(initialReport);
  const [videoSrc, setVideoSrc] = useState<string | undefined>(initialReport.video_url);
  const [screenVideoSrc, setScreenVideoSrc] = useState<string | undefined>(initialReport.screen_video_url);
  const [activeVideoTab, setActiveVideoTab] = useState<'both' | 'camera' | 'screen'>('both');
  const [filterSeverity, setFilterSeverity] = useState<string>('all');
  const [managerComment, setManagerComment] = useState('');
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const [selectedEventId, setSelectedEventId] = useState<string | null>(null);
  const [currentProgress, setCurrentProgress] = useState<number>(0);
  const [isPlaying, setIsPlaying] = useState<boolean>(false);

  const cameraVideoRef = useRef<HTMLVideoElement | null>(null);
  const screenVideoRef = useRef<HTMLVideoElement | null>(null);
  const timelineContainerRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    setReport(initialReport);
    setVideoSrc(initialReport.video_url);
    setScreenVideoSrc(initialReport.screen_video_url);
  }, [initialReport]);

  useEffect(() => {
    let active = true;
    if (!initialReport.video_url) {
      loadProctoringVideoBlob(report.attempt_id, 'camera').then((blob) => {
        if (!active || !blob || typeof URL === 'undefined') return;
        try {
          setVideoSrc(URL.createObjectURL(blob));
        } catch {
          // ignore
        }
      });
    }

    if (!initialReport.screen_video_url) {
      loadProctoringVideoBlob(report.attempt_id, 'screen').then((blob) => {
        if (!active || !blob || typeof URL === 'undefined') return;
        try {
          setScreenVideoSrc(URL.createObjectURL(blob));
        } catch {
          // ignore
        }
      });
    }

    return () => {
      active = false;
    };
  }, [report.attempt_id, initialReport.video_url, initialReport.screen_video_url]);

  const activeBan = getActiveFraudBan(
    report.specialization_id || 'backend-python-v1',
    report.requested_grade || 'junior'
  );

  const handleManagerDecision = (decision: 'confirm_fraud' | 'dismiss_fraud') => {
    const updated = submitManagerVerdict({
      attemptId: report.attempt_id,
      decision,
      comment:
        managerComment ||
        (decision === 'confirm_fraud'
          ? 'Подтверждено использование сторонней помощи по результатам проверки видеозаписи'
          : 'Нарушения регламента не подтвердились, сдача признана корректной'),
    });
    if (updated) {
      setReport(updated);
      setActionNotice(
        decision === 'confirm_fraud'
          ? 'Фрод подтвержден! Попытка аннулирована, кандидату назначен бан на 30 дней.'
          : 'Кандидат оправдан! Результат экзамена и грейд подтверждены.'
      );
      onVerdictSubmitted?.(updated);
    }
  };

  const seekToSeconds = (sec: number, eventId?: string) => {
    if (eventId) setSelectedEventId(eventId);
    const target = Math.max(0, sec);
    if (cameraVideoRef.current) {
      cameraVideoRef.current.currentTime = target;
      cameraVideoRef.current.play().catch(() => {});
    }
    if (screenVideoRef.current) {
      screenVideoRef.current.currentTime = target;
      screenVideoRef.current.play().catch(() => {});
    }
    setIsPlaying(true);
    const total = Math.max(1, report.duration_seconds || 60);
    setCurrentProgress(Math.min(100, (target / total) * 100));
    if (timelineContainerRef.current) {
      timelineContainerRef.current.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }
  };

  const seekToEvent = (ev: ProctoringEvent) => {
    const startMs = new Date(report.started_at).getTime();
    const evMs = new Date(ev.timestamp).getTime();
    const offsetSec = Math.max(0, Math.floor((evMs - startMs) / 1000) - 2);
    seekToSeconds(offsetSec, ev.id);
  };

  const toggleSyncPlayback = () => {
    if (isPlaying) {
      cameraVideoRef.current?.pause();
      screenVideoRef.current?.pause();
      setIsPlaying(false);
    } else {
      cameraVideoRef.current?.play().catch(() => {});
      screenVideoRef.current?.play().catch(() => {});
      setIsPlaying(true);
    }
  };

  const stepSeconds = (delta: number) => {
    const cur = cameraVideoRef.current?.currentTime || screenVideoRef.current?.currentTime || 0;
    seekToSeconds(cur + delta);
  };

  const handleScrubClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width));
    const targetSec = ratio * (report.duration_seconds || 60);
    seekToSeconds(targetSec);
  };

  const getStatusBadge = () => {
    switch (report.review_status) {
      case 'confirmed_fraud':
        return {
          bannerClass: 'fraud',
          title: 'Попытка аннулирована: фрод подтвержден экспертом',
          subtitle: isManagerView
            ? `Сотрудник платформы подтвердил нечестную сдачу. Кандидату выставлена блокировка на 30 дней (до ${activeBan?.unban_at ? new Date(activeBan.unban_at).toLocaleDateString('ru-RU') : '30 дней'}).`
            : `Вы нечестно сдали экзамен. На данный грейд установлена блокировка на 30 дней. Следующая попытка через ${activeBan?.days_left ?? 30} дней.`,
        };
      case 'review_required':
        return {
          bannerClass: 'suspicious',
          title: 'Подозрение на фрод: экзамен направлен эксперту',
          subtitle: isManagerView
            ? 'Зафиксированы отклонения от регламента сдачи (смена окон, отвод взгляда). Требуется решение сотрудника платформы или работодателя.'
            : 'Зафиксированы отклонения от регламента честной сдачи. Видеозапись и протокол сессии переданы на проверку эксперту платформы / работодателю. Окончательное решение остается за проверяющим.',
        };
      case 'dismissed_clean':
        return {
          bannerClass: 'clean',
          title: 'Проверка экспертом завершена: результат подтвержден',
          subtitle: 'Подозрения признаны несущественными. Зачет сохранен, грейд подтвержден.',
        };
      default:
        return {
          bannerClass: 'clean',
          title: 'Экзамен сдан честно',
          subtitle: 'Существенных нарушений регламента не обнаружено.',
        };
    }
  };

  const statusInfo = getStatusBadge();

  const filteredEvents = report.events.filter((ev) => {
    if (filterSeverity === 'all') return true;
    return ev.severity === filterSeverity;
  });

  const getEventTitle = (type: ProctoringEvent['event_type']) => {
    switch (type) {
      case 'tab_hidden':
        return 'Переключение на другую вкладку браузера';
      case 'window_blur':
        return 'Потеря фокуса окна теста';
      case 'gaze_deviation':
        return 'Отвод взгляда от экрана (подозрение на шпаргалку/телефон/AI)';
      case 'hotkey_switch':
        return 'Попытка переключения окон горячими клавишами (Alt+Tab / Win)';
      case 'devtools_attempt':
        return 'Попытка вызова инструментов разработчика (DevTools)';
      case 'copy_attempt':
        return 'Попытка копирования текста задания';
      case 'paste_attempt':
        return 'Попытка вставки текста в поле ответа';
      case 'face_missing':
        return 'Лицо не обнаружено в кадре камеры';
      case 'multiple_faces':
        return 'Обнаружено несколько лиц в кадре';
      case 'camera_disabled':
        return 'Отключение веб-камеры во время экзамена';
      case 'screen_share_stopped':
        return 'Остановка демонстрации экрана';
      case 'fullscreen_exit':
        return 'Выход из полноэкранного режима';
      case 'context_menu':
        return 'Вызов контекстного меню браузера';
      default:
        return 'Зафиксировано подозрительное событие';
    }
  };

  return (
    <section className="card proctoring-report-card">
      <div className="card-head">
        <div>
          <h2>Протокол видео-прокторинга</h2>
          {isManagerView && report.candidate_name && (
            <p style={{ margin: '0.2rem 0 0 0', fontSize: '0.9rem', color: '#55565d' }}>
              Кандидат: <strong>{report.candidate_name}</strong> · Грейд: <strong>{report.requested_grade?.toUpperCase() ?? 'JUNIOR'}</strong>
            </p>
          )}
        </div>
        <span className="badge">Сессия: {report.session_id.slice(0, 8)}</span>
      </div>

      {actionNotice && (
        <div className="notice notice-warm" style={{ marginBottom: '1rem' }}>
          {actionNotice}
        </div>
      )}

      {/* Main Fraud Status Banner */}
      <div className={`proctoring-score-banner ${statusInfo.bannerClass}`}>
        <div>
          <span className="eyebrow" style={{ color: 'inherit' }}>
            СТАТУС ПРОВЕРКИ ЧЕСТНОСТИ
          </span>
          <h3 style={{ margin: '0.2rem 0', fontSize: '1.25rem' }}>{statusInfo.title}</h3>
          <p style={{ margin: 0, fontSize: '0.9rem', lineHeight: 1.45 }}>{statusInfo.subtitle}</p>
          {isManagerView && report.manager_verdict && (
            <p style={{ marginTop: '0.5rem', fontSize: '0.85rem', fontStyle: 'italic' }}>
              Вердикт эксперта ({report.manager_verdict.reviewer_name}): «{report.manager_verdict.comment}»
            </p>
          )}
        </div>
        <div style={{ textAlign: 'right', minWidth: '100px' }}>
          <div className="proctoring-score-val">{report.fraud_score} / 100</div>
          <small>Индекс риска</small>
        </div>
      </div>

      {/* Ban warning if active */}
      {activeBan && (
        <div className="notice notice-danger" style={{ marginBottom: '1.5rem' }}>
          <strong>⛔ Доступ к повторной оценке заблокирован за фрод!</strong>
          <p style={{ margin: '0.25rem 0 0 0' }}>
            Повторный экзамен на грейд «{activeBan.grade.toUpperCase()}» недоступен еще {activeBan.days_left} дн. (до {new Date(activeBan.unban_at).toLocaleDateString('ru-RU')}).
          </p>
        </div>
      )}

      {/* Candidate-specific notification: instructions without management controls */}
      {!isManagerView && (
        <div className="notice notice-info" style={{ marginBottom: '1.5rem' }}>
          <strong>🎥 Видеозапись и телеметрия сессии сохранены</strong>
          <p style={{ margin: '0.35rem 0 0 0', fontSize: '0.88rem', lineHeight: 1.4 }}>
            Видеозапись веб-камеры и события прокторинга зафиксированы в защищенном архиве платформы. Подтверждение оценки и вынесение окончательного решения осуществляется сотрудником платформы либо работодателем.
          </p>
        </div>
      )}

      {/* Violation Stats */}
      <div className="proctoring-stats-row">
        <div className="proctoring-stat-box">
          <span>Смена вкладок</span>
          <strong>{report.events_summary.tab_switches}</strong>
        </div>
        <div className="proctoring-stat-box">
          <span>Потеря фокуса</span>
          <strong>{report.events_summary.window_blurs}</strong>
        </div>
        <div className="proctoring-stat-box">
          <span>Отвод взгляда (Gaze)</span>
          <strong>{report.events_summary.gaze_deviations}</strong>
        </div>
        <div className="proctoring-stat-box">
          <span>Уход из кадра</span>
          <strong>{report.events_summary.face_missing_count}</strong>
        </div>
        <div className="proctoring-stat-box">
          <span>Буфер обмена</span>
          <strong>{report.events_summary.clipboard_actions}</strong>
        </div>
      </div>

      {/* Video playback ONLY for Manager / Platform Staff / Employer */}
      {isManagerView && (
        <div
          style={{
            marginBottom: '1.5rem',
            background: '#111827',
            borderRadius: '10px',
            padding: '1.25rem',
            color: '#ffffff',
          }}
        >
          <div
            style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center',
              marginBottom: '1rem',
              flexWrap: 'wrap',
              gap: '0.75rem',
            }}
          >
            <div>
              <h3 style={{ fontSize: '1.1rem', margin: '0 0 0.2rem 0', color: '#ffffff' }}>
                🎥 Записи сессии экзамена: Веб-камера и Захват экрана
              </h3>
              <p style={{ fontSize: '0.8rem', color: '#94a3b8', margin: 0 }}>
                Два независимых синхронизированных видеопотока для детальной проверки экспертом
              </p>
            </div>
            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
              <div className="button-group" style={{ display: 'flex', background: '#1e293b', borderRadius: '6px', padding: '2px' }}>
                <button
                  type="button"
                  className={`button button-small ${activeVideoTab === 'both' ? 'button-primary' : 'button-secondary'}`}
                  style={{ fontSize: '0.75rem', padding: '0.25rem 0.6rem' }}
                  onClick={() => setActiveVideoTab('both')}
                >
                  Оба экрана
                </button>
                <button
                  type="button"
                  className={`button button-small ${activeVideoTab === 'camera' ? 'button-primary' : 'button-secondary'}`}
                  style={{ fontSize: '0.75rem', padding: '0.25rem 0.6rem' }}
                  onClick={() => setActiveVideoTab('camera')}
                >
                  📷 Веб-камера
                </button>
                <button
                  type="button"
                  className={`button button-small ${activeVideoTab === 'screen' ? 'button-primary' : 'button-secondary'}`}
                  style={{ fontSize: '0.75rem', padding: '0.25rem 0.6rem' }}
                  onClick={() => setActiveVideoTab('screen')}
                >
                  🖥️ Захват экрана
                </button>
              </div>
              <span
                className="badge"
                style={{
                  background: '#1e293b',
                  color: '#38bdf8',
                  borderColor: '#334155',
                }}
              >
                {Math.round(report.duration_seconds / 60)} мин.
              </span>
            </div>
          </div>

          {/* Synchronized Playback and Interactive Timeline */}
          <div className="proctoring-scrub-container" ref={timelineContainerRef}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.6rem', flexWrap: 'wrap', gap: '0.5rem' }}>
              <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                <button
                  type="button"
                  className="button button-small button-primary"
                  style={{ fontSize: '0.8rem', padding: '0.3rem 0.8rem' }}
                  onClick={toggleSyncPlayback}
                >
                  {isPlaying ? '⏸ Пауза оба' : '▶ Синхронный плей'}
                </button>
                <button
                  type="button"
                  className="button button-small button-secondary"
                  style={{ fontSize: '0.8rem', padding: '0.3rem 0.6rem' }}
                  onClick={() => stepSeconds(-5)}
                >
                  ⏪ -5с
                </button>
                <button
                  type="button"
                  className="button button-small button-secondary"
                  style={{ fontSize: '0.8rem', padding: '0.3rem 0.6rem' }}
                  onClick={() => stepSeconds(5)}
                >
                  +5с ⏩
                </button>
              </div>
              <span style={{ fontSize: '0.75rem', color: '#94a3b8' }}>
                🎯 Кликните на маркер нарушения на таймлайне для мгновенного перехода
              </span>
            </div>

            <div
              className="proctoring-scrub-bar-wrapper"
              onClick={handleScrubClick}
              title="Нажмите на шкалу для перемотки"
            >
              <div className="proctoring-scrub-track">
                <div
                  className="proctoring-scrub-progress"
                  style={{ width: `${currentProgress}%` }}
                />
                {report.events.map((ev) => {
                  const startMs = new Date(report.started_at).getTime();
                  const evMs = new Date(ev.timestamp).getTime();
                  const offsetSec = Math.max(0, (evMs - startMs) / 1000);
                  const total = Math.max(1, report.duration_seconds || 60);
                  const pct = Math.min(100, Math.max(0, (offsetSec / total) * 100));
                  return (
                    <div
                      key={ev.id}
                      className={`proctoring-scrub-pin ${ev.severity}`}
                      style={{ left: `${pct}%` }}
                      onClick={(e) => {
                        e.stopPropagation();
                        seekToEvent(ev);
                      }}
                      title={`${new Date(ev.timestamp).toLocaleTimeString('ru-RU')}: ${getEventTitle(ev.event_type)}`}
                    />
                  );
                })}
              </div>
            </div>
          </div>

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: activeVideoTab === 'both' ? 'repeat(auto-fit, minmax(320px, 1fr))' : '1fr',
              gap: '1rem',
            }}
          >
            {/* 1. Camera Video */}
            {(activeVideoTab === 'both' || activeVideoTab === 'camera') && (
              <div style={{ background: '#0f172a', borderRadius: '8px', padding: '0.75rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                  <span style={{ fontSize: '0.85rem', fontWeight: 600, color: '#38bdf8' }}>
                    📷 1. Запись веб-камеры (Лицо и взгляд)
                  </span>
                  <span className="badge" style={{ background: '#1e293b', color: '#94a3b8', fontSize: '0.7rem' }}>
                    640x480
                  </span>
                </div>
                {videoSrc ? (
                  <>
                    <video
                      ref={cameraVideoRef}
                      controls
                      playsInline
                      src={videoSrc}
                      onTimeUpdate={(e) => {
                        const cur = (e.target as HTMLVideoElement).currentTime;
                        if (report.duration_seconds > 0) {
                          setCurrentProgress((cur / report.duration_seconds) * 100);
                        }
                      }}
                      style={{
                        width: '100%',
                        maxHeight: '340px',
                        borderRadius: '6px',
                        background: '#000000',
                        outline: 'none',
                      }}
                    />
                    <div style={{ marginTop: '0.4rem', display: 'flex', justifyContent: 'flex-end' }}>
                      <a
                        href={videoSrc}
                        download={`webcam-${report.attempt_id}.webm`}
                        className="text-button"
                        style={{ fontSize: '0.75rem', color: '#38bdf8' }}
                      >
                        📥 Скачать запись веб-камеры (.webm)
                      </a>
                    </div>
                  </>
                ) : (
                  <div style={{ padding: '2rem 1rem', textAlign: 'center', color: '#94a3b8' }}>
                    📹 Запись веб-камеры не зафиксирована в этой сессии
                  </div>
                )}
              </div>
            )}

            {/* 2. Screen Share Video */}
            {(activeVideoTab === 'both' || activeVideoTab === 'screen') && (
              <div style={{ background: '#0f172a', borderRadius: '8px', padding: '0.75rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                  <span style={{ fontSize: '0.85rem', fontWeight: 600, color: '#f59e0b' }}>
                    🖥️ 2. Запись экрана (Действия на рабочем столе)
                  </span>
                  <span className="badge" style={{ background: '#1e293b', color: '#94a3b8', fontSize: '0.7rem' }}>
                    Screen Share
                  </span>
                </div>
                {screenVideoSrc ? (
                  <>
                    <video
                      ref={screenVideoRef}
                      controls
                      playsInline
                      src={screenVideoSrc}
                      style={{
                        width: '100%',
                        maxHeight: '340px',
                        borderRadius: '6px',
                        background: '#000000',
                        outline: 'none',
                      }}
                    />
                    <div style={{ marginTop: '0.4rem', display: 'flex', justifyContent: 'flex-end' }}>
                      <a
                        href={screenVideoSrc}
                        download={`screen-${report.attempt_id}.webm`}
                        className="text-button"
                        style={{ fontSize: '0.75rem', color: '#f59e0b' }}
                      >
                        📥 Скачать запись экрана (.webm)
                      </a>
                    </div>
                  </>
                ) : (
                  <div style={{ padding: '2rem 1rem', textAlign: 'center', color: '#94a3b8' }}>
                    🖥️ Запись экрана не зафиксирована в этой сессии
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}

      {/* Manager / Employer Confirmation Panel ONLY */}
      {isManagerView && (
        <div
          style={{
            background: '#f8f9fa',
            border: '2px dashed #402fff',
            borderRadius: '10px',
            padding: '1.25rem',
            marginBottom: '1.5rem',
          }}
        >
          <span className="eyebrow" style={{ color: '#402fff' }}>
            ПОДТВЕРЖДЕНИЕ ОЦЕНКИ · ПАНЕЛЬ РЕШЕНИЯ ЭКСПЕРТА
          </span>
          <h3 style={{ margin: '0.25rem 0 0.5rem 0', fontSize: '1.1rem' }}>
            Решение сотрудника платформы / работодателя
          </h3>
          <p style={{ fontSize: '0.85rem', color: '#55565d', margin: '0 0 1rem 0' }}>
            Окончательное слово остаётся за сотрудником платформы или работодателем. Изучите видеозапись и таймлайн подозрительных моментов перед вынесением вердикта.
          </p>

          <label className="field" style={{ marginBottom: '1rem' }}>
            <span>Комментарий к экспертному решению</span>
            <input
              type="text"
              placeholder="Причина решения (например: подтверждено использование шпаргалки на втором экране)"
              value={managerComment}
              onChange={(e) => setManagerComment(e.target.value)}
            />
          </label>

          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
            <button
              type="button"
              className="button button-danger"
              onClick={() => handleManagerDecision('confirm_fraud')}
            >
              ⛔ Подтвердить фрод (Бан на 30 дней)
            </button>
            <button
              type="button"
              className="button button-secondary"
              onClick={() => handleManagerDecision('dismiss_fraud')}
            >
              ✓ Оправдать (Подтвердить оценку)
            </button>
          </div>
        </div>
      )}

      {/* Events Timeline */}
      <div>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            marginBottom: '0.75rem',
          }}
        >
          <h3 style={{ fontSize: '1rem', margin: 0 }}>
            Таймлайн подозрительных моментов ({report.events.length})
          </h3>
          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button
              type="button"
              className={`button button-small ${filterSeverity === 'all' ? 'button-primary' : 'button-secondary'}`}
              onClick={() => setFilterSeverity('all')}
            >
              Все
            </button>
            <button
              type="button"
              className={`button button-small ${filterSeverity === 'high' ? 'button-primary' : 'button-secondary'}`}
              onClick={() => setFilterSeverity('high')}
            >
              Критические
            </button>
          </div>
        </div>

        {filteredEvents.length === 0 ? (
          <p className="empty-state" style={{ padding: '1rem', background: '#f8f9fa', borderRadius: '6px' }}>
            Событий в выбранной категории не зафиксировано.
          </p>
        ) : (
          <ul className="proctoring-events-timeline">
            {filteredEvents.map((ev) => {
              const startMs = new Date(report.started_at).getTime();
              const evMs = new Date(ev.timestamp).getTime();
              const sec = Math.max(0, Math.floor((evMs - startMs) / 1000));
              const m = Math.floor(sec / 60).toString().padStart(2, '0');
              const s = (sec % 60).toString().padStart(2, '0');
              const isSelected = selectedEventId === ev.id;

              return (
                <li
                  key={ev.id}
                  className={`proctoring-timeline-item ${ev.severity} ${isSelected ? 'active' : ''} ${isManagerView ? 'clickable' : ''}`}
                  onClick={() => {
                    if (isManagerView) {
                      seekToEvent(ev);
                    }
                  }}
                  title={isManagerView ? 'Нажмите, чтобы перемотать видео на момент нарушения' : undefined}
                >
                  <div className="proctoring-timeline-time">
                    {new Date(ev.timestamp).toLocaleTimeString('ru-RU')}
                    <div style={{ fontSize: '0.72rem', color: '#6b7280', marginTop: '2px' }}>
                      ⏱ +{m}:{s}
                    </div>
                  </div>
                  <div className="proctoring-timeline-desc">
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '0.5rem' }}>
                      <strong>{getEventTitle(ev.event_type)}</strong>
                      {isManagerView && (
                        <button
                          type="button"
                          className="button button-small button-secondary"
                          style={{ padding: '0.2rem 0.5rem', fontSize: '0.75rem', whiteSpace: 'nowrap' }}
                          onClick={(e) => {
                            e.stopPropagation();
                            seekToEvent(ev);
                          }}
                        >
                          ▶ В плеер (+{m}:{s})
                        </button>
                      )}
                    </div>
                    <small>
                      Важность: {ev.severity.toUpperCase()}
                      {ev.duration_seconds ? ` · Длительность: ${ev.duration_seconds} сек.` : ''}
                      {ev.metadata ? ` · ${JSON.stringify(ev.metadata)}` : ''}
                    </small>
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </section>
  );
}
