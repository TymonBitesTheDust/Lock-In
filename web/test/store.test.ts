import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Commands } from '../src/core/timer';
import { KEYS, MemoryStorage, createStore } from '../src/data/store';
import { parseConfig } from '../src/groups/api';
import { MINUTE } from './fakeClock';

let now = 0;
const time = () => ({ elapsedMs: now, wallMs: now, bootCount: -1 });
const at = (iso: string) => new Date(iso).getTime();

describe('store', () => {
  let storage: MemoryStorage;

  beforeEach(() => {
    storage = new MemoryStorage();
    now = at('2026-10-24T10:00:00+11:00');
  });

  it('writes segments before the snapshot and survives a "reload"', () => {
    const a = createStore(storage, time);
    a.restore();
    const maths = a.subjectRepo.add('Maths', '#3987E5');
    expect(a.startOrResume(maths)).toBe('started');
    now += 20 * MINUTE;
    a.dispatch(Commands.pause);
    expect(JSON.parse(storage.getItem(KEYS.segments)!)).toHaveLength(1);

    // A new page load reads the same storage.
    now += 5 * MINUTE;
    const b = createStore(storage, time);
    b.restore();
    expect(b.timer.value.kind).toBe('active');
    expect(b.startOrResume(maths)).toBe('resumed');
    now += 10 * MINUTE;
    b.dispatch(Commands.stop);
    const segs = b.segments.value;
    expect(segs.map((s) => s.durationMs)).toEqual([20 * MINUTE, 10 * MINUTE]);
    expect(b.sessions.value).toHaveLength(1);
    expect(b.sessions.value[0].focusMs).toBe(30 * MINUTE);
  });

  it('replaying an old snapshot never double-counts a segment', () => {
    const a = createStore(storage, time);
    a.restore();
    a.startOrResume(null);
    const snapshot = storage.getItem(KEYS.timer);
    now += 30 * MINUTE;
    a.dispatch(Commands.stop);
    // Crash between "records" and "snapshot": the old snapshot comes back.
    storage.setItem(KEYS.timer, snapshot!);
    const b = createStore(storage, time);
    b.restore();
    b.dispatch(Commands.stop);
    expect(b.segments.value).toHaveLength(1);
    expect(b.segments.value[0].durationMs).toBe(30 * MINUTE);
  });

  it('switching subjects ends the running session first', () => {
    const s = createStore(storage, time);
    s.restore();
    const maths = s.subjectRepo.add('Maths', '#3987E5');
    const physics = s.subjectRepo.add('Physics', '#D95926');
    s.startOrResume(maths);
    now += 5 * MINUTE;
    s.startOrResume(physics);
    expect(s.sessions.value).toHaveLength(1);
    expect(s.timer.value.kind === 'active' && s.timer.value.subject.name).toBe('Physics');
  });

  it('uses the preferred Pomodoro settings', () => {
    const s = createStore(storage, time);
    s.restore();
    s.updatePrefs(() => ({ preferPomodoro: true, pomodoroFocusMin: 50, shortBreakMin: 10 }));
    s.startOrResume(null);
    const t = s.timer.value;
    expect(t.kind === 'active' && t.mode.kind === 'pomodoro' && t.mode.focusMs).toBe(50 * MINUTE);
  });

  it('renaming a subject renames its history; removing keeps it', () => {
    const s = createStore(storage, time);
    s.restore();
    const id = s.subjectRepo.add('Math', '#3987E5');
    s.startOrResume(id);
    now += MINUTE;
    s.dispatch(Commands.stop);
    s.subjectRepo.update(id, 'Maths', '#199E70');
    expect(s.segments.value[0].subjectName).toBe('Maths');
    s.subjectRepo.archive(id);
    expect(s.subjects.value).toHaveLength(0);
    expect(s.segments.value).toHaveLength(1);
    expect(s.colorOf(id)).toBe('#199E70');
  });

  it('the study day starts at the configured hour', () => {
    now = at('2026-10-25T03:00:00+11:00');
    const s = createStore(storage, time);
    expect(s.today.value).toBe('2026-10-24');
    s.updatePrefs(() => ({ dayStartHour: 0 }));
    expect(s.today.value).toBe('2026-10-25');
  });

  it('exports and restores a backup', () => {
    const a = createStore(storage, time);
    a.restore();
    const id = a.subjectRepo.add('Maths', '#3987E5');
    a.taskRepo.add('Chapter 3', '2026-10-24', id);
    a.ddayRepo.add('Finals', '2026-11-23');
    a.startOrResume(id);
    now += 15 * MINUTE;
    a.dispatch(Commands.stop);
    const backup = JSON.parse(JSON.stringify(a.exportBackup()));

    const other = createStore(new MemoryStorage(), time);
    other.restore();
    other.importBackup(backup);
    expect(other.subjects.value.map((x) => x.name)).toEqual(['Maths']);
    expect(other.tasks.value).toHaveLength(1);
    expect(other.ddays.value).toHaveLength(1);
    expect(other.segments.value[0].durationMs).toBe(15 * MINUTE);
    // New ids continue after the restored ones.
    expect(other.subjectRepo.add('Physics', '#D95926')).toBe(id + 1);
    expect(() => other.importBackup({ app: 'something-else' })).toThrow(/backup/);
  });

  it('a full or blocked storage is reported, not thrown', () => {
    const full = new MemoryStorage();
    full.setItem = () => {
      throw new DOMException('quota', 'QuotaExceededError');
    };
    const s = createStore(full, time);
    s.restore();
    s.subjectRepo.add('Maths', '#3987E5');
    expect(s.storageError.value).toBe(true);
    expect(s.subjects.value).toHaveLength(1);
  });

  it('announces a boundary as fresh only if it just happened', () => {
    const s = createStore(storage, time);
    s.restore();
    s.updatePrefs(() => ({ preferPomodoro: true }));
    s.startOrResume(null);
    now += 25 * MINUTE + 1_000;
    s.dispatch(Commands.reconcile);
    expect(s.announcements.value.at(-1)).toMatchObject({ kind: 'phase', fresh: true });
    now += 60 * MINUTE;
    s.dispatch(Commands.reconcile);
    expect(s.announcements.value.at(-1)).toMatchObject({ kind: 'phase', fresh: false });
  });
});

describe('presence', () => {
  afterEach(() => vi.useRealTimers());

  it('publishes what Android publishes', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(at('2026-10-24T10:00:00+11:00'));
    const { store } = await import('../src/data/runtime');
    const { buildPresence } = await import('../src/groups/state');
    store.restore();
    const account = { uid: 'u1', email: 'a@b.c', displayName: 'Tymon' };
    expect(buildPresence(account)).toMatchObject({ state: 'IDLE', runningSinceMs: null, todayKey: '2026-10-24', weekKey: '2026-10-19', todayMs: 0 });

    store.startOrResume(null);
    vi.advanceTimersByTime(10 * MINUTE);
    const studying = buildPresence(account);
    expect(studying.state).toBe('STUDYING');
    expect(studying.subject).toBe('General');
    expect(studying.runningSinceMs).toBe(at('2026-10-24T10:00:00+11:00'));

    store.dispatch(Commands.pause);
    expect(buildPresence(account)).toMatchObject({ state: 'PAUSED', runningSinceMs: null, todayMs: 10 * MINUTE, weekMs: 10 * MINUTE });
  });
});

describe('firebase config', () => {
  it('accepts JSON or the console snippet', () => {
    const json = '{"apiKey":"k","authDomain":"p.firebaseapp.com","projectId":"p","appId":"1:2:web:3"}';
    expect(parseConfig(json)?.projectId).toBe('p');
    const snippet = `const firebaseConfig = {
  apiKey: "k",
  authDomain: "p.firebaseapp.com",
  projectId: "p",
  storageBucket: "p.firebasestorage.app",
  messagingSenderId: "2",
  appId: "1:2:web:3",
};`;
    expect(parseConfig(snippet)).toMatchObject({ apiKey: 'k', appId: '1:2:web:3', storageBucket: 'p.firebasestorage.app' });
    expect(parseConfig('')).toBeNull();
    expect(parseConfig('{"apiKey":"k"}')).toBeNull();
    expect(parseConfig(undefined)).toBeNull();
  });
});
