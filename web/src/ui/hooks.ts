import { useEffect, useState } from 'preact/hooks';
import type { TimePoint } from '../core/timer';
import { store } from '../data/runtime';

/**
 * Re-renders at the instants `nextDelay` asks for. A pure recomputation each time, like the
 * Android tickers: nothing is counted, so a skipped tick can't lose time.
 * `nextDelay` returns Infinity to stop ticking until something else re-renders.
 */
export function useNow(nextDelay: (now: TimePoint) => number, deps: unknown[] = []): TimePoint {
  const [now, setNow] = useState(() => store.time());
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;
    const schedule = (at: TimePoint) => {
      const delay = nextDelay(at);
      if (!Number.isFinite(delay)) return;
      timer = setTimeout(() => {
        if (cancelled) return;
        const fresh = store.time();
        setNow(fresh);
        schedule(fresh);
      }, Math.max(delay, 16));
    };
    const fresh = store.time();
    setNow(fresh);
    schedule(fresh);
    const onVisible = () => {
      if (document.visibilityState !== 'visible') return;
      clearTimeout(timer);
      const t = store.time();
      setNow(t);
      schedule(t);
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      cancelled = true;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, deps);
  return now;
}

/** Ticks every `ms` while `active`, otherwise renders once. */
export function useInterval(ms: number, active: boolean): TimePoint {
  return useNow(() => (active ? ms : Number.POSITIVE_INFINITY), [ms, active]);
}
