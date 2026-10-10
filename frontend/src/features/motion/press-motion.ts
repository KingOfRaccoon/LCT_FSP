import { createCriticallyDampedSpring, type CriticallyDampedSpring } from './spring.ts';

export interface ReducedMotionQuery {
  readonly matches: boolean;
  addEventListener(type: 'change', listener: (event: MediaQueryListEvent) => void): void;
  removeEventListener(type: 'change', listener: (event: MediaQueryListEvent) => void): void;
}

export interface PressMotionOptions {
  reducedMotionQuery?: ReducedMotionQuery;
}

const PRESSABLE = 'button, a.button, a.text-button, a.nav-link, .quick-links > a';
const REDUCED_QUERY = '(prefers-reduced-motion: reduce)';
const PRESS_SCALE = '--motion-press-scale';

interface PressState {
  element: HTMLElement;
  spring: CriticallyDampedSpring;
  pressed: boolean;
  animating: boolean;
  marker: string | null;
  scale: string;
  priority: string;
}

function isPressable(element: Element): element is HTMLElement {
  if (!element || typeof element.matches !== 'function' || !('style' in element)) return false;
  if (!element.matches(PRESSABLE)) return false;
  if (element.tagName === 'SUMMARY' || /^(INPUT|SELECT|TEXTAREA)$/.test(element.tagName)) return false;
  if (element.tagName === 'A' && !element.hasAttribute('href')) return false;
  return !((element as HTMLButtonElement).disabled || element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true');
}

function pressableFrom(target: EventTarget | null): HTMLElement | null {
  if (!target || typeof target !== 'object' || !('closest' in target)) return null;
  const element = (target as Element).closest(PRESSABLE);
  return element && isPressable(element) ? element : null;
}

export function installPressMotion(document: Document, options: PressMotionOptions = {}): () => void {
  const view = document.defaultView;
  if (!view) return () => {};

  const query = options.reducedMotionQuery ?? view.matchMedia(REDUCED_QUERY);
  const root = document.documentElement;
  const originalReduced = root.getAttribute('data-motion-reduced');
  const active = new Map<HTMLElement, PressState>();
  const pointerTargets = new Map<number, HTMLElement>();
  const keyboardTargets = new Map<string, HTMLElement>();
  let frameId: number | undefined;
  let observer: MutationObserver | undefined;
  let disposed = false;

  const stopObserverIfIdle = () => {
    if (active.size || !observer) return;
    observer.disconnect();
    observer = undefined;
  };

  const restore = (state: PressState) => {
    if (state.marker === null) state.element.removeAttribute('data-motion-press');
    else state.element.setAttribute('data-motion-press', state.marker);
    if (state.scale) state.element.style.setProperty(PRESS_SCALE, state.scale, state.priority);
    else state.element.style.removeProperty(PRESS_SCALE);
    active.delete(state.element);
    for (const [pointerId, element] of pointerTargets) if (element === state.element) pointerTargets.delete(pointerId);
    for (const [key, element] of keyboardTargets) if (element === state.element) keyboardTargets.delete(key);
    stopObserverIfIdle();
  };

  const cancelFrame = () => {
    if (frameId !== undefined) view.cancelAnimationFrame(frameId);
    frameId = undefined;
  };

  const schedule = () => {
    if (frameId === undefined && [...active.values()].some((state) => state.animating) && !query.matches && !disposed) {
      frameId = view.requestAnimationFrame(renderFrame);
    }
  };

  const renderFrame = (time: number) => {
    frameId = undefined;
    for (const [element, state] of active) {
      if (!element.isConnected || !isPressable(element)) {
        restore(state);
        continue;
      }
      const sample = state.spring.sample(time);
      element.style.setProperty(PRESS_SCALE, String(sample.value), state.priority);
      if (sample.settled) {
        state.animating = false;
        if (!state.pressed) restore(state);
      }
    }
    schedule();
  };

  const cancelAll = () => {
    cancelFrame();
    for (const state of [...active.values()]) restore(state);
    pointerTargets.clear();
    keyboardTargets.clear();
  };

  const markReducedMotion = (reduced: boolean) => {
    root.setAttribute('data-motion-reduced', String(reduced));
    if (reduced) cancelAll();
  };

  const begin = (element: HTMLElement, time: number) => {
    if (query.matches || !isPressable(element)) return;
    let state = active.get(element);
    if (!state) {
      const computedScale = Number.parseFloat(view.getComputedStyle(element).getPropertyValue(PRESS_SCALE));
      const initialScale = Number.isFinite(computedScale) ? computedScale : 1;
      state = {
        element,
        spring: createCriticallyDampedSpring(initialScale, 0.98),
        pressed: true,
        animating: true,
        marker: element.getAttribute('data-motion-press'),
        scale: element.style.getPropertyValue(PRESS_SCALE),
        priority: element.style.getPropertyPriority(PRESS_SCALE),
      };
      active.set(element, state);
      ensureObserver();
      element.setAttribute('data-motion-press', 'true');
    }
    const sample = state.spring.retarget(0.98, time);
    state.pressed = true;
    state.animating = !sample.settled;
    schedule();
  };

  const release = (element: HTMLElement, time: number, immediate = false) => {
    const state = active.get(element);
    if (!state) return;
    if (immediate || query.matches || !element.isConnected || !isPressable(element)) {
      restore(state);
      if (!active.size) cancelFrame();
      return;
    }
    state.pressed = false;
    const sample = state.spring.retarget(1, time);
    state.animating = !sample.settled;
    if (sample.settled) {
      restore(state);
      return;
    }
    schedule();
  };

  const onPointerDown = (event: PointerEvent) => {
    if (query.matches || event.button !== 0 || event.isPrimary === false) return;
    const element = pressableFrom(event.target);
    if (!element) return;
    pointerTargets.set(event.pointerId, element);
    begin(element, event.timeStamp);
  };
  const onPointerMove = (event: PointerEvent) => {
    const element = pointerTargets.get(event.pointerId);
    if (!element) return;
    if (!event.target || typeof event.target !== 'object' || !('nodeType' in event.target) || !element.contains(event.target as Node)) {
      pointerTargets.delete(event.pointerId);
      release(element, event.timeStamp, true);
    }
  };
  const onPointerUp = (event: PointerEvent) => {
    const element = pointerTargets.get(event.pointerId);
    if (!element) return;
    pointerTargets.delete(event.pointerId);
    release(element, event.timeStamp);
  };
  const onPointerCancel = (event: PointerEvent) => {
    const element = pointerTargets.get(event.pointerId);
    if (!element) return;
    pointerTargets.delete(event.pointerId);
    release(element, event.timeStamp, true);
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (query.matches) return;
    if (event.key === 'Escape') {
      const element = pressableFrom(event.target);
      if (element) release(element, event.timeStamp, true);
      return;
    }
    if (event.repeat || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.isComposing) return;
    if (event.key !== 'Enter' && event.key !== ' ') return;
    const element = pressableFrom(event.target);
    if (!element) return;
    if (event.key === ' ' && element.tagName !== 'BUTTON') return;
    const identity = event.key;
    if (keyboardTargets.has(identity)) return;
    keyboardTargets.set(identity, element);
    begin(element, event.timeStamp);
  };
  const onKeyUp = (event: KeyboardEvent) => {
    const identity = event.key;
    const element = keyboardTargets.get(identity);
    if (!element || (event.key !== 'Enter' && event.key !== ' ')) return;
    keyboardTargets.delete(identity);
    release(element, event.timeStamp);
  };
  const onBlur = () => cancelAll();
  const onVisibilityChange = () => { if (document.hidden) cancelAll(); };
  const onFocusOut = (event: FocusEvent) => {
    const target = event.target as Element | null;
    const element = target && typeof target.closest === 'function' ? target.closest(PRESSABLE) : null;
    if (element && isPressable(element)) release(element, event.timeStamp);
  };

  const onReducedChange = (event: MediaQueryListEvent) => markReducedMotion(event.matches);
  const ensureObserver = () => {
    if (observer || disposed) return;
    observer = new view.MutationObserver((records) => {
      if (!records.some((record) => record.type === 'childList' || record.type === 'attributes')) return;
      for (const state of [...active.values()]) {
        if (!state.element.isConnected || !isPressable(state.element)) release(state.element, view.performance.now(), true);
      }
    });
    observer.observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeFilter: ['disabled', 'aria-disabled', 'class', 'href', 'type'] });
  };

  document.addEventListener('pointerdown', onPointerDown, true);
  document.addEventListener('pointermove', onPointerMove, true);
  document.addEventListener('pointerup', onPointerUp, true);
  document.addEventListener('pointercancel', onPointerCancel, true);
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('keyup', onKeyUp, true);
  document.addEventListener('focusout', onFocusOut, true);
  document.addEventListener('visibilitychange', onVisibilityChange);
  view.addEventListener('blur', onBlur);
  query.addEventListener('change', onReducedChange);
  markReducedMotion(query.matches);

  return () => {
    if (disposed) return;
    disposed = true;
    cancelAll();
    observer?.disconnect();
    observer = undefined;
    document.removeEventListener('pointerdown', onPointerDown, true);
    document.removeEventListener('pointermove', onPointerMove, true);
    document.removeEventListener('pointerup', onPointerUp, true);
    document.removeEventListener('pointercancel', onPointerCancel, true);
    document.removeEventListener('keydown', onKeyDown, true);
    document.removeEventListener('keyup', onKeyUp, true);
    document.removeEventListener('focusout', onFocusOut, true);
    document.removeEventListener('visibilitychange', onVisibilityChange);
    view.removeEventListener('blur', onBlur);
    query.removeEventListener('change', onReducedChange);
    if (originalReduced === null) root.removeAttribute('data-motion-reduced');
    else root.setAttribute('data-motion-reduced', originalReduced);
  };
}
