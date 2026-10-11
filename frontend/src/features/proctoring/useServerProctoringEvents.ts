import { useEffect, useRef, useState } from 'react';
import type { ServerObservationType } from './server-proctoring-client.ts';

export interface ServerObservationEvent {
  id: string;
  event_type: ServerObservationType;
  timestamp: string;
}

export function useServerProctoringEvents(enabled: boolean, sessionId: string | null) {
  const [events, setEvents] = useState<ServerObservationEvent[]>([]);
  const currentSessionId = useRef(sessionId);
  const emitEvent = (eventType: ServerObservationType) => {
    setEvents((current) => current.length >= 2000 ? current : [...current, { id: crypto.randomUUID(), event_type: eventType, timestamp: new Date().toISOString() }]);
  };

  useEffect(() => {
    if (currentSessionId.current === sessionId) return;
    currentSessionId.current = sessionId;
    setEvents([]);
  }, [sessionId]);

  useEffect(() => {
    if (!enabled) return;
    let enteredFullscreen = Boolean(document.fullscreenElement);
    const visibility = () => { if (document.visibilityState === 'hidden') emitEvent('tab_hidden'); };
    const blur = () => emitEvent('window_blur');
    const fullscreen = () => {
      if (document.fullscreenElement) enteredFullscreen = true;
      else if (enteredFullscreen) { enteredFullscreen = false; emitEvent('fullscreen_exit'); }
    };
    document.addEventListener('visibilitychange', visibility);
    window.addEventListener('blur', blur);
    document.addEventListener('fullscreenchange', fullscreen);
    return () => {
      document.removeEventListener('visibilitychange', visibility);
      window.removeEventListener('blur', blur);
      document.removeEventListener('fullscreenchange', fullscreen);
    };
  }, [enabled]);

  return { events, emitEvent, clearEvents: () => setEvents([]) };
}
