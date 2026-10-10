export interface SpringSample {
  value: number;
  velocity: number;
  settled: boolean;
}

export interface CriticallyDampedSpring {
  sample(time: number): SpringSample;
  retarget(target: number, time: number): SpringSample;
}

export function createCriticallyDampedSpring(
  initialValue: number,
  initialTarget: number,
  angularFrequency = 28,
): CriticallyDampedSpring {
  let value = initialValue;
  let velocity = 0;
  let target = initialTarget;
  let lastTime: number | undefined;
  let settled = Math.abs(value - target) < 0.0005;

  const snapshot = (): SpringSample => ({ value, velocity, settled });

  return {
    sample(time) {
      if (lastTime === undefined) {
        lastTime = time;
        return snapshot();
      }
      const elapsed = Math.min(Math.max((time - lastTime) / 1000, 0), 0.064);
      lastTime = time;
      if (settled || elapsed === 0) return snapshot();

      const displacement = value - target;
      const velocityOffset = velocity + angularFrequency * displacement;
      const decay = Math.exp(-angularFrequency * elapsed);
      value = target + (displacement + velocityOffset * elapsed) * decay;
      velocity = (velocity - angularFrequency * velocityOffset * elapsed) * decay;
      settled = Math.abs(value - target) < 0.0005 && Math.abs(velocity) < 0.005;
      if (settled) {
        value = target;
        velocity = 0;
      }
      return snapshot();
    },
    retarget(nextTarget, time) {
      const current = this.sample(time);
      target = nextTarget;
      settled = Math.abs(value - target) < 0.0005 && Math.abs(velocity) < 0.005;
      return { ...current, settled };
    },
  };
}
