import { useCallback, useEffect, useRef, useState } from 'react';
import type { ProctoringEvent, ProctoringEventType, ProctoringSeverity } from './proctoring-types.ts';

interface UseEventMonitorOptions {
  active: boolean;
  sessionId: string;
  onEventEmitted?: (event: ProctoringEvent) => void;
}

const severityMap: Record<ProctoringEventType, ProctoringSeverity> = {
  tab_hidden: 'high',
  window_blur: 'medium',
  fullscreen_exit: 'medium',
  copy_attempt: 'medium',
  paste_attempt: 'medium',
  context_menu: 'low',
  window_resize: 'low',
  face_missing: 'high',
  multiple_faces: 'high',
  gaze_deviation: 'high',
  hotkey_switch: 'high',
  devtools_attempt: 'critical',
  camera_disabled: 'critical',
  screen_share_stopped: 'critical',
  audio_anomaly: 'medium',
};

export function useEventMonitor({ active, sessionId, onEventEmitted }: UseEventMonitorOptions) {
  const [events, setEvents] = useState<ProctoringEvent[]>([]);
  const [lastWarning, setLastWarning] = useState<string | null>(null);

  const blurTimestampRef = useRef<number | null>(null);
  const hiddenTimestampRef = useRef<number | null>(null);
  const onEventEmittedRef = useRef(onEventEmitted);
  onEventEmittedRef.current = onEventEmitted;

  const emitEvent = useCallback(
    (type: ProctoringEventType, meta?: Record<string, string | number | boolean>, duration?: number) => {
      const event: ProctoringEvent = {
        id: crypto.randomUUID ? crypto.randomUUID() : `ev-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
        session_id: sessionId,
        event_type: type,
        severity: severityMap[type] || 'medium',
        timestamp: new Date().toISOString(),
        duration_seconds: duration,
        metadata: meta,
      };

      setEvents((prev) => [...prev, event]);
      onEventEmittedRef.current?.(event);

      // User-facing warning text
      let warningText: string | null = null;
      if (type === 'tab_hidden') warningText = 'Внимание: переключение вкладки зафиксировано в протоколе!';
      else if (type === 'window_blur') warningText = 'Внимание: потеря фокуса окна зафиксирована!';
      else if (type === 'copy_attempt') warningText = 'Копирование текста задания зафиксировано!';
      else if (type === 'paste_attempt') warningText = 'Вставка текста зафиксирована!';
      else if (type === 'gaze_deviation') warningText = 'Внимание: отведенный взгляд зафиксирован в протоколе!';
      else if (type === 'hotkey_switch') warningText = 'Попытка системного переключения окон (Alt+Tab / Win) зафиксирована!';
      else if (type === 'devtools_attempt') warningText = 'Критично: попытка открытия DevTools зафиксирована!';
      else if (type === 'camera_disabled') warningText = 'Критично: камера была отключена!';
      else if (type === 'screen_share_stopped') warningText = 'Критично: демонстрация экрана остановлена!';

      if (warningText) {
        setLastWarning(warningText);
        window.setTimeout(() => {
          setLastWarning((curr) => (curr === warningText ? null : curr));
        }, 4000);
      }
    },
    [sessionId]
  );

  useEffect(() => {
    if (!active) return;

    // 1. Tab visibility monitoring
    const handleVisibilityChange = () => {
      if (document.hidden) {
        hiddenTimestampRef.current = Date.now();
        emitEvent('tab_hidden');
      } else if (hiddenTimestampRef.current) {
        const duration = Math.round((Date.now() - hiddenTimestampRef.current) / 1000);
        hiddenTimestampRef.current = null;
        emitEvent('tab_hidden', { action: 'returned_to_tab', duration_seconds: duration }, duration);
      }
    };

    // 2. Window focus & blur monitoring
    const handleWindowBlur = () => {
      blurTimestampRef.current = Date.now();
      emitEvent('window_blur');
    };

    const handleWindowFocus = () => {
      if (blurTimestampRef.current) {
        const duration = Math.round((Date.now() - blurTimestampRef.current) / 1000);
        blurTimestampRef.current = null;
        emitEvent('window_blur', { action: 'refocused', duration_seconds: duration }, duration);
      }
    };

    // 3. Copy & paste interception
    const handleCopy = (_e: ClipboardEvent) => {
      emitEvent('copy_attempt', { hasSelection: Boolean(window.getSelection()?.toString()) });
    };

    const handlePaste = (e: ClipboardEvent) => {
      emitEvent('paste_attempt', {
        textLength: e.clipboardData?.getData('text')?.length || 0,
      });
    };

    // 4. Keyboard shortcuts interception (Alt+Tab, Win, F12, DevTools)
    const handleKeyDown = (e: KeyboardEvent) => {
      // DevTools keys
      if (
        e.key === 'F12' ||
        (e.ctrlKey && e.shiftKey && ['I', 'J', 'C'].includes(e.key.toUpperCase()))
      ) {
        e.preventDefault();
        emitEvent('devtools_attempt', { key: e.key });
      }

      // Hotkey switch attempts (Alt+Tab, Meta)
      if (e.key === 'Meta' || (e.altKey && e.key === 'Tab')) {
        emitEvent('hotkey_switch', { key: e.key });
      }
    };

    // 5. Context menu prevention/log
    const handleContextMenu = (e: MouseEvent) => {
      e.preventDefault();
      emitEvent('context_menu', { x: e.clientX, y: e.clientY });
    };

    // 6. Fullscreen exit
    const handleFullscreenChange = () => {
      if (!document.fullscreenElement) {
        emitEvent('fullscreen_exit');
      }
    };

    document.addEventListener('visibilitychange', handleVisibilityChange);
    window.addEventListener('blur', handleWindowBlur);
    window.addEventListener('focus', handleWindowFocus);
    document.addEventListener('copy', handleCopy);
    document.addEventListener('paste', handlePaste);
    window.addEventListener('keydown', handleKeyDown);
    document.addEventListener('contextmenu', handleContextMenu);
    document.addEventListener('fullscreenchange', handleFullscreenChange);

    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
      window.removeEventListener('blur', handleWindowBlur);
      window.removeEventListener('focus', handleWindowFocus);
      document.removeEventListener('copy', handleCopy);
      document.removeEventListener('paste', handlePaste);
      window.removeEventListener('keydown', handleKeyDown);
      document.removeEventListener('contextmenu', handleContextMenu);
      document.removeEventListener('fullscreenchange', handleFullscreenChange);
    };
  }, [active, emitEvent]);

  return {
    events,
    emitEvent,
    lastWarning,
  };
}
