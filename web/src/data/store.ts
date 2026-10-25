/**
 * Local data for the web app: the same stores as Android (DataStore for the live session and
 * preferences, Room for subjects, segments, sessions, tasks and D-Days), kept in localStorage.
 *
 * The timer controller follows the Android commit order: durable records first, then the
 * snapshot, then publish. Reloading the page at any point replays the snapshot, which re-emits
 * the same segments under the same (sessionId, index) keys, so nothing is counted twice.
 */
import { batch, computed, signal } from '@preact/signals';
import {
  GENERAL,
  GENERAL_COLOR,
  StudyDayClock,
  StudyStats,
  sanitizePreferences,
  segmentEnd,
  type DDay,
  type LocalDate,
  type StudyPreferences,
  type StudySegment,
  type StudyTask,
  type Subject,
} from '../core/model';
import {
  Commands,
  IDLE,
  STOPWATCH,
  decodeSnapshot,
  encodeSnapshot,
  pomodoro,
  reduce,
  unrecordedFocusMs,
  type PomodoroMode,
  type SessionSummary,
  type TimeSource,
  type TimePoint,
  type TimerCommand,
  type TimerEffect,
  type TimerState,
  type Transition,
} from '../core/timer';

export const KEYS = {
  timer: 'lockin.timer',
  prefs: 'lockin.prefs',
  subjects: 'lockin.subjects',
  segments: 'lockin.segments',
  sessions: 'lockin.sessions',
  tasks: 'lockin.tasks',
  ddays: 'lockin.ddays',
  ids: 'lockin.ids',
} as const;

export interface StoredSession {
  readonly sessionId: string;
  readonly subjectId: number | null;
  readonly subjectName: string;
  readonly mode: 'pomodoro' | 'stopwatch';
  readonly startedAtWallMs: number;
  readonly endedAtWallMs: number;
  readonly focusMs: number;
  readonly segmentCount: number;
  readonly focusRounds: number;
}

/** Everything a backup file holds. */
export interface Backup {
  readonly app: 'lock-in';
  readonly version: 1;
  readonly exportedAtMs: number;
  readonly prefs: StudyPreferences;
  readonly subjects: Subject[];
  readonly segments: StudySegment[];
  readonly sessions: StoredSession[];
  readonly tasks: StudyTask[];
  readonly ddays: DDay[];
}

/** Totals for one study day, including the part of a live session not yet stored. */
export interface DayTotals {
  readonly totalMs: number;
  readonly bySubjectId: Map<number | null, number>;
  /** Focus time running right now and not yet stored. */
  readonly liveMs: number;
}

export type Announcement =
  | { kind: 'phase'; effect: Extract<TimerEffect, { type: 'phaseChanged' }>; fresh: boolean }
  | { kind: 'interrupted'; creditedUntilWallMs: number };

export function pomodoroModeOf(p: StudyPreferences): PomodoroMode {
  return pomodoro({
    focusMs: Math.max(p.pomodoroFocusMin, 1) * 60_000,
    shortBreakMs: Math.max(p.shortBreakMin, 1) * 60_000,
    longBreakMs: Math.max(p.longBreakMin, 1) * 60_000,
    longBreakEvery: Math.max(p.longBreakEvery, 1),
    autoStartBreaks: p.autoStartBreaks,
    autoStartFocus: p.autoStartFocus,
  });
}

export function liveTotalsForDay(segments: readonly StudySegment[], dayStartMs: number, dayEndMs: number, state: TimerState, now: TimePoint): DayTotals {
  const bySubjectId = new Map<number | null, number>();
  let totalMs = 0;
  for (const s of segments) {
    const part = StudyStats.overlap(s, dayStartMs, dayEndMs);
    if (part <= 0) continue;
    bySubjectId.set(s.subjectId, (bySubjectId.get(s.subjectId) ?? 0) + part);
    totalMs += part;
  }
  let liveMs = 0;
  if (state.kind === 'active' && now.wallMs >= dayStartMs && now.wallMs < dayEndMs) {
    liveMs = unrecordedFocusMs(state, now);
    if (liveMs > 0) {
      bySubjectId.set(state.subject.id, (bySubjectId.get(state.subject.id) ?? 0) + liveMs);
      totalMs += liveMs;
    }
  }
  return { totalMs, bySubjectId, liveMs };
}

/** The running stretch as a provisional segment, so stats agree with the timer screen. */
export function liveSegment(state: TimerState, now: TimePoint): StudySegment | null {
  if (state.kind !== 'active') return null;
  const ms = unrecordedFocusMs(state, now);
  if (ms <= 0) return null;
  return { sessionId: 'live', index: -1, subjectId: state.subject.id, subjectName: state.subject.name, startWallMs: now.wallMs - ms, durationMs: ms };
}

const randomId = (): string =>
  typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;

export type LockInStore = ReturnType<typeof createStore>;

export function createStore(storage: Storage, time: TimeSource) {
  // region Persistence

  function read<T>(key: string, fallback: T): T {
    try {
      const text = storage.getItem(key);
      return text === null ? fallback : (JSON.parse(text) as T);
    } catch {
      return fallback;
    }
  }

  /** Returns false when storage is full or blocked; the in-memory state still updates. */
  function write(key: string, value: unknown): boolean {
    try {
      storage.setItem(key, JSON.stringify(value));
      return true;
    } catch {
      storageError.value = true;
      return false;
    }
  }

  const storageError = signal(false);
  const ids = read<Record<string, number>>(KEYS.ids, {});
  function nextId(kind: string): number {
    ids[kind] = (ids[kind] ?? 0) + 1;
    write(KEYS.ids, ids);
    return ids[kind];
  }

  // endregion

  // region State

  const prefs = signal<StudyPreferences>(sanitizePreferences(read(KEYS.prefs, null)));
  const allSubjects = signal<Subject[]>(read<Subject[]>(KEYS.subjects, []));
  const segments = signal<StudySegment[]>(read<StudySegment[]>(KEYS.segments, []));
  const sessions = signal<StoredSession[]>(read<StoredSession[]>(KEYS.sessions, []));
  const tasks = signal<StudyTask[]>(read<StudyTask[]>(KEYS.tasks, []));
  const ddays = signal<DDay[]>(read<DDay[]>(KEYS.ddays, []));
  const timer = signal<TimerState>(IDLE);

  /** Ticks at least every 30 s and on every visibility change, so the study day rolls over. */
  const minuteNow = signal(time().wallMs);
  const clock = computed(() => new StudyDayClock(prefs.value.dayStartHour));
  const today = computed<LocalDate>(() => clock.value.dayOf(minuteNow.value));
  const subjects = computed(() => allSubjects.value.filter((s) => !s.archived).sort((a, b) => a.order - b.order));
  const subjectsById = computed(() => new Map(allSubjects.value.map((s) => [s.id, s] as const)));
  const ddaysSorted = computed(() => [...ddays.value].sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.id - b.id)));

  const announcements = signal<Announcement[]>([]);

  // endregion

  // region Timer controller

  function upsertSegment(list: StudySegment[], seg: StudySegment): StudySegment[] {
    const i = list.findIndex((s) => s.sessionId === seg.sessionId && s.index === seg.index);
    if (i >= 0) {
      const copy = list.slice();
      copy[i] = seg;
      return copy;
    }
    return [...list, seg];
  }

  function commit(t: Transition, force: boolean) {
    const previous = timer.value;
    let nextSegments = segments.value;
    let nextSessions = sessions.value;
    for (const e of t.effects) {
      if (e.type === 'recordSegment') {
        const s = e.segment;
        nextSegments = upsertSegment(nextSegments, {
          sessionId: s.sessionId,
          index: s.index,
          subjectId: s.subject.id,
          subjectName: s.subject.name,
          startWallMs: s.startWallMs,
          durationMs: s.durationMs,
          endedBy: s.endedBy,
        });
      } else if (e.type === 'sessionFinished') {
        const sum: SessionSummary = e.summary;
        const row: StoredSession = {
          sessionId: sum.sessionId,
          subjectId: sum.subject.id,
          subjectName: sum.subject.name,
          mode: sum.mode.kind,
          startedAtWallMs: sum.startedAtWallMs,
          endedAtWallMs: sum.endedAtWallMs,
          focusMs: sum.focusMs,
          segmentCount: sum.segmentCount,
          focusRounds: sum.focusRounds,
        };
        nextSessions = [...nextSessions.filter((x) => x.sessionId !== row.sessionId), row];
      }
    }
    // 1. Durable records. 2. Snapshot. 3. Publish.
    // If a record can't be written (storage full), stop here like Android does when Room throws:
    // the old snapshot stays, so the session keeps counting and replays the record later.
    const recordsSaved =
      (nextSegments === segments.value || write(KEYS.segments, nextSegments)) && (nextSessions === sessions.value || write(KEYS.sessions, nextSessions));
    if (!recordsSaved) return;
    if (force || t.state !== previous) {
      try {
        storage.setItem(KEYS.timer, encodeSnapshot(t.state));
      } catch {
        storageError.value = true;
      }
    }
    batch(() => {
      segments.value = nextSegments;
      sessions.value = nextSessions;
      timer.value = t.state;
    });
    // 4. Announcements.
    const now = time();
    const fresh: Announcement[] = [];
    for (const e of t.effects) {
      if (e.type === 'phaseChanged') {
        // Fresh when the boundary passed moments ago (the page was open), stale after a long sleep.
        const s = t.state;
        const sinceMs = s.kind === 'active' && s.run.kind === 'running' ? now.elapsedMs - s.run.since.elapsedMs : s.kind === 'active' && s.run.kind === 'paused' ? now.wallMs - s.run.atWallMs : 0;
        fresh.push({ kind: 'phase', effect: e, fresh: sinceMs < 5_000 });
      } else if (e.type === 'interrupted') {
        fresh.push({ kind: 'interrupted', creditedUntilWallMs: e.creditedUntilWallMs });
      }
    }
    if (fresh.length) announcements.value = [...announcements.value, ...fresh];
  }

  function dispatch(command: TimerCommand): Transition {
    const t = reduce(timer.value, command, time());
    commit(t, false);
    return t;
  }

  /** Restores the persisted session and catches up with the clock. Call once at startup. */
  function restore() {
    const persisted = decodeSnapshot(storage.getItem(KEYS.timer)) ?? IDLE;
    timer.value = persisted;
    commit(reduce(persisted, Commands.reconcile, time()), true);
  }

  // endregion

  // region Session launcher

  /**
   * Starts `subjectId` (null = General) in the preferred mode. If that subject is the active
   * session, resumes it when paused; if another subject is active, that session ends first.
   * Returns 'started', 'resumed' or 'unchanged'.
   */
  function startOrResume(subjectId: number | null): 'started' | 'resumed' | 'unchanged' {
    const current = timer.value;
    if (current.kind === 'active') {
      if (current.subject.id === subjectId) {
        if (current.run.kind === 'paused') {
          dispatch(Commands.resume);
          return 'resumed';
        }
        return 'unchanged';
      }
      dispatch(Commands.stop);
    }
    const p = prefs.value;
    const name = subjectId === null ? GENERAL : (subjects.value.find((s) => s.id === subjectId)?.name ?? GENERAL);
    const mode = p.preferPomodoro ? pomodoroModeOf(p) : STOPWATCH;
    dispatch(Commands.start(randomId(), { id: subjectId, name }, mode));
    return 'started';
  }

  // endregion

  // region Repositories

  function updatePrefs(transform: (p: StudyPreferences) => Partial<StudyPreferences>) {
    const next = sanitizePreferences({ ...prefs.value, ...transform(prefs.value) });
    write(KEYS.prefs, next);
    prefs.value = next;
  }

  function saveSubjects(list: Subject[]) {
    write(KEYS.subjects, list);
    allSubjects.value = list;
  }

  const subjectRepo = {
    add(name: string, color: string): number {
      const id = nextId('subject');
      const order = allSubjects.value.reduce((m, s) => Math.max(m, s.order), 0) + 1;
      saveSubjects([...allSubjects.value, { id, name: name.trim().slice(0, 40), color, order }]);
      return id;
    },
    /** Renaming also renames past segments, so history and stats show the new name. */
    update(id: number, name: string, color: string) {
      const existing = allSubjects.value.find((s) => s.id === id);
      if (!existing) return;
      const trimmed = name.trim().slice(0, 40);
      saveSubjects(allSubjects.value.map((s) => (s.id === id ? { ...s, name: trimmed, color } : s)));
      if (trimmed !== existing.name) {
        const renamed = segments.value.map((s) => (s.subjectId === id ? { ...s, subjectName: trimmed } : s));
        write(KEYS.segments, renamed);
        segments.value = renamed;
      }
    },
    /** Hides the subject from pickers. Its history stays. */
    archive(id: number) {
      saveSubjects(allSubjects.value.map((s) => (s.id === id ? { ...s, archived: true } : s)));
    },
    move(id: number, delta: -1 | 1) {
      const list = subjects.value;
      const i = list.findIndex((s) => s.id === id);
      const j = i + delta;
      if (i < 0 || j < 0 || j >= list.length) return;
      const a = list[i];
      const b = list[j];
      saveSubjects(allSubjects.value.map((s) => (s.id === a.id ? { ...s, order: b.order } : s.id === b.id ? { ...s, order: a.order } : s)));
    },
  };

  function saveTasks(list: StudyTask[]) {
    write(KEYS.tasks, list);
    tasks.value = list;
  }

  const taskRepo = {
    add(title: string, date: LocalDate, subjectId: number | null) {
      const trimmed = title.trim().slice(0, 200);
      if (!trimmed) return;
      saveTasks([...tasks.value, { id: nextId('task'), title: trimmed, date, subjectId, done: false, createdAtMs: time().wallMs }]);
    },
    setDone(id: number, done: boolean) {
      saveTasks(tasks.value.map((t) => (t.id === id ? { ...t, done } : t)));
    },
    edit(id: number, title: string, subjectId: number | null) {
      saveTasks(tasks.value.map((t) => (t.id === id ? { ...t, title: title.trim().slice(0, 200), subjectId } : t)));
    },
    moveTo(id: number, date: LocalDate) {
      saveTasks(tasks.value.map((t) => (t.id === id ? { ...t, date } : t)));
    },
    delete(id: number) {
      saveTasks(tasks.value.filter((t) => t.id !== id));
    },
  };

  function saveDDays(list: DDay[]) {
    write(KEYS.ddays, list);
    ddays.value = list;
  }

  const ddayRepo = {
    add(title: string, date: LocalDate) {
      const trimmed = title.trim().slice(0, 60);
      if (!trimmed) return;
      saveDDays([...ddays.value, { id: nextId('dday'), title: trimmed, date }]);
    },
    delete(id: number) {
      saveDDays(ddays.value.filter((d) => d.id !== id));
    },
  };

  /** Stored segments overlapping `[fromMs, toMs)`. */
  function segmentsBetween(fromMs: number, toMs: number): StudySegment[] {
    return segments.value.filter((s) => s.startWallMs < toMs && segmentEnd(s) > fromMs);
  }

  function colorOf(subjectId: number | null): string {
    return (subjectId !== null ? subjectsById.value.get(subjectId)?.color : undefined) ?? GENERAL_COLOR;
  }

  // endregion

  // region Backup

  function exportBackup(): Backup {
    return {
      app: 'lock-in',
      version: 1,
      exportedAtMs: time().wallMs,
      prefs: prefs.value,
      subjects: allSubjects.value,
      segments: segments.value,
      sessions: sessions.value,
      tasks: tasks.value,
      ddays: ddays.value,
    };
  }

  /** Replaces local data with a backup. Throws with a user-facing message if the file is wrong. */
  function importBackup(data: unknown) {
    const b = data as Partial<Backup> | null;
    if (!b || b.app !== 'lock-in' || b.version !== 1 || !Array.isArray(b.segments) || !Array.isArray(b.subjects)) {
      throw new Error("That file isn't a Lock In backup.");
    }
    if (timer.value.kind === 'active') dispatch(Commands.stop);
    const maxId = (list: { id: number }[]) => list.reduce((m, x) => Math.max(m, x.id), 0);
    const subjectsIn = b.subjects;
    const tasksIn = Array.isArray(b.tasks) ? b.tasks : [];
    const ddaysIn = Array.isArray(b.ddays) ? b.ddays : [];
    ids.subject = Math.max(ids.subject ?? 0, maxId(subjectsIn));
    ids.task = Math.max(ids.task ?? 0, maxId(tasksIn));
    ids.dday = Math.max(ids.dday ?? 0, maxId(ddaysIn));
    write(KEYS.ids, ids);
    batch(() => {
      saveSubjects(subjectsIn);
      write(KEYS.segments, b.segments);
      segments.value = b.segments as StudySegment[];
      const sessionsIn = Array.isArray(b.sessions) ? b.sessions : [];
      write(KEYS.sessions, sessionsIn);
      sessions.value = sessionsIn;
      saveTasks(tasksIn);
      saveDDays(ddaysIn);
      updatePrefs(() => b.prefs ?? {});
    });
  }

  /** Picks up changes another tab made. */
  function reloadFromStorage(key: string | null) {
    batch(() => {
      if (key === null || key === KEYS.prefs) prefs.value = sanitizePreferences(read(KEYS.prefs, null));
      if (key === null || key === KEYS.subjects) allSubjects.value = read(KEYS.subjects, []);
      if (key === null || key === KEYS.segments) segments.value = read(KEYS.segments, []);
      if (key === null || key === KEYS.sessions) sessions.value = read(KEYS.sessions, []);
      if (key === null || key === KEYS.tasks) tasks.value = read(KEYS.tasks, []);
      if (key === null || key === KEYS.ddays) ddays.value = read(KEYS.ddays, []);
      if (key === null || key === KEYS.timer) timer.value = decodeSnapshot(storage.getItem(KEYS.timer)) ?? IDLE;
      if (key === null || key === KEYS.ids) Object.assign(ids, read(KEYS.ids, {}));
    });
  }

  // endregion

  return {
    time,
    // state
    prefs,
    subjects,
    allSubjects,
    subjectsById,
    segments,
    sessions,
    tasks,
    ddays: ddaysSorted,
    timer,
    clock,
    today,
    minuteNow,
    announcements,
    storageError,
    // timer
    dispatch,
    restore,
    startOrResume,
    // repositories
    updatePrefs,
    subjectRepo,
    taskRepo,
    ddayRepo,
    segmentsBetween,
    colorOf,
    exportBackup,
    importBackup,
    reloadFromStorage,
  };
}

/** In-memory Storage for tests and for browsers that block localStorage. */
export class MemoryStorage implements Storage {
  private map = new Map<string, string>();
  get length() {
    return this.map.size;
  }
  clear() {
    this.map.clear();
  }
  getItem(key: string) {
    return this.map.get(key) ?? null;
  }
  key(i: number) {
    return [...this.map.keys()][i] ?? null;
  }
  removeItem(key: string) {
    this.map.delete(key);
  }
  setItem(key: string, value: string) {
    this.map.set(key, String(value));
  }
}
