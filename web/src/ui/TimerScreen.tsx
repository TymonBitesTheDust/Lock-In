import { useState } from 'preact/hooks';
import { Durations, GENERAL, GENERAL_COLOR, ddayDaysFrom, ddayLabel, nextSubjectColor } from '../core/model';
import { Commands, formatClock, formatReadout, millisUntilDisplayChanges, readoutAt, type TimerReadout } from '../core/timer';
import { isIOS, requestPersistentStorage, runShortcut, store, unlockAudio, wakeLockSupported } from '../data/runtime';
import { liveTotalsForDay } from '../data/store';
import { Section, SubjectSheet, Toggle } from './common';
import { useNow } from './hooks';
import { Icon } from './icons';

/** Starts or resumes a subject from any screen, with the side effects a tap is allowed to have. */
export function startSubject(subjectId: number | null) {
  unlockAudio();
  const result = store.startOrResume(subjectId);
  if (result === 'started') {
    void requestPersistentStorage();
    const shortcut = store.prefs.value.shortcutOnStart;
    if (shortcut && isIOS()) runShortcut(shortcut);
  }
}

export function stopSession() {
  store.dispatch(Commands.stop);
  const shortcut = store.prefs.value.shortcutOnStop;
  if (shortcut && isIOS()) runShortcut(shortcut);
}

export function TimerScreen() {
  const state = store.timer.value;
  const now = useNow((t) => {
    const r = readoutAt(state, t);
    // Keep "today" fresh once a minute even when idle.
    return r ? millisUntilDisplayChanges(r) : 60_000;
  }, [state]);
  const readout = readoutAt(state, now);
  return state.kind === 'active' && readout ? <SessionView readout={readout} now={now.wallMs} /> : <Home />;
}

function Home() {
  const [adding, setAdding] = useState(false);
  const clock = store.clock.value;
  const today = store.today.value;
  const now = store.time();
  const start = clock.startOf(today);
  const end = clock.endOf(today);
  const totals = liveTotalsForDay(store.segmentsBetween(start, end), start, end, store.timer.value, now);
  const subjects = store.subjects.value;
  const prefs = store.prefs.value;
  const upcoming = store.ddays.value.find((d) => ddayDaysFrom(d, today) >= 0);
  const generalMs = totals.bySubjectId.get(null) ?? 0;

  return (
    <div class="screen">
      <div style="min-height:40px;margin-top:4px">
        {upcoming && (
          <span class="chip">
            {upcoming.title} · {ddayLabel(upcoming, today)}
          </span>
        )}
      </div>
      <div class="muted" style="margin-top:12px">
        Today
      </div>
      <div class="clock" aria-label={`Studied today ${Durations.short(totals.totalMs)}`}>
        {Durations.clock(totals.totalMs)}
      </div>

      <div class="segmented" role="group" aria-label="Timer mode">
        <button type="button" aria-pressed={!prefs.preferPomodoro} onClick={() => store.updatePrefs(() => ({ preferPomodoro: false }))}>
          {!prefs.preferPomodoro && <Icon name="check" />}Stopwatch
        </button>
        <button type="button" aria-pressed={prefs.preferPomodoro} onClick={() => store.updatePrefs(() => ({ preferPomodoro: true }))}>
          {prefs.preferPomodoro && <Icon name="check" />}Pomodoro {prefs.pomodoroFocusMin} / {prefs.shortBreakMin}
        </button>
      </div>

      <Section title="Subjects" action={{ label: 'Add', icon: 'add', onClick: () => setAdding(true) }} />
      <div class="list">
        {subjects.map((s) => (
          <SubjectRow key={s.id} name={s.name} color={s.color} ms={totals.bySubjectId.get(s.id) ?? 0} onStart={() => startSubject(s.id)} />
        ))}
        {(subjects.length === 0 || generalMs > 0) && <SubjectRow name={GENERAL} color={GENERAL_COLOR} ms={generalMs} onStart={() => startSubject(null)} />}
      </div>
      {subjects.length === 0 && <p class="hint">Add your subjects to see where your time goes. Until then, sessions count as General.</p>}
      <p class="hint">Your time keeps counting when you lock your phone or close Lock In.</p>

      {adding && (
        <SubjectSheet
          title="New subject"
          initialName=""
          initialColor={nextSubjectColor(subjects.map((s) => s.color))}
          onSave={(name, color) => store.subjectRepo.add(name, color)}
          onClose={() => setAdding(false)}
        />
      )}
    </div>
  );
}

function SubjectRow({ name, color, ms, onStart }: { name: string; color: string; ms: number; onStart: () => void }) {
  return (
    <div class="row">
      <span class="dot" style={{ background: color }} />
      <span class="grow title ellipsis">{name}</span>
      <span class="tnum muted">{Durations.clock(ms)}</span>
      <button type="button" class="play-btn" aria-label={`Start ${name}`} onClick={onStart}>
        <Icon name="play" />
      </button>
    </div>
  );
}

function phaseLabel(r: TimerReadout, pomodoro: boolean): string {
  if (r.phase === 'SHORT_BREAK') return 'Short break';
  if (r.phase === 'LONG_BREAK') return 'Long break';
  return pomodoro ? `Focus · round ${r.focusRounds + 1}` : 'Focus';
}

function statusText(r: TimerReadout): string | null {
  switch (r.pauseReason) {
    case 'USER':
      return 'Paused. Paused time never counts.';
    case 'AWAITING_NEXT_PHASE':
      return r.phase === 'FOCUS' ? "Break's over. Resume when you're ready." : 'Round done. Resume to start your break.';
    case 'INTERRUPTED':
      return 'Your clock jumped backwards, so we paused here. Everything up to then was counted.';
    default:
      return null;
  }
}

function SessionView({ readout, now }: { readout: TimerReadout; now: number }) {
  const state = store.timer.value;
  if (state.kind !== 'active') return null;
  const pomodoro = state.mode.kind === 'pomodoro';
  const color = store.colorOf(state.subject.id);
  const clock = store.clock.value;
  const today = store.today.value;
  const start = clock.startOf(today);
  const end = clock.endOf(today);
  const todayMs = liveTotalsForDay(store.segmentsBetween(start, end), start, end, state, store.time()).totalMs;
  const status = statusText(readout);
  const prefs = store.prefs.value;
  const isBreak = readout.phase !== 'FOCUS';

  return (
    <div class="screen">
      <div class="session">
        <div class="subject">
          <span class="dot" style={{ background: color }} />
          {state.subject.name}
        </div>
        <div class={`phase ${isBreak ? 'break' : ''}`}>{phaseLabel(readout, pomodoro)}</div>
        <div class="clock" role="timer" aria-live="off" data-now={now}>
          {formatReadout(readout)}
        </div>
        <div class="muted tnum">{pomodoro ? `${formatClock(readout.sessionFocusMs)} focused this session` : `Today ${Durations.clock(todayMs)}`}</div>
        {status && <p class="status">{status}</p>}
        <div class="controls">
          {readout.isRunning ? (
            <button type="button" class="btn" onClick={() => store.dispatch(Commands.pause)}>
              Pause
            </button>
          ) : (
            <button
              type="button"
              class="btn primary"
              onClick={() => {
                unlockAudio();
                store.dispatch(Commands.resume);
              }}
            >
              Resume
            </button>
          )}
          {pomodoro && (
            <button type="button" class="btn" onClick={() => store.dispatch(Commands.skip)}>
              Skip
            </button>
          )}
          <button type="button" class="btn text" onClick={stopSession}>
            Stop
          </button>
        </div>
        {wakeLockSupported() && (
          <label class="awake row" style="width:auto;gap:12px">
            <span class="small muted">Keep screen on</span>
            <Toggle checked={prefs.keepScreenOn} label="Keep screen on" onChange={(v) => store.updatePrefs(() => ({ keepScreenOn: v }))} />
          </label>
        )}
        {pomodoro && <p class="hint">Break alerts play while Lock In is open. Time is counted either way.</p>}
      </div>
    </div>
  );
}
