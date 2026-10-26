/**
 * Domain models and study-time maths, ported from `core/model` (Kotlin).
 * Calendar dates are ISO strings (`2026-10-24`): they compare correctly as strings and
 * serialise as themselves. All "local time" means the device's time zone.
 */

// region Dates

export type LocalDate = string;

const DAY_MS = 86_400_000;
const pad2 = (n: number) => String(n).padStart(2, '0');

function parts(date: LocalDate): [number, number, number] {
  const [y, m, d] = date.split('-').map(Number);
  return [y, m, d];
}

export function toEpochDay(date: LocalDate): number {
  const [y, m, d] = parts(date);
  return Math.round(Date.UTC(y, m - 1, d) / DAY_MS);
}

export function fromEpochDay(day: number): LocalDate {
  const t = new Date(day * DAY_MS);
  return `${t.getUTCFullYear()}-${pad2(t.getUTCMonth() + 1)}-${pad2(t.getUTCDate())}`;
}

export const plusDays = (date: LocalDate, n: number): LocalDate => fromEpochDay(toEpochDay(date) + n);

export const daysBetween = (from: LocalDate, to: LocalDate): number => toEpochDay(to) - toEpochDay(from);

/** 1 = Monday … 7 = Sunday, like `java.time.DayOfWeek`. */
export function dayOfWeek(date: LocalDate): number {
  const dow = new Date(toEpochDay(date) * DAY_MS).getUTCDay();
  return dow === 0 ? 7 : dow;
}

export const weekStart = (date: LocalDate): LocalDate => plusDays(date, 1 - dayOfWeek(date));

export function monthStart(date: LocalDate): LocalDate {
  const [y, m] = parts(date);
  return `${y}-${pad2(m)}-01`;
}

export function lengthOfMonth(date: LocalDate): number {
  const [y, m] = parts(date);
  return new Date(Date.UTC(y, m, 0)).getUTCDate();
}

export function plusMonths(date: LocalDate, n: number): LocalDate {
  const [y, m, d] = parts(date);
  const first = new Date(Date.UTC(y, m - 1 + n, 1));
  const ny = first.getUTCFullYear();
  const nm = first.getUTCMonth() + 1;
  const last = new Date(Date.UTC(ny, nm, 0)).getUTCDate();
  return `${ny}-${pad2(nm)}-${pad2(Math.min(d, last))}`;
}

export const dayOfMonth = (date: LocalDate): number => parts(date)[2];

/** A `Date` at local noon of `date`, for formatting with `Intl`. */
export function toLocalNoon(date: LocalDate): Date {
  const [y, m, d] = parts(date);
  return new Date(y, m - 1, d, 12);
}

export function localDateOf(wallMs: number): LocalDate {
  const t = new Date(wallMs);
  return `${t.getFullYear()}-${pad2(t.getMonth() + 1)}-${pad2(t.getDate())}`;
}

export function formatDate(date: LocalDate, options: Intl.DateTimeFormatOptions): string {
  return new Intl.DateTimeFormat(undefined, options).format(toLocalNoon(date));
}

export function formatTime(wallMs: number): string {
  const t = new Date(wallMs);
  return `${pad2(t.getHours())}:${pad2(t.getMinutes())}`;
}

/**
 * A study day runs from `startHour`:00 local time to the same hour the next day (default 05:00),
 * so a late-night session counts for the evening it belongs to. "Daily reset" is this function.
 */
export class StudyDayClock {
  constructor(readonly startHour: number) {
    if (!(startHour >= 0 && startHour <= 23)) throw new Error('startHour must be 0..23');
  }

  dayOf(wallMs: number): LocalDate {
    const t = new Date(wallMs);
    // Subtract hours on the local date-time (not the instant), like LocalDateTime.minusHours.
    const shifted = new Date(Date.UTC(t.getFullYear(), t.getMonth(), t.getDate(), t.getHours() - this.startHour, t.getMinutes()));
    return `${shifted.getUTCFullYear()}-${pad2(shifted.getUTCMonth() + 1)}-${pad2(shifted.getUTCDate())}`;
  }

  /** First instant of `day`. Local time, so DST shifts the instant, not the hour. */
  startOf(day: LocalDate): number {
    const [y, m, d] = parts(day);
    return new Date(y, m - 1, d, this.startHour, 0, 0, 0).getTime();
  }

  endOf(day: LocalDate): number {
    return this.startOf(plusDays(day, 1));
  }
}

// endregion

// region Models

export interface Subject {
  readonly id: number;
  readonly name: string;
  /** CSS colour, `#RRGGBB`. */
  readonly color: string;
  readonly archived?: boolean;
  readonly order: number;
}

export interface StudyTask {
  readonly id: number;
  readonly title: string;
  readonly date: LocalDate;
  readonly subjectId: number | null;
  readonly done: boolean;
  readonly createdAtMs: number;
}

/** An exam, deadline or milestone the user is counting down to. */
export interface DDay {
  readonly id: number;
  readonly title: string;
  readonly date: LocalDate;
}

/** Positive before the date, 0 on the day, negative after. */
export const ddayDaysFrom = (dday: DDay, today: LocalDate): number => daysBetween(today, dday.date);

/** `D-12`, `D-Day` or `D+3`. */
export function ddayLabel(dday: DDay, today: LocalDate): string {
  const days = ddayDaysFrom(dday, today);
  if (days > 0) return `D-${days}`;
  if (days === 0) return 'D-Day';
  return `D+${-days}`;
}

/** A closed stretch of focus as stored. */
export interface StudySegment {
  readonly sessionId: string;
  readonly index: number;
  readonly subjectId: number | null;
  readonly subjectName: string;
  readonly startWallMs: number;
  readonly durationMs: number;
  readonly endedBy?: string;
}

/** Defined from the duration, never from a second wall-clock reading. */
export const segmentEnd = (s: StudySegment): number => s.startWallMs + s.durationMs;

// region Appearance

export type ThemeMode = 'system' | 'light' | 'dark';
export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark'];

/** The accent-coloured parts of one theme. Surfaces and text stay neutral in every accent. */
export interface AccentTones {
  readonly primary: string;
  readonly onPrimary: string;
  readonly primaryContainer: string;
  readonly onPrimaryContainer: string;
  /** Selection containers: segmented buttons, tonal buttons. */
  readonly selection: string;
  readonly onSelection: string;
  /** Heatmap steps from least to most study time. */
  readonly heat: readonly [string, string, string, string];
}

export type AccentName = 'blue' | 'orange' | 'green' | 'teal' | 'purple' | 'pink';

/**
 * Same values as Android (core/model Appearance.kt). Blue is the original palette; every other
 * accent is Blue with only the hue changed, so contrast is the same in light and dark.
 */
export const ACCENTS: Readonly<Record<AccentName, { readonly label: string; readonly swatch: string; readonly light: AccentTones; readonly dark: AccentTones }>> = {
  blue: {
    label: 'Blue',
    swatch: '#3987E5',
    light: { primary: '#2F5E9E', onPrimary: '#FFFFFF', primaryContainer: '#D5E3FF', onPrimaryContainer: '#0B1D36', selection: '#DCE6F5', onSelection: '#0B1D36', heat: ['#9EC5F4', '#6DA7EC', '#3987E5', '#1C5CAB'] },
    dark: { primary: '#9DC3FF', onPrimary: '#0B1D36', primaryContainer: '#1E3554', onPrimaryContainer: '#D5E3FF', selection: '#243447', onSelection: '#D5E3FF', heat: ['#184F95', '#256ABF', '#3987E5', '#86B6EF'] },
  },
  orange: {
    label: 'Orange',
    swatch: '#D36800',
    light: { primary: '#964800', onPrimary: '#FFFFFF', primaryContainer: '#FFDBC7', onPrimaryContainer: '#331400', selection: '#FFDFCD', onSelection: '#331400', heat: ['#FFB07C', '#F0893D', '#D36800', '#944700'] },
    dark: { primary: '#FFAF7B', onPrimary: '#331400', primaryContainer: '#542907', onPrimaryContainer: '#FFDBC7', selection: '#4B2B15', onSelection: '#FFDBC7', heat: ['#813D00', '#A95200', '#D36800', '#FA9C5B'] },
  },
  green: {
    label: 'Green',
    swatch: '#419841',
    light: { primary: '#306A2E', onPrimary: '#FFFFFF', primaryContainer: '#D1E9C9', onPrimaryContainer: '#0B220A', selection: '#DCE9D5', onSelection: '#0B220A', heat: ['#A0CE97', '#72B36B', '#419841', '#216A24'] },
    dark: { primary: '#99CF90', onPrimary: '#0B220A', primaryContainer: '#1E3B1C', onPrimaryContainer: '#D1E9C9', selection: '#253722', onSelection: '#D1E9C9', heat: ['#1B5C1E', '#2B792D', '#419841', '#89C081'] },
  },
  teal: {
    label: 'Teal',
    swatch: '#009694',
    light: { primary: '#006968', onPrimary: '#FFFFFF', primaryContainer: '#C4EAE8', onPrimaryContainer: '#002121', selection: '#D5E9E8', onSelection: '#002121', heat: ['#81CFCD', '#23B6B3', '#009694', '#006866'] },
    dark: { primary: '#71D1CE', onPrimary: '#002121', primaryContainer: '#003B3A', onPrimaryContainer: '#C4EAE8', selection: '#163837', onSelection: '#C4EAE8', heat: ['#005A58', '#007775', '#009694', '#5DC2C0'] },
  },
  purple: {
    label: 'Purple',
    swatch: '#9373E2',
    light: { primary: '#67519B', onPrimary: '#FFFFFF', primaryContainer: '#E9DDFE', onPrimaryContainer: '#201835', selection: '#EBE2F6', onSelection: '#201835', heat: ['#CBB8F5', '#AF96EC', '#9373E2', '#674CA7'] },
    dark: { primary: '#CCB6FE', onPrimary: '#201835', primaryContainer: '#392E53', onPrimaryContainer: '#E9DDFE', selection: '#362F47', onSelection: '#E9DDFE', heat: ['#594192', '#7658BB', '#9373E2', '#BDA7F0'] },
  },
  pink: {
    label: 'Pink',
    swatch: '#D15F94',
    light: { primary: '#914367', onPrimary: '#FFFFFF', primaryContainer: '#FFD9E5', onPrimaryContainer: '#311320', selection: '#FCDDE7', onSelection: '#311320', heat: ['#F7ACC9', '#E686AF', '#D15F94', '#973C67'] },
    dark: { primary: '#FEA8CA', onPrimary: '#311320', primaryContainer: '#502739', onPrimaryContainer: '#FFD9E5', selection: '#472A36', onSelection: '#FFD9E5', heat: ['#833359', '#AB4776', '#D15F94', '#EF99BC'] },
  },
};

export const ACCENT_NAMES = Object.keys(ACCENTS) as AccentName[];

export function isDarkTheme(mode: ThemeMode, systemDark: boolean): boolean {
  return mode === 'dark' || (mode === 'system' && systemDark);
}

/**
 * An app the user allows during focus. iPhone web apps can't see or open other apps by name, so
 * each one is a link: an app's URL scheme (spotify://), a website, or the name of a Shortcut that
 * opens the app.
 */
export interface AllowedAppLink {
  readonly name: string;
  readonly link: string;
}

export const MAX_ALLOWED_APPS = 12;

/** Apps whose links are well known, offered as one-tap picks. */
export const ALLOWED_APP_PRESETS: readonly AllowedAppLink[] = [
  { name: 'Music', link: 'music://' },
  { name: 'Notes', link: 'mobilenotes://' },
  { name: 'Calendar', link: 'calshow://' },
  { name: 'Books', link: 'ibooks://' },
  { name: 'Files', link: 'shareddocuments://' },
  { name: 'Spotify', link: 'spotify://' },
  { name: 'Notion', link: 'notion://' },
];

/** A link with a scheme (spotify://, https://…) opens directly; anything else is a Shortcut name. */
export function isUrlLink(link: string): boolean {
  return /^[a-z][a-z0-9+.-]*:/i.test(link.trim());
}

function sanitizeAllowedApps(v: unknown): AllowedAppLink[] {
  if (!Array.isArray(v)) return [];
  const result: AllowedAppLink[] = [];
  for (const item of v) {
    if (!item || typeof item !== 'object') continue;
    const { name, link } = item as { name?: unknown; link?: unknown };
    if (typeof name !== 'string' || typeof link !== 'string') continue;
    const n = name.trim().slice(0, 40);
    const l = link.trim().slice(0, 300);
    // javascript: and data: links would run code or load content inside Lock In.
    if (!n || !l || /^(javascript|data|vbscript):/i.test(l)) continue;
    result.push({ name: n, link: l });
    if (result.length >= MAX_ALLOWED_APPS) break;
  }
  return result;
}

// endregion

export interface StudyPreferences {
  readonly pomodoroFocusMin: number;
  readonly shortBreakMin: number;
  readonly longBreakMin: number;
  readonly longBreakEvery: number;
  readonly autoStartBreaks: boolean;
  readonly autoStartFocus: boolean;
  /** Local hour a study day starts at (0..23). */
  readonly dayStartHour: number;
  /** Which mode the subject play buttons start. */
  readonly preferPomodoro: boolean;
  /** Ask the browser to keep the screen on while a session runs. */
  readonly keepScreenOn: boolean;
  /** Play a chime when a Pomodoro phase ends while Lock In is open. */
  readonly chime: boolean;
  /** iPhone Shortcuts to run when a session starts and stops (empty = off). */
  readonly shortcutOnStart: string;
  readonly shortcutOnStop: string;
  /** Light, dark or follow the device. */
  readonly themeMode: ThemeMode;
  /** Accent colour for the whole app. */
  readonly accent: AccentName;
  /** Shortcuts to the apps the user allows, shown on the timer during focus. */
  readonly allowedApps: readonly AllowedAppLink[];
}

export const DEFAULT_PREFERENCES: StudyPreferences = {
  pomodoroFocusMin: 25,
  shortBreakMin: 5,
  longBreakMin: 15,
  longBreakEvery: 4,
  autoStartBreaks: true,
  autoStartFocus: false,
  dayStartHour: 5,
  preferPomodoro: false,
  keepScreenOn: false,
  chime: true,
  shortcutOnStart: '',
  shortcutOnStop: '',
  themeMode: 'system',
  accent: 'blue',
  allowedApps: [],
};

const clamp = (v: unknown, lo: number, hi: number, fallback: number) => {
  const n = typeof v === 'number' && Number.isFinite(v) ? Math.round(v) : fallback;
  return Math.min(Math.max(n, lo), hi);
};

/** Same limits as the Android preferences store. */
export function sanitizePreferences(p: Partial<StudyPreferences> | null | undefined): StudyPreferences {
  const d = DEFAULT_PREFERENCES;
  const v = p ?? {};
  const bool = (x: unknown, fb: boolean) => (typeof x === 'boolean' ? x : fb);
  const str = (x: unknown) => (typeof x === 'string' ? x.slice(0, 80) : '');
  return {
    pomodoroFocusMin: clamp(v.pomodoroFocusMin, 1, 180, d.pomodoroFocusMin),
    shortBreakMin: clamp(v.shortBreakMin, 1, 60, d.shortBreakMin),
    longBreakMin: clamp(v.longBreakMin, 1, 90, d.longBreakMin),
    longBreakEvery: clamp(v.longBreakEvery, 1, 12, d.longBreakEvery),
    autoStartBreaks: bool(v.autoStartBreaks, d.autoStartBreaks),
    autoStartFocus: bool(v.autoStartFocus, d.autoStartFocus),
    dayStartHour: clamp(v.dayStartHour, 0, 23, d.dayStartHour),
    preferPomodoro: bool(v.preferPomodoro, d.preferPomodoro),
    keepScreenOn: bool(v.keepScreenOn, d.keepScreenOn),
    chime: bool(v.chime, d.chime),
    shortcutOnStart: str(v.shortcutOnStart),
    shortcutOnStop: str(v.shortcutOnStop),
    themeMode: THEME_MODES.includes(v.themeMode as ThemeMode) ? (v.themeMode as ThemeMode) : d.themeMode,
    accent: v.accent && Object.hasOwn(ACCENTS, v.accent) ? (v.accent as AccentName) : d.accent,
    allowedApps: sanitizeAllowedApps(v.allowedApps),
  };
}

/**
 * Categorical hues validated on the dark surface (#0F1216), same order as Android.
 * New subjects take the next unused slot; colours follow the subject, never its rank.
 */
export const SUBJECT_PALETTE = ['#3987E5', '#D95926', '#199E70', '#C98500', '#D55181', '#008300', '#9085E9', '#E66767'];

export function nextSubjectColor(used: readonly string[]): string {
  const upper = used.map((c) => c.toUpperCase());
  return SUBJECT_PALETTE.find((c) => !upper.includes(c)) ?? SUBJECT_PALETTE[used.length % SUBJECT_PALETTE.length];
}

export const GENERAL = 'General';
export const GENERAL_COLOR = '#8A9099';

// endregion

// region Stats

export interface SubjectTotal {
  readonly subjectId: number | null;
  readonly name: string;
  readonly ms: number;
}

/** A day counts towards a streak from one minute of focus. */
export const MIN_STREAK_MS = 60_000;

/** Pure aggregation over focus segments. Segments that cross a boundary are split, never rounded. */
export const StudyStats = {
  /** Milliseconds of `segment` inside `[startMs, endMs)`. */
  overlap(segment: StudySegment, startMs: number, endMs: number): number {
    return Math.max(Math.min(segmentEnd(segment), endMs) - Math.max(segment.startWallMs, startMs), 0);
  },

  total(segments: readonly StudySegment[], startMs: number, endMs: number): number {
    let sum = 0;
    for (const s of segments) sum += StudyStats.overlap(s, startMs, endMs);
    return sum;
  },

  /** Total per study day for every day in `[from, to]`, including days with nothing. */
  perDay(segments: readonly StudySegment[], clock: StudyDayClock, from: LocalDate, to: LocalDate): Map<LocalDate, number> {
    const result = new Map<LocalDate, number>();
    for (let day = from; day <= to; day = plusDays(day, 1)) result.set(day, 0);
    for (const segment of segments) {
      let d = clock.dayOf(segment.startWallMs);
      const end = segmentEnd(segment);
      for (;;) {
        const start = clock.startOf(d);
        if (start >= end) break;
        const part = StudyStats.overlap(segment, start, clock.endOf(d));
        const current = result.get(d);
        if (part > 0 && current !== undefined) result.set(d, current + part);
        d = plusDays(d, 1);
      }
    }
    return result;
  },

  /** Totals per subject inside the range, largest first. Keyed by id, falling back to name. */
  perSubject(segments: readonly StudySegment[], startMs: number, endMs: number): SubjectTotal[] {
    const groups = new Map<string, StudySegment[]>();
    for (const s of segments) {
      const key = s.subjectId !== null ? `id:${s.subjectId}` : `name:${s.subjectName}`;
      const list = groups.get(key);
      if (list) list.push(s);
      else groups.set(key, [s]);
    }
    const totals: SubjectTotal[] = [];
    for (const list of groups.values()) {
      const latest = list.reduce((a, b) => (b.startWallMs > a.startWallMs ? b : a));
      totals.push({ subjectId: list[0].subjectId, name: latest.subjectName, ms: StudyStats.total(list, startMs, endMs) });
    }
    return totals.filter((t) => t.ms > 0).sort((a, b) => b.ms - a.ms);
  },

  /** Time per local clock hour (0..23) inside the range. */
  perHour(segments: readonly StudySegment[], startMs: number, endMs: number): number[] {
    const hours = new Array<number>(24).fill(0);
    for (const segment of segments) {
      let cursor = Math.max(segment.startWallMs, startMs);
      const end = Math.min(segmentEnd(segment), endMs);
      while (cursor < end) {
        const local = new Date(cursor);
        const hourStart = new Date(cursor);
        hourStart.setMinutes(0, 0, 0);
        const stop = Math.min(hourStart.getTime() + 3_600_000, end);
        hours[local.getHours()] += stop - cursor;
        cursor = stop;
      }
    }
    return hours;
  },

  /** Consecutive days with focus, ending today, or yesterday when today has nothing yet. */
  currentStreak(perDay: Map<LocalDate, number>, today: LocalDate, minMs = MIN_STREAK_MS): number {
    let day = (perDay.get(today) ?? 0) >= minMs ? today : plusDays(today, -1);
    let streak = 0;
    while ((perDay.get(day) ?? 0) >= minMs) {
      streak++;
      day = plusDays(day, -1);
    }
    return streak;
  },

  bestStreak(perDay: Map<LocalDate, number>, minMs = MIN_STREAK_MS): number {
    let best = 0;
    let run = 0;
    let previous: LocalDate | null = null;
    for (const day of [...perDay.keys()].sort()) {
      const studied = (perDay.get(day) ?? 0) >= minMs;
      if (!studied) run = 0;
      else if (previous !== null && plusDays(previous, 1) === day) run += 1;
      else run = 1;
      best = Math.max(best, run);
      previous = day;
    }
    return best;
  },
};

export const Durations = {
  /** `3h 05m`, `42m`, `0m`. */
  short(ms: number): string {
    const totalMinutes = Math.floor(Math.max(ms, 0) / 60_000);
    const h = Math.floor(totalMinutes / 60);
    const m = totalMinutes % 60;
    return h > 0 ? `${h}h ${pad2(m)}m` : `${m}m`;
  },

  /** `3:05:09`, always with hours. */
  clock(ms: number): string {
    const total = Math.floor(Math.max(ms, 0) / 1_000);
    return `${Math.floor(total / 3_600)}:${pad2(Math.floor((total % 3_600) / 60))}:${pad2(total % 60)}`;
  },
};

// endregion
