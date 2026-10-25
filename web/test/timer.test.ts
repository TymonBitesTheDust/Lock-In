// A line-by-line port of core/timer/src/test/.../TimerEngineTest.kt, so both apps obey the same rules.
import { beforeEach, describe, expect, it } from 'vitest';
import {
  Commands,
  IDLE,
  STOPWATCH,
  decodeSnapshot,
  encodeSnapshot,
  formatClock,
  formatReadout,
  millisUntilDisplayChanges,
  nextBoundaryElapsedMs,
  pomodoro,
  readoutAt,
  reduce,
  webTimeSource,
  type ActiveState,
  type FocusSegment,
  type PomodoroMode,
  type TimerCommand,
  type TimerEffect,
  type TimerMode,
  type TimerReadout,
  type TimerState,
  type Transition,
} from '../src/core/timer';
import { FakeClock, MINUTE } from './fakeClock';

const physics = { id: 3, name: 'Physics' };
const shortPomodoro: PomodoroMode = pomodoro({
  focusMs: 25 * MINUTE,
  shortBreakMs: 5 * MINUTE,
  longBreakMs: 15 * MINUTE,
  longBreakEvery: 4,
  autoStartBreaks: true,
  autoStartFocus: true,
});

let clock: FakeClock;
let state: TimerState;
let effects: TimerEffect[];

beforeEach(() => {
  clock = new FakeClock();
  state = IDLE;
  effects = [];
});

function send(command: TimerCommand): Transition {
  const t = reduce(state, command, clock.now());
  state = t.state;
  effects.push(...t.effects);
  return t;
}

const start = (mode: TimerMode = STOPWATCH) => send(Commands.start('s-1', physics, mode));
const readout = (): TimerReadout => readoutAt(state, clock.now())!;
const active = () => state as ActiveState;
const segments = (): FocusSegment[] => effects.flatMap((e) => (e.type === 'recordSegment' ? [e.segment] : []));
const withoutHeartbeat = (s: TimerState) => (s.kind === 'active' ? { ...s, lastAlive: null } : s);

describe('stopwatch', () => {
  it('counts only running time and segments add up to the total', () => {
    start();
    clock.advanceMinutes(10);
    expect(readout().sessionFocusMs).toBe(10 * MINUTE);

    send(Commands.pause);
    clock.advanceMinutes(5);
    expect(readout().sessionFocusMs).toBe(10 * MINUTE);

    send(Commands.resume);
    clock.advanceMinutes(3);
    expect(readout().sessionFocusMs).toBe(13 * MINUTE);

    send(Commands.stop);
    expect(state).toEqual(IDLE);
    const finished = effects.filter((e) => e.type === 'sessionFinished');
    expect(finished).toHaveLength(1);
    const summary = finished[0].type === 'sessionFinished' ? finished[0].summary : null;
    expect(summary?.focusMs).toBe(13 * MINUTE);
    expect(summary?.segmentCount).toBe(2);
    expect(segments().map((s) => s.endedBy)).toEqual(['PAUSED', 'STOPPED']);
    expect(segments().reduce((a, s) => a + s.durationMs, 0)).toBe(summary?.focusMs);
    expect(segments().map((s) => s.index)).toEqual([0, 1]);
  });

  it('changing the wall clock never changes study time (with a separate monotonic clock)', () => {
    start();
    const startWall = clock.now().wallMs;
    clock.advanceMinutes(10);
    clock.jumpWallClock(2 * 60 * MINUTE);
    clock.advanceMinutes(5);
    expect(readout().sessionFocusMs).toBe(15 * MINUTE);

    send(Commands.stop);
    const [segment] = segments();
    expect(segment.durationMs).toBe(15 * MINUTE);
    expect(segment.startWallMs).toBe(startWall);
    expect(segment.endWallMs).toBe(startWall + 15 * MINUTE);
  });

  it('heartbeats do not create segments or change totals', () => {
    start();
    for (let i = 0; i < 30; i++) {
      clock.advanceMinutes(1);
      send(Commands.heartbeat);
    }
    expect(segments()).toHaveLength(0);
    expect(readout().sessionFocusMs).toBe(30 * MINUTE);
    expect(active().lastAlive).toEqual(clock.now());
  });
});

describe('pomodoro', () => {
  it('late wake-up crosses every boundary at its exact instant', () => {
    start(shortPomodoro);
    clock.advanceMinutes(61);
    send(Commands.reconcile);

    expect(active().phase).toBe('FOCUS');
    expect(active().focusRounds).toBe(2);
    expect(readout().phaseElapsedMs).toBe(1 * MINUTE);
    expect(readout().sessionFocusMs).toBe(51 * MINUTE);
    expect(segments().map((s) => s.durationMs)).toEqual([25 * MINUTE, 25 * MINUTE]);
    expect(segments().every((s) => s.endedBy === 'PHASE_COMPLETE')).toBe(true);

    const announced = effects.filter((e) => e.type === 'phaseChanged');
    expect(announced).toEqual([{ type: 'phaseChanged', from: 'SHORT_BREAK', to: 'FOCUS', nextStartsAutomatically: true }]);
  });

  it('reconcile is idempotent', () => {
    start(shortPomodoro);
    clock.advanceMinutes(26);
    const first = send(Commands.reconcile);
    const second = send(Commands.reconcile);
    expect(second.state).toEqual(first.state);
    expect(second.effects).toHaveLength(0);
  });

  it('without auto-start focus the session waits after a break', () => {
    start({ ...shortPomodoro, autoStartFocus: false });
    clock.advanceMinutes(90);
    send(Commands.reconcile);

    expect(active().phase).toBe('FOCUS');
    expect(active().run).toEqual({ kind: 'paused', reason: 'AWAITING_NEXT_PHASE', atWallMs: active().lastAlive.wallMs - 60 * MINUTE });
    expect(readout().sessionFocusMs).toBe(25 * MINUTE);

    send(Commands.resume);
    clock.advanceMinutes(5);
    expect(readout().sessionFocusMs).toBe(30 * MINUTE);
  });

  it('every fourth round is followed by a long break', () => {
    start(shortPomodoro);
    clock.advanceMinutes(116);
    send(Commands.reconcile);
    expect(active().phase).toBe('LONG_BREAK');
    expect(active().focusRounds).toBe(4);
    expect(readout().sessionFocusMs).toBe(100 * MINUTE);
    expect(readout().phaseRemainingMs).toBe(14 * MINUTE);
  });

  it('skip ends the phase now and starts the next one', () => {
    start({ ...shortPomodoro, autoStartBreaks: false });
    clock.advanceMinutes(10);
    send(Commands.skip);

    expect(active().phase).toBe('SHORT_BREAK');
    expect(active().run.kind).toBe('running');
    expect(segments()).toHaveLength(1);
    expect(segments()[0].endedBy).toBe('SKIPPED');
    expect(segments()[0].durationMs).toBe(10 * MINUTE);
  });

  it('pausing mid-phase keeps the remaining time', () => {
    start(shortPomodoro);
    clock.advanceMinutes(20);
    send(Commands.pause);
    clock.advanceMinutes(60);
    send(Commands.reconcile);
    expect(active().phase).toBe('FOCUS');
    expect(readout().phaseRemainingMs).toBe(5 * MINUTE);

    send(Commands.resume);
    expect(nextBoundaryElapsedMs(state)).toBe(clock.now().elapsedMs + 5 * MINUTE);
  });

  it('readout crosses a boundary even before the alarm is handled', () => {
    start(shortPomodoro);
    clock.advanceMinutes(26);
    expect(active().phase).toBe('FOCUS');
    expect(readout().phase).toBe('SHORT_BREAK');
    expect(readout().phaseRemainingMs).toBe(4 * MINUTE);
  });
});

describe('restarts', () => {
  it('credits time up to the last heartbeat and pauses', () => {
    start();
    clock.advanceMinutes(10);
    send(Commands.heartbeat);
    const lastAliveWall = clock.now().wallMs;
    clock.advanceMinutes(3);
    clock.reboot(20 * MINUTE);

    send(Commands.reconcile);

    expect(active().run).toEqual({ kind: 'paused', reason: 'INTERRUPTED', atWallMs: lastAliveWall });
    expect(readout().sessionFocusMs).toBe(10 * MINUTE);
    expect(segments().map((s) => s.endedBy)).toEqual(['INTERRUPTED']);
    expect(effects.filter((e) => e.type === 'interrupted')).toEqual([{ type: 'interrupted', creditedUntilWallMs: lastAliveWall }]);

    send(Commands.resume);
    clock.advanceMinutes(2);
    expect(readout().sessionFocusMs).toBe(12 * MINUTE);
  });

  it('is detected without a boot counter when the clocks disagree', () => {
    clock.forgetBootCount();
    start();
    clock.advanceMinutes(5);
    send(Commands.heartbeat);
    clock.reboot(8 * 60 * MINUTE, 3 * 60 * 60 * 1_000, false);

    send(Commands.reconcile);
    expect(active().run.kind === 'paused' && active().run).toMatchObject({ reason: 'INTERRUPTED' });
    expect(readout().sessionFocusMs).toBe(5 * MINUTE);
  });

  it('replays pomodoro boundaries before the last heartbeat', () => {
    start(shortPomodoro);
    clock.advanceMinutes(27);
    send(Commands.heartbeat);
    clock.reboot(60 * MINUTE);

    send(Commands.reconcile);
    expect(active().phase).toBe('SHORT_BREAK');
    expect(active().run).toMatchObject({ kind: 'paused', reason: 'INTERRUPTED' });
    expect(active().phaseBankedMs).toBe(2 * MINUTE);
    expect(readout().sessionFocusMs).toBe(25 * MINUTE);
    expect(effects.some((e) => e.type === 'phaseChanged' && e.to === 'FOCUS')).toBe(false);
  });

  it('leaves paused sessions untouched', () => {
    start();
    clock.advanceMinutes(10);
    send(Commands.pause);
    const paused = state;
    clock.reboot(MINUTE);
    const t = send(Commands.reconcile);
    expect(withoutHeartbeat(t.state)).toEqual(withoutHeartbeat(paused));
    expect(t.effects).toHaveLength(0);
  });
});

describe('web clock', () => {
  it('pauses at the last checkpoint when the clock moves backwards', () => {
    // On the web both readings are Date.now(); the only "restart" is a clock moved backwards.
    let now = 1_760_000_000_000;
    const at = () => ({ elapsedMs: now, wallMs: now, bootCount: -1 });
    let s: TimerState = reduce(IDLE, Commands.start('w', physics, STOPWATCH), at()).state;
    now += 10 * MINUTE;
    s = reduce(s, Commands.heartbeat, at()).state;
    now -= 60 * MINUTE;
    const t = reduce(s, Commands.reconcile, at());
    expect((t.state as ActiveState).run).toMatchObject({ kind: 'paused', reason: 'INTERRUPTED' });
    expect(t.effects.filter((e) => e.type === 'recordSegment')).toHaveLength(1);
  });

  it('reads Date.now for both clocks', () => {
    const p = webTimeSource();
    expect(p.elapsedMs).toBe(p.wallMs);
  });
});

describe('invalid commands', () => {
  it('are ignored with a reason', () => {
    expect(send(Commands.pause).effects.map((e) => e.type)).toEqual(['ignored']);
    expect(send(Commands.stop).effects.map((e) => e.type)).toEqual(['ignored']);
    expect(send(Commands.heartbeat).effects).toHaveLength(0);

    start();
    expect(start().effects.map((e) => e.type)).toEqual(['ignored']);
    expect(send(Commands.resume).effects.map((e) => e.type)).toEqual(['ignored']);
    expect(send(Commands.skip).effects.map((e) => e.type)).toEqual(['ignored']);
    expect(active().sessionId).toBe('s-1');
  });

  it('pausing immediately records no empty segment', () => {
    start();
    send(Commands.pause);
    expect(segments()).toHaveLength(0);
    expect(active().nextSegmentIndex).toBe(0);
  });

  it('rejects blank session ids and too-short phases', () => {
    expect(() => Commands.start(' ', physics, STOPWATCH)).toThrow();
    expect(() => pomodoro({ focusMs: 10, shortBreakMs: MINUTE, longBreakMs: MINUTE })).toThrow();
  });
});

describe('readout', () => {
  it('display ticks land exactly on second boundaries', () => {
    start();
    clock.advance(10_250);
    expect(millisUntilDisplayChanges(readout())).toBe(750);

    const countdown: TimerReadout = { phase: 'FOCUS', isRunning: true, pauseReason: null, sessionFocusMs: 0, phaseElapsedMs: 0, phaseRemainingMs: 1_499, focusRounds: 0 };
    expect(millisUntilDisplayChanges(countdown)).toBe(499);
    expect(formatReadout(countdown)).toBe('00:02');
    expect(millisUntilDisplayChanges({ ...countdown, phaseRemainingMs: 2_000 })).toBe(1_000);

    send(Commands.pause);
    expect(millisUntilDisplayChanges(readout())).toBe(Number.POSITIVE_INFINITY);
  });

  it('formats clocks', () => {
    expect(formatClock(0)).toBe('00:00');
    expect(formatClock(599_999)).toBe('09:59');
    expect(formatClock(599_001, true)).toBe('10:00');
    expect(formatClock(3_600_000)).toBe('1:00:00');
    expect(formatClock((12 * 3600 + 34 * 60 + 56) * 1_000)).toBe('12:34:56');
  });

  it('idle has no readout and no boundary', () => {
    expect(readoutAt(IDLE, clock.now())).toBeNull();
    expect(nextBoundaryElapsedMs(IDLE)).toBeNull();
    start();
    expect(nextBoundaryElapsedMs(state)).toBeNull();
  });
});

describe('snapshot codec', () => {
  it('round-trips active and idle states', () => {
    start(shortPomodoro);
    clock.advanceMinutes(3);
    send(Commands.pause);
    expect(decodeSnapshot(encodeSnapshot(state))).toEqual(state);
    expect(decodeSnapshot(encodeSnapshot(IDLE))).toEqual(IDLE);
  });

  it('treats a missing snapshot as idle and garbage as unreadable', () => {
    expect(decodeSnapshot(null)).toEqual(IDLE);
    expect(decodeSnapshot('not json')).toBeNull();
    expect(decodeSnapshot('{"v":99,"state":{"kind":"idle"}}')).toBeNull();
    expect(decodeSnapshot('{"v":1,"state":{"kind":"active"}}')).toBeNull();
  });
});
