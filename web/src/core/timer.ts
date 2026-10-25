/**
 * The timer's functional core, ported from `core/timer` (Kotlin). Same rules, same tests:
 * `reduce(state, command, now) -> { state, effects }` is pure, never reads a clock, and a running
 * session stores only *when* it started, so a closed tab or a sleeping iPhone loses nothing.
 */

// region Clocks

/**
 * One reading of the clocks the timer cares about, taken at the same instant.
 * On the web both readings come from `Date.now()` (see `webTimeSource`), and `bootCount` is
 * unknown; the engine is kept identical to Android so behaviour and tests match.
 */
export interface TimePoint {
  readonly elapsedMs: number;
  readonly wallMs: number;
  readonly bootCount: number;
}

export const UNKNOWN_BOOT = -1;
export const CLOCK_DRIFT_TOLERANCE_MS = 5 * 60 * 1000;

export function shiftedBy(p: TimePoint, deltaMs: number): TimePoint {
  return { elapsedMs: p.elapsedMs + deltaMs, wallMs: p.wallMs + deltaMs, bootCount: p.bootCount };
}

/** True when `earlier` belongs to the same run of the monotonic clock as `now`. */
export function isSameBootAs(now: TimePoint, earlier: TimePoint): boolean {
  if (now.elapsedMs < earlier.elapsedMs) return false;
  if (now.bootCount !== UNKNOWN_BOOT && earlier.bootCount !== UNKNOWN_BOOT) {
    return now.bootCount === earlier.bootCount;
  }
  const drift = now.wallMs - earlier.wallMs - (now.elapsedMs - earlier.elapsedMs);
  return Math.abs(drift) <= CLOCK_DRIFT_TOLERANCE_MS;
}

export type TimeSource = () => TimePoint;

/**
 * Browsers have no monotonic clock that survives a closed tab, so the web build measures with
 * the wall clock. iPhones keep it in sync automatically; a clock moved backwards is treated like
 * a restart (the session pauses at its last checkpoint) instead of losing or inventing time.
 */
export const webTimeSource: TimeSource = () => {
  const now = Date.now();
  return { elapsedMs: now, wallMs: now, bootCount: UNKNOWN_BOOT };
};

// endregion

// region Model

export interface SubjectRef {
  readonly id: number | null;
  readonly name: string;
}

export interface StopwatchMode {
  readonly kind: 'stopwatch';
}

export interface PomodoroMode {
  readonly kind: 'pomodoro';
  readonly focusMs: number;
  readonly shortBreakMs: number;
  readonly longBreakMs: number;
  readonly longBreakEvery: number;
  readonly autoStartBreaks: boolean;
  readonly autoStartFocus: boolean;
}

export type TimerMode = StopwatchMode | PomodoroMode;

export const MIN_PHASE_MS = 60_000;
export const STOPWATCH: StopwatchMode = { kind: 'stopwatch' };

export function pomodoro(p: Omit<PomodoroMode, 'kind' | 'longBreakEvery' | 'autoStartBreaks' | 'autoStartFocus'> & Partial<PomodoroMode>): PomodoroMode {
  const mode: PomodoroMode = {
    kind: 'pomodoro',
    focusMs: p.focusMs,
    shortBreakMs: p.shortBreakMs,
    longBreakMs: p.longBreakMs,
    longBreakEvery: p.longBreakEvery ?? 4,
    autoStartBreaks: p.autoStartBreaks ?? true,
    autoStartFocus: p.autoStartFocus ?? false,
  };
  if (mode.focusMs < MIN_PHASE_MS || mode.shortBreakMs < MIN_PHASE_MS || mode.longBreakMs < MIN_PHASE_MS) {
    throw new Error(`Pomodoro phases must be at least ${MIN_PHASE_MS} ms`);
  }
  if (mode.longBreakEvery < 1) throw new Error('longBreakEvery must be at least 1');
  return mode;
}

export type Phase = 'FOCUS' | 'SHORT_BREAK' | 'LONG_BREAK';

export function durationOf(mode: PomodoroMode, phase: Phase): number {
  switch (phase) {
    case 'FOCUS':
      return mode.focusMs;
    case 'SHORT_BREAK':
      return mode.shortBreakMs;
    case 'LONG_BREAK':
      return mode.longBreakMs;
  }
}

/** The break that follows focus round number `round` (1-based). */
export function breakAfterRound(mode: PomodoroMode, round: number): Phase {
  return round % mode.longBreakEvery === 0 ? 'LONG_BREAK' : 'SHORT_BREAK';
}

export type PauseReason = 'USER' | 'AWAITING_NEXT_PHASE' | 'INTERRUPTED';

export type RunState =
  | { readonly kind: 'running'; readonly since: TimePoint }
  | { readonly kind: 'paused'; readonly reason: PauseReason; readonly atWallMs: number };

export interface IdleState {
  readonly kind: 'idle';
}

/**
 * A session in progress. No tick counter: the live total is always
 * `focusBankedMs + (now - since)`, so a closed app or a late timer cannot lose time.
 */
export interface ActiveState {
  readonly kind: 'active';
  readonly sessionId: string;
  readonly subject: SubjectRef;
  readonly mode: TimerMode;
  readonly startedAtWallMs: number;
  readonly phase: Phase;
  /** Focus rounds that ended, completed or skipped. Always 0 for stopwatch sessions. */
  readonly focusRounds: number;
  readonly run: RunState;
  /** Time in the current phase across segments that already closed. */
  readonly phaseBankedMs: number;
  /** Focus time across segments that already closed. */
  readonly focusBankedMs: number;
  /** Index the next focus segment gets. Segments are keyed by (sessionId, index). */
  readonly nextSegmentIndex: number;
  /** Last instant a live page saw this session. */
  readonly lastAlive: TimePoint;
}

export type TimerState = IdleState | ActiveState;
export const IDLE: IdleState = { kind: 'idle' };

export type SegmentEnd = 'PAUSED' | 'PHASE_COMPLETE' | 'SKIPPED' | 'STOPPED' | 'INTERRUPTED';

/** A closed stretch of focus. `endWallMs` is `startWallMs + durationMs` by definition. */
export interface FocusSegment {
  readonly sessionId: string;
  readonly index: number;
  readonly subject: SubjectRef;
  readonly startWallMs: number;
  readonly endWallMs: number;
  readonly durationMs: number;
  readonly endedBy: SegmentEnd;
}

export interface SessionSummary {
  readonly sessionId: string;
  readonly subject: SubjectRef;
  readonly mode: TimerMode;
  readonly startedAtWallMs: number;
  readonly endedAtWallMs: number;
  readonly focusMs: number;
  readonly segmentCount: number;
  readonly focusRounds: number;
}

export type TimerCommand =
  | { readonly type: 'start'; readonly sessionId: string; readonly subject: SubjectRef; readonly mode: TimerMode }
  | { readonly type: 'pause' }
  | { readonly type: 'resume' }
  | { readonly type: 'stop' }
  | { readonly type: 'skip' }
  /** "Look at the clock and catch up." Idempotent. */
  | { readonly type: 'reconcile' }
  /** Records that a live page saw the session just now. */
  | { readonly type: 'heartbeat' };

export const Commands = {
  start: (sessionId: string, subject: SubjectRef, mode: TimerMode): TimerCommand => {
    if (!sessionId.trim()) throw new Error('sessionId must not be blank');
    return { type: 'start', sessionId, subject, mode };
  },
  pause: { type: 'pause' } as TimerCommand,
  resume: { type: 'resume' } as TimerCommand,
  stop: { type: 'stop' } as TimerCommand,
  skip: { type: 'skip' } as TimerCommand,
  reconcile: { type: 'reconcile' } as TimerCommand,
  heartbeat: { type: 'heartbeat' } as TimerCommand,
};

export type TimerEffect =
  | { readonly type: 'recordSegment'; readonly segment: FocusSegment }
  | { readonly type: 'sessionFinished'; readonly summary: SessionSummary }
  /** A boundary was crossed without the user asking; if several passed, only the last is reported. */
  | { readonly type: 'phaseChanged'; readonly from: Phase; readonly to: Phase; readonly nextStartsAutomatically: boolean }
  | { readonly type: 'interrupted'; readonly creditedUntilWallMs: number }
  | { readonly type: 'ignored'; readonly command: TimerCommand; readonly reason: string };

export interface Transition {
  readonly state: TimerState;
  readonly effects: readonly TimerEffect[];
}

// endregion

// region Engine

/** Upper bound on boundaries crossed in one catch-up (about a week of 1-minute phases). */
export const MAX_CATCH_UP_PHASES = 20_000;

export function reduce(state: TimerState, command: TimerCommand, now: TimePoint): Transition {
  const effects: TimerEffect[] = [];
  let next = recoverIfRebooted(state, now, effects);
  next = advance(next, now, effects);
  next = apply(next, command, now, effects);
  if (next.kind === 'active') next = { ...next, lastAlive: now };
  return { state: next, effects };
}

/** `state` as it is at `now`, without effects. Used for display and scheduling. */
export function project(state: TimerState, now: TimePoint): TimerState {
  const discard: TimerEffect[] = [];
  return advance(recoverIfRebooted(state, now, discard), now, discard);
}

function recoverIfRebooted(state: TimerState, now: TimePoint, effects: TimerEffect[]): TimerState {
  if (state.kind !== 'active' || state.run.kind !== 'running') return state;
  const since = state.run.since;
  if (isSameBootAs(now, since)) return state;

  // `since` belongs to another run of the clock, so `now - since` means nothing. Replay the
  // session up to the last instant a live page saw it, then pause there.
  const lastAlive = isSameBootAs(state.lastAlive, since) ? state.lastAlive : since;
  const replayEffects: TimerEffect[] = [];
  let replayed = advanceActive(state, lastAlive, replayEffects);
  if (replayed.run.kind === 'running') {
    replayed = closeSegment(replayed, lastAlive, 'INTERRUPTED', replayEffects, {
      kind: 'paused',
      reason: 'INTERRUPTED',
      atWallMs: lastAlive.wallMs,
    });
  }
  // Keep the durable records from the replay; drop phase announcements that are now stale.
  for (const e of replayEffects) if (e.type === 'recordSegment') effects.push(e);
  effects.push({ type: 'interrupted', creditedUntilWallMs: lastAlive.wallMs });
  return replayed;
}

function advance(state: TimerState, now: TimePoint, effects: TimerEffect[]): TimerState {
  return state.kind === 'active' ? advanceActive(state, now, effects) : state;
}

function advanceActive(state: ActiveState, now: TimePoint, effects: TimerEffect[]): ActiveState {
  if (state.mode.kind !== 'pomodoro') return state;
  const mode = state.mode;
  let current = state;
  let lastChange: TimerEffect | null = null;
  let crossed = 0;
  while (crossed < MAX_CATCH_UP_PHASES) {
    const run = current.run;
    if (run.kind !== 'running') break;
    const remaining = Math.max(durationOf(mode, current.phase) - current.phaseBankedMs, 0);
    const boundary = shiftedBy(run.since, remaining);
    if (now.elapsedMs < boundary.elapsedMs) break;

    const closed = closeSegment(current, boundary, 'PHASE_COMPLETE', effects, run);
    const [entered, change] = enterNextPhase(closed, mode, boundary, false);
    current = entered;
    lastChange = change;
    crossed++;
  }
  if (lastChange) effects.push(lastChange);
  return current;
}

function apply(state: TimerState, command: TimerCommand, now: TimePoint, effects: TimerEffect[]): TimerState {
  switch (command.type) {
    case 'start':
      if (state.kind === 'idle') {
        return {
          kind: 'active',
          sessionId: command.sessionId,
          subject: command.subject,
          mode: command.mode,
          startedAtWallMs: now.wallMs,
          phase: 'FOCUS',
          focusRounds: 0,
          run: { kind: 'running', since: now },
          phaseBankedMs: 0,
          focusBankedMs: 0,
          nextSegmentIndex: 0,
          lastAlive: now,
        };
      }
      return ignore(state, command, 'A session is already active', effects);

    case 'pause':
      return whenActive(state, command, effects, (s) =>
        s.run.kind === 'running'
          ? closeSegment(s, now, 'PAUSED', effects, { kind: 'paused', reason: 'USER', atWallMs: now.wallMs })
          : ignore(s, command, 'Already paused', effects),
      );

    case 'resume':
      return whenActive(state, command, effects, (s) =>
        s.run.kind === 'paused' ? { ...s, run: { kind: 'running', since: now } } : ignore(s, command, 'Already running', effects),
      );

    case 'skip':
      return whenActive(state, command, effects, (s) => {
        if (s.mode.kind !== 'pomodoro') return ignore(s, command, 'Stopwatch sessions have no phases', effects);
        const closed = closeSegment(s, now, 'SKIPPED', effects, s.run);
        return enterNextPhase(closed, s.mode, now, true)[0];
      });

    case 'stop':
      return whenActive(state, command, effects, (s) => {
        const closed = closeSegment(s, now, 'STOPPED', effects, { kind: 'paused', reason: 'USER', atWallMs: now.wallMs });
        effects.push({
          type: 'sessionFinished',
          summary: {
            sessionId: closed.sessionId,
            subject: closed.subject,
            mode: closed.mode,
            startedAtWallMs: closed.startedAtWallMs,
            endedAtWallMs: now.wallMs,
            focusMs: closed.focusBankedMs,
            segmentCount: closed.nextSegmentIndex,
            focusRounds: closed.focusRounds,
          },
        });
        return IDLE;
      });

    // Catch-up already happened; these exist to trigger it (and refresh lastAlive).
    case 'reconcile':
    case 'heartbeat':
      return state;
  }
}

/** Closes the running segment at `at` (if running) and moves to `then`. Banks focus time. */
function closeSegment(state: ActiveState, at: TimePoint, endedBy: SegmentEnd, effects: TimerEffect[], then: RunState): ActiveState {
  if (state.run.kind !== 'running') return { ...state, run: then };
  const since = state.run.since;
  const durationMs = Math.max(at.elapsedMs - since.elapsedMs, 0);
  let next: ActiveState = { ...state, run: then, phaseBankedMs: state.phaseBankedMs + durationMs };
  if (state.phase === 'FOCUS') {
    next = { ...next, focusBankedMs: state.focusBankedMs + durationMs };
    if (durationMs > 0) {
      effects.push({
        type: 'recordSegment',
        segment: {
          sessionId: state.sessionId,
          index: state.nextSegmentIndex,
          subject: state.subject,
          startWallMs: since.wallMs,
          endWallMs: since.wallMs + durationMs,
          durationMs,
          endedBy,
        },
      });
      next = { ...next, nextSegmentIndex: state.nextSegmentIndex + 1 };
    }
  }
  return next;
}

function enterNextPhase(state: ActiveState, mode: PomodoroMode, at: TimePoint, forceStart: boolean): [ActiveState, TimerEffect] {
  const leavingFocus = state.phase === 'FOCUS';
  const rounds = leavingFocus ? state.focusRounds + 1 : state.focusRounds;
  const nextPhase: Phase = leavingFocus ? breakAfterRound(mode, rounds) : 'FOCUS';
  const autoStart = forceStart || (leavingFocus ? mode.autoStartBreaks : mode.autoStartFocus);
  // Starts exactly at the boundary, not "when we noticed", so a late wake-up loses nothing.
  const run: RunState = autoStart ? { kind: 'running', since: at } : { kind: 'paused', reason: 'AWAITING_NEXT_PHASE', atWallMs: at.wallMs };
  return [
    { ...state, phase: nextPhase, focusRounds: rounds, phaseBankedMs: 0, run },
    { type: 'phaseChanged', from: state.phase, to: nextPhase, nextStartsAutomatically: autoStart },
  ];
}

function whenActive(state: TimerState, command: TimerCommand, effects: TimerEffect[], block: (s: ActiveState) => TimerState): TimerState {
  return state.kind === 'idle' ? ignore(state, command, 'No active session', effects) : block(state);
}

function ignore<S extends TimerState>(state: S, command: TimerCommand, reason: string, effects: TimerEffect[]): S {
  effects.push({ type: 'ignored', command, reason });
  return state;
}

// endregion

// region Readout

/** Everything a screen needs to render the timer at one instant. */
export interface TimerReadout {
  readonly phase: Phase;
  readonly isRunning: boolean;
  readonly pauseReason: PauseReason | null;
  /** Focus time in this session, including the stretch running right now. */
  readonly sessionFocusMs: number;
  readonly phaseElapsedMs: number;
  /** Pomodoro only. */
  readonly phaseRemainingMs: number | null;
  readonly focusRounds: number;
}

export function isCountdown(r: TimerReadout): boolean {
  return r.phaseRemainingMs !== null;
}

/** The number on the big clock: time left (Pomodoro) or session focus (stopwatch). */
export function displayMs(r: TimerReadout): number {
  return r.phaseRemainingMs ?? r.sessionFocusMs;
}

/**
 * How long until the displayed second changes, so the clock never skips or repeats a second.
 * Countdowns round up (25:00 shows for the first second); count-ups round down.
 */
export function millisUntilDisplayChanges(r: TimerReadout): number {
  if (!r.isRunning) return Number.POSITIVE_INFINITY;
  const intoSecond = displayMs(r) % 1_000;
  const wait = isCountdown(r) ? (intoSecond > 0 ? intoSecond : 1_000) : 1_000 - intoSecond;
  return Math.min(Math.max(wait, 1), 1_000);
}

/** Reads the session at `now`, crossing any Pomodoro boundaries that passed. Null when idle. */
export function readoutAt(state: TimerState, now: TimePoint): TimerReadout | null {
  const s = project(state, now);
  if (s.kind !== 'active') return null;
  const runningForMs = s.run.kind === 'running' ? Math.max(now.elapsedMs - s.run.since.elapsedMs, 0) : 0;
  const phaseElapsedMs = s.phaseBankedMs + runningForMs;
  const sessionFocusMs = s.focusBankedMs + (s.phase === 'FOCUS' ? runningForMs : 0);
  const remainingMs = s.mode.kind === 'pomodoro' ? Math.max(durationOf(s.mode, s.phase) - phaseElapsedMs, 0) : null;
  return {
    phase: s.phase,
    isRunning: s.run.kind === 'running',
    pauseReason: s.run.kind === 'paused' ? s.run.reason : null,
    sessionFocusMs,
    phaseElapsedMs,
    phaseRemainingMs: remainingMs,
    focusRounds: s.focusRounds,
  };
}

/** Elapsed-clock instant of the next Pomodoro boundary, or null if nothing is scheduled. */
export function nextBoundaryElapsedMs(state: TimerState): number | null {
  if (state.kind !== 'active' || state.mode.kind !== 'pomodoro' || state.run.kind !== 'running') return null;
  return state.run.since.elapsedMs + Math.max(durationOf(state.mode, state.phase) - state.phaseBankedMs, 0);
}

/** Focus time in this session not yet stored: the running stretch plus uncommitted boundaries. */
export function unrecordedFocusMs(state: TimerState, now: TimePoint): number {
  if (state.kind !== 'active') return 0;
  const r = readoutAt(state, now);
  if (!r) return 0;
  return Math.max(r.sessionFocusMs - state.focusBankedMs, 0);
}

const pad2 = (n: number) => String(n).padStart(2, '0');

/** `12:34` under an hour, `1:02:03` from an hour up. */
export function formatClock(ms: number, roundUp = false): string {
  const safe = Math.max(ms, 0);
  const total = roundUp ? Math.floor((safe + 999) / 1_000) : Math.floor(safe / 1_000);
  const h = Math.floor(total / 3_600);
  const m = Math.floor((total % 3_600) / 60);
  const s = total % 60;
  return h > 0 ? `${h}:${pad2(m)}:${pad2(s)}` : `${pad2(m)}:${pad2(s)}`;
}

export function formatReadout(r: TimerReadout): string {
  return formatClock(displayMs(r), isCountdown(r));
}

// endregion

// region Snapshot codec

const SNAPSHOT_VERSION = 1;

export function encodeSnapshot(state: TimerState): string {
  return JSON.stringify({ v: SNAPSHOT_VERSION, state });
}

/** Null means "present but unreadable"; the controller then starts idle. */
export function decodeSnapshot(text: string | null): TimerState | null {
  if (text === null) return IDLE;
  try {
    const parsed = JSON.parse(text) as { v?: number; state?: TimerState };
    if (parsed.v !== SNAPSHOT_VERSION || !parsed.state) return null;
    const s = parsed.state;
    if (s.kind === 'idle') return IDLE;
    if (s.kind !== 'active' || typeof s.sessionId !== 'string' || !s.run || !s.lastAlive || !s.mode) return null;
    return s;
  } catch {
    return null;
  }
}

// endregion
