import assert from 'node:assert/strict';
import test from 'node:test';
import { createCriticallyDampedSpring } from './motion/spring.ts';
import { installPressMotion } from './motion/press-motion.ts';

test('retargeting preserves the sampled position and velocity', () => {
  const spring = createCriticallyDampedSpring(1, 0.98);
  spring.sample(0);
  const moving = spring.sample(8);
  assert.ok(moving.velocity < 0);

  spring.retarget(1, 8);
  const retargeted = spring.sample(8);
  assert.equal(retargeted.value, moving.value);
  assert.equal(retargeted.velocity, moving.velocity);
  assert.ok(retargeted.value < 1);
});

test('a critically damped spring settles without overshoot and stops requesting frames', () => {
  const spring = createCriticallyDampedSpring(1, 0.98);
  let latest = spring.sample(0);
  for (let time = 8; time <= 1200 && !latest.settled; time += 8) latest = spring.sample(time);

  assert.equal(latest.settled, true);
  assert.ok(latest.value >= 0.98);
  assert.ok(latest.value <= 1);
  assert.equal(spring.sample(1208).settled, true);
});

class FakeStyle {
  values = new Map<string, string>();
  priorities = new Map<string, string>();
  getPropertyValue(name: string) { return this.values.get(name) ?? ''; }
  getPropertyPriority(name: string) { return this.priorities.get(name) ?? ''; }
  setProperty(name: string, value: string, priority = '') { this.values.set(name, value); this.priorities.set(name, priority); }
  removeProperty(name: string) { this.values.delete(name); this.priorities.delete(name); }
}

class FakeElement {
  tagName: string;
  isConnected = true;
  disabled = false;
  style = new FakeStyle();
  attributes = new Map<string, string>();
  listeners = new Map<string, ((event: any) => void)[]>();
  classes: Set<string>;
  synthesizedClicks = 0;
  constructor(tagName = 'BUTTON', classes = new Set<string>()) { this.tagName = tagName; this.classes = classes; }
  matches(selector: string) {
    return selector.split(',').some((part) => {
      const value = part.trim();
      if (value === 'button') return this.tagName === 'BUTTON';
      if (value === 'a') return this.tagName === 'A';
      if (value === 'a.button') return this.tagName === 'A' && this.classes.has('button');
      if (value === 'a.text-button') return this.tagName === 'A' && this.classes.has('text-button');
      if (value === 'a.nav-link') return this.tagName === 'A' && this.classes.has('nav-link');
      if (value === '.button-secondary') return this.classes.has('button-secondary');
      if (value === '.secondary') return this.classes.has('secondary');
      if (value === '.quiet-link') return this.classes.has('quiet-link');
      if (value === '.inline-link') return this.classes.has('inline-link');
      if (value === 'button:disabled') return this.tagName === 'BUTTON' && this.disabled;
      if (value === 'input,select,textarea') return ['INPUT', 'SELECT', 'TEXTAREA'].includes(this.tagName);
      if (value === '.button') return this.classes.has('button');
      if (value === '.text-button') return this.classes.has('text-button');
      if (value === '.nav-link') return this.classes.has('nav-link');
      if (value === '.quick-links > a') return this.tagName === 'A' && this.classes.has('quick-link');
      return false;
    });
  }
  closest(selector: string) { return this.matches(selector) ? this : null; }
  contains(target: unknown) { return target === this; }
  getAttribute(name: string) { return this.attributes.get(name) ?? null; }
  hasAttribute(name: string) { return this.attributes.has(name) || (name === 'disabled' && this.disabled); }
  setAttribute(name: string, value: string) { this.attributes.set(name, value); }
  removeAttribute(name: string) { this.attributes.delete(name); }
  addEventListener(name: string, listener: (event: any) => void) {
    this.listeners.set(name, [...(this.listeners.get(name) ?? []), listener]);
  }
  click() { this.synthesizedClicks += 1; }
}

function runtimeHarness(initialReduced = false) {
  const documentListeners = new Map<string, ((event: any) => void)[]>();
  const windowListeners = new Map<string, ((event: any) => void)[]>();
  const pendingFrames = new Map<number, (time: number) => void>();
  let nextFrame = 1;
  let observerCallback: MutationCallback | undefined;
  let mediaChange: ((event: any) => void) | undefined;
  const observerStats = { created: 0, observed: 0, disconnected: 0, options: undefined as MutationObserverInit | undefined };
  const html = new FakeElement('HTML');
  const mediaQuery = {
    matches: initialReduced,
    addEventListener: (_name: string, listener: (event: any) => void) => { mediaChange = listener; },
    removeEventListener: () => { mediaChange = undefined; },
  };
  class Observer {
    constructor(callback: MutationCallback) { observerStats.created += 1; observerCallback = callback; }
    observe(_target: Node, options: MutationObserverInit) { observerStats.observed += 1; observerStats.options = options; }
    disconnect() { observerStats.disconnected += 1; observerCallback = undefined; }
  }
  const view = {
    matchMedia: () => mediaQuery,
    requestAnimationFrame: (callback: (time: number) => void) => { const id = nextFrame++; pendingFrames.set(id, callback); return id; },
    cancelAnimationFrame: (id: number) => { pendingFrames.delete(id); },
    MutationObserver: Observer,
    performance: { now: () => 0 },
    getComputedStyle: (element: FakeElement) => ({ getPropertyValue: (name: string) => element.style.getPropertyValue(name) || '1' }),
    addEventListener: (name: string, listener: (event: any) => void) => { windowListeners.set(name, [...(windowListeners.get(name) ?? []), listener]); },
    removeEventListener: (name: string) => { windowListeners.delete(name); },
  };
  const documentLike = {
    defaultView: view,
    documentElement: html,
    hidden: false,
    addEventListener: (name: string, listener: (event: any) => void) => { documentListeners.set(name, [...(documentListeners.get(name) ?? []), listener]); },
    removeEventListener: (name: string) => { documentListeners.delete(name); },
  };
  const fire = (listeners: Map<string, ((event: any) => void)[]>, name: string, event: any = {}) => {
    const populated = { timeStamp: 0, ...event };
    for (const listener of listeners.get(name) ?? []) listener(populated);
  };
  return {
    document: documentLike as unknown as Document,
    html,
    mediaQuery,
    fireDocument: fire.bind(null, documentListeners),
    fireWindow: fire.bind(null, windowListeners),
    changeReduced(value: boolean) { mediaQuery.matches = value; mediaChange?.({ matches: value }); },
    tick(time: number) {
      const callbacks = [...pendingFrames.values()]; pendingFrames.clear(); callbacks.forEach((callback) => callback(time));
    },
    pendingFrames,
    observerStats,
    mutate(records: MutationRecord[]) { observerCallback?.(records, {} as MutationObserver); },
    documentListeners,
    windowListeners,
  };
}

test('pointer cancellation restores preexisting marker and scale without synthesizing a click', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  button.setAttribute('data-motion-press', 'original');
  button.style.setProperty('--motion-press-scale', '0.97', 'important');
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 3, isPrimary: true });
  assert.equal(button.getAttribute('data-motion-press'), 'true');
  h.fireDocument('pointercancel', { target: button, pointerId: 3 });
  assert.equal(button.synthesizedClicks, 0);
  assert.equal(button.getAttribute('data-motion-press'), 'original');
  assert.equal(button.style.getPropertyValue('--motion-press-scale'), '0.97');
  assert.equal(button.style.getPropertyPriority('--motion-press-scale'), 'important');
  cleanup();
});

test('secondary business actions receive the same press feedback', () => {
  const h = runtimeHarness();
  const button = new FakeElement('BUTTON', new Set(['button-secondary']));
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 8, isPrimary: true });
  assert.equal(button.getAttribute('data-motion-press'), 'true');
  cleanup();
});

test('secondary pointer input is ignored without canceling or stopping the native event', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  let prevented = 0;
  let stopped = 0;
  h.fireDocument('pointerdown', {
    target: button, button: 2, pointerId: 16, isPrimary: true,
    preventDefault() { prevented += 1; }, stopPropagation() { stopped += 1; },
  });
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 17, isPrimary: false });
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(prevented, 0);
  assert.equal(stopped, 0);
  cleanup();
});

test('press spring starts from the current rendered scale without a first-frame jump', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const preScaled = new FakeElement();
  preScaled.style.setProperty('--motion-press-scale', '0.97', 'important');
  const cleanup = installPressMotion(h.document);

  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 9, isPrimary: true });
  assert.equal(button.style.getPropertyValue('--motion-press-scale'), '');
  h.tick(16);
  const firstScale = Number(button.style.getPropertyValue('--motion-press-scale'));
  assert.ok(firstScale < 1 && firstScale > 0.98);

  h.fireDocument('pointerdown', { target: preScaled, button: 0, pointerId: 10, isPrimary: true });
  assert.equal(preScaled.style.getPropertyValue('--motion-press-scale'), '0.97');
  h.tick(32);
  assert.ok(Number(preScaled.style.getPropertyValue('--motion-press-scale')) > 0.97);
  cleanup();
});

test('reduced motion immediately clears active visuals and writes its root state', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 1, isPrimary: true });
  assert.equal(h.pendingFrames.size, 1);

  h.changeReduced(true);
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(h.html.getAttribute('data-motion-reduced'), 'true');
  assert.equal(h.pendingFrames.size, 0);
  h.changeReduced(false);
  assert.equal(h.html.getAttribute('data-motion-reduced'), 'false');
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 15, isPrimary: true });
  assert.equal(button.getAttribute('data-motion-press'), 'true');
  cleanup();
  assert.equal(h.html.getAttribute('data-motion-reduced'), null);
});

test('an injected reduced-motion query overrides the window query and tracks preference changes', () => {
  const h = runtimeHarness(false);
  const button = new FakeElement();
  let listener: ((event: MediaQueryListEvent) => void) | undefined;
  const injectedQuery = {
    matches: true,
    addEventListener(_type: 'change', callback: (event: MediaQueryListEvent) => void) { listener = callback; },
    removeEventListener() { listener = undefined; },
  };
  const cleanup = installPressMotion(h.document, { reducedMotionQuery: injectedQuery });

  assert.equal(h.html.getAttribute('data-motion-reduced'), 'true');
  assert.equal(h.pendingFrames.size, 0);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 21, isPrimary: true });
  assert.equal(button.getAttribute('data-motion-press'), null);

  injectedQuery.matches = false;
  listener?.({ matches: false } as MediaQueryListEvent);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 22, isPrimary: true });
  assert.equal(button.getAttribute('data-motion-press'), 'true');
  assert.equal(h.pendingFrames.size, 1);

  injectedQuery.matches = true;
  listener?.({ matches: true } as MediaQueryListEvent);
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(h.pendingFrames.size, 0);
  cleanup();
});

test('reduced-motion input is not tracked and does not start a mutation observer', () => {
  const h = runtimeHarness(true);
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 11, isPrimary: true });
  h.fireDocument('keydown', { target: button, key: 'Enter' });
  assert.equal(h.observerStats.created, 0);
  assert.equal(h.pendingFrames.size, 0);
  assert.equal(button.getAttribute('data-motion-press'), null);
  cleanup();
});

test('pointer release settles to the original style and leaves no animation frame queued', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 4, isPrimary: true });
  h.tick(16);
  const pressedScale = Number(button.style.getPropertyValue('--motion-press-scale'));
  assert.ok(pressedScale < 1);

  h.fireDocument('pointerup', { target: button, pointerId: 4, timeStamp: 16 });
  for (let time = 24; time <= 800 && h.pendingFrames.size; time += 8) h.tick(time);
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(button.style.getPropertyValue('--motion-press-scale'), '');
  assert.equal(h.pendingFrames.size, 0);
  cleanup();
});

test('a held press stays compressed after settling without spending frames until release', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 5, isPrimary: true });
  for (let time = 8; time <= 800 && h.pendingFrames.size; time += 8) h.tick(time);

  assert.equal(button.getAttribute('data-motion-press'), 'true');
  assert.equal(Number(button.style.getPropertyValue('--motion-press-scale')), 0.98);
  assert.equal(h.pendingFrames.size, 0);
  h.fireDocument('pointerup', { target: button, pointerId: 5, timeStamp: 800 });
  assert.equal(h.pendingFrames.size, 1);
  cleanup();
});

test('the observer stays connected for a settled held press and prunes a detached control', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  assert.equal(h.observerStats.created, 0);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 12, isPrimary: true });
  assert.equal(h.observerStats.observed, 1);
  assert.deepEqual(h.observerStats.options?.attributeFilter, ['disabled', 'aria-disabled', 'class', 'href', 'type']);
  for (let time = 8; time <= 800 && h.pendingFrames.size; time += 8) h.tick(time);
  assert.equal(h.pendingFrames.size, 0);
  assert.equal(button.getAttribute('data-motion-press'), 'true');

  button.isConnected = false;
  h.mutate([{ type: 'childList', target: {} as Node } as MutationRecord]);
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(h.observerStats.disconnected, 1);
  cleanup();
});

test('blur and hidden-document changes cancel the active press and observer', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 13, isPrimary: true });
  h.fireWindow('blur');
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(h.pendingFrames.size, 0);
  assert.equal(h.observerStats.disconnected, 1);

  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 14, isPrimary: true });
  (h.document as unknown as { hidden: boolean }).hidden = true;
  h.fireDocument('visibilitychange');
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(h.pendingFrames.size, 0);
  assert.equal(h.observerStats.disconnected, 2);
  cleanup();
});

test('keyboard repeats and modified activation do not start press motion', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('keydown', { target: button, key: 'Enter', repeat: true });
  h.fireDocument('keydown', { target: button, key: ' ', repeat: false, ctrlKey: true });
  assert.equal(button.getAttribute('data-motion-press'), null);
  h.fireDocument('keydown', { target: button, key: 'Enter', repeat: false });
  assert.equal(button.getAttribute('data-motion-press'), 'true');
  h.fireDocument('keydown', { target: button, key: 'Enter', repeat: true });
  assert.equal(h.pendingFrames.size, 1);
  h.fireDocument('keyup', { target: button, key: 'Enter' });
  cleanup();
});

test('Space starts native button feedback but not anchor feedback', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const link = new FakeElement('A', new Set(['nav-link']));
  link.setAttribute('href', '/home');
  const cleanup = installPressMotion(h.document);

  h.fireDocument('keydown', { target: link, key: ' ' });
  assert.equal(link.getAttribute('data-motion-press'), null);
  h.fireDocument('keydown', { target: button, key: ' ' });
  assert.equal(button.getAttribute('data-motion-press'), 'true');
  h.fireDocument('keydown', { target: button, key: 'Escape' });
  assert.equal(button.getAttribute('data-motion-press'), null);
  cleanup();
});

test('dispose and disabled-control pruning restore attributes and release listeners', () => {
  const h = runtimeHarness();
  const button = new FakeElement();
  const cleanup = installPressMotion(h.document);
  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 1, isPrimary: true });
  button.disabled = true;
  h.mutate([{ type: 'attributes', target: button as unknown as Node, attributeName: 'disabled' } as MutationRecord]);
  assert.equal(button.getAttribute('data-motion-press'), null);

  h.fireDocument('pointerdown', { target: button, button: 0, pointerId: 2, isPrimary: true });
  cleanup();
  assert.equal(button.getAttribute('data-motion-press'), null);
  assert.equal(h.pendingFrames.size, 0);
  assert.equal(h.documentListeners.size, 0);
  assert.equal(h.windowListeners.size, 0);
});
