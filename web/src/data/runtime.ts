/**
 * The browser side of the timer: what Android's foreground service, alarms and receivers do,
 * done with the tools a web page has.
 *
 * - Nothing needs to keep counting: the session stores its start time, so a closed app or a
 *   locked iPhone loses nothing. Opening Lock In again reconciles with the clock.
 * - While the page is open, one timeout is kept for the next Pomodoro boundary (like the
 *   Android wake-up alarm), and a chime plays when it fires.
 * - Optional: the screen stays on during a session (Screen Wake Lock API).
 */
import { effect } from '@preact/signals';
import { nextBoundaryElapsedMs, readoutAt, webTimeSource, Commands } from '../core/timer';
import { isUrlLink } from '../core/model';
import { createStore, MemoryStorage, type LockInStore } from './store';

function pickStorage(): Storage {
  try {
    const probe = '__lockin_probe__';
    window.localStorage.setItem(probe, '1');
    window.localStorage.removeItem(probe);
    return window.localStorage;
  } catch {
    return new MemoryStorage();
  }
}

export const store: LockInStore = createStore(pickStorage(), webTimeSource);

/** True when Lock In was opened from the Home Screen (iOS) or installed (other browsers). */
export const isStandalone = (): boolean =>
  (typeof navigator !== 'undefined' && (navigator as Navigator & { standalone?: boolean }).standalone === true) ||
  (typeof window !== 'undefined' && window.matchMedia?.('(display-mode: standalone)').matches);

export const isIOS = (): boolean =>
  /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

// region Audio

let audio: AudioContext | null = null;

/** iOS only plays sound from an AudioContext that was started by a tap. Call from tap handlers. */
export function unlockAudio() {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    audio ??= new Ctx();
    if (audio.state === 'suspended') void audio.resume();
  } catch {
    audio = null;
  }
}

export function chime() {
  if (!audio || audio.state !== 'running') return;
  const ctx = audio;
  const start = ctx.currentTime + 0.02;
  [660, 880, 990].forEach((freq, i) => {
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.value = freq;
    const t = start + i * 0.18;
    gain.gain.setValueAtTime(0.0001, t);
    gain.gain.exponentialRampToValueAtTime(0.25, t + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, t + 0.6);
    osc.connect(gain).connect(ctx.destination);
    osc.start(t);
    osc.stop(t + 0.65);
  });
}

// endregion

// region Shortcuts

/**
 * Runs an iPhone Shortcut by name, for example one that turns on a "Study" Focus. iOS opens the
 * Shortcuts app to run it; the user comes back with the "Lock In" back button in the corner.
 */
export function runShortcut(name: string) {
  const trimmed = name.trim();
  if (!trimmed) return;
  window.location.href = `shortcuts://run-shortcut?name=${encodeURIComponent(trimmed)}`;
}

/**
 * Opens an app the user allows during focus: an app link (spotify://) or website opens directly,
 * anything else is run as a Shortcut name (for apps without a link: Shortcuts → Open App).
 */
export function openAllowedApp(link: string) {
  const l = link.trim();
  if (!l) return;
  if (!isUrlLink(l)) {
    runShortcut(l);
  } else if (/^https?:/i.test(l)) {
    window.open(l, '_blank', 'noopener');
  } else {
    window.location.href = l;
  }
}

// endregion

// region Wake lock

type WakeLockSentinelLike = { release(): Promise<void>; released: boolean; addEventListener(t: 'release', l: () => void): void };
let wakeLock: WakeLockSentinelLike | null = null;
let wakeLockWanted = false;

async function syncWakeLock() {
  const nav = navigator as Navigator & { wakeLock?: { request(type: 'screen'): Promise<WakeLockSentinelLike> } };
  if (!nav.wakeLock) return;
  try {
    if (wakeLockWanted && document.visibilityState === 'visible' && (!wakeLock || wakeLock.released)) {
      wakeLock = await nav.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => {
        wakeLock = null;
      });
    } else if (!wakeLockWanted && wakeLock) {
      await wakeLock.release();
      wakeLock = null;
    }
  } catch {
    wakeLock = null;
  }
}

export const wakeLockSupported = (): boolean => typeof navigator !== 'undefined' && 'wakeLock' in navigator;

// endregion

let started = false;

/** Restores the session and wires timers, visibility and storage events. Idempotent. */
export function startRuntime() {
  if (started) return;
  started = true;
  store.restore();

  // The one wake-up for the next Pomodoro boundary, re-derived from state after every change.
  let boundaryTimer: ReturnType<typeof setTimeout> | undefined;
  effect(() => {
    const at = nextBoundaryElapsedMs(store.timer.value);
    clearTimeout(boundaryTimer);
    if (at === null) return;
    const delay = Math.max(at - store.time().elapsedMs, 0) + 50;
    // setTimeout clamps long delays; re-check at least every 10 minutes.
    boundaryTimer = setTimeout(() => store.dispatch(Commands.reconcile), Math.min(delay, 10 * 60_000));
  });

  // Heartbeat while a session is active, so a clock moved backwards pauses at the last checkpoint.
  setInterval(() => {
    if (store.timer.value.kind === 'active' && document.visibilityState === 'visible') store.dispatch(Commands.heartbeat);
    store.minuteNow.value = Date.now();
  }, 30_000);

  const onVisible = () => {
    store.minuteNow.value = Date.now();
    if (document.visibilityState === 'visible') {
      store.dispatch(Commands.reconcile);
    } else if (store.timer.value.kind === 'active') {
      store.dispatch(Commands.heartbeat);
    }
    void syncWakeLock();
  };
  document.addEventListener('visibilitychange', onVisible);
  window.addEventListener('pageshow', onVisible);
  window.addEventListener('pagehide', () => {
    if (store.timer.value.kind === 'active') store.dispatch(Commands.heartbeat);
  });

  // Another tab (or the installed app and a Safari tab) changed something.
  window.addEventListener('storage', (e) => {
    if (e.key === null || e.key.startsWith('lockin.')) store.reloadFromStorage(e.key);
  });

  // Keep the screen on while a session runs, if the user asked for it.
  effect(() => {
    const s = store.timer.value;
    const r = readoutAt(s, store.time());
    wakeLockWanted = store.prefs.value.keepScreenOn && r !== null && r.isRunning;
    void syncWakeLock();
  });

  // Chime for boundaries that passed while the page was open.
  effect(() => {
    const list = store.announcements.value;
    const last = list[list.length - 1];
    if (last?.kind === 'phase' && last.fresh && store.prefs.peek().chime) chime();
  });
}

/** Asks the browser not to evict Lock In's data under storage pressure. */
export async function requestPersistentStorage(): Promise<boolean> {
  try {
    return (await navigator.storage?.persist?.()) ?? false;
  } catch {
    return false;
  }
}
