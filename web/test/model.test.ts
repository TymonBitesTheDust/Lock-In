// Port of core/model/src/test/.../StudyStatsTest.kt. Runs with TZ=Australia/Sydney (see vite.config.ts).
import { describe, expect, it } from 'vitest';
import {
  Durations,
  StudyDayClock,
  StudyStats,
  ddayLabel,
  nextSubjectColor,
  plusDays,
  plusMonths,
  sanitizePreferences,
  ACCENTS,
  ACCENT_NAMES,
  MAX_ALLOWED_APPS,
  isDarkTheme,
  isUrlLink,
  weekStart,
  type StudySegment,
} from '../src/core/model';

const clock = new StudyDayClock(5);

/** Local Sydney time, like LocalDateTime.parse(text).atZone(zone). */
function at(text: string): number {
  const [date, time] = text.split('T');
  const [y, m, d] = date.split('-').map(Number);
  const [hh, mm, ss] = time.split(':').map(Number);
  return new Date(y, m - 1, d, hh, mm, ss).getTime();
}

function segment(start: string, minutes: number, subjectId: number | null = 1, name = 'Maths', index = 0): StudySegment {
  return { sessionId: 's', index, subjectId, subjectName: name, startWallMs: at(start), durationMs: minutes * 60_000 };
}

describe('study days', () => {
  it('runs in the Sydney time zone', () => {
    expect(new Date(at('2026-10-24T12:00:00')).getTimezoneOffset()).toBe(-660);
  });

  it('a session after midnight belongs to the previous study day', () => {
    expect(clock.dayOf(at('2026-10-24T01:30:00'))).toBe('2026-10-23');
    expect(clock.dayOf(at('2026-10-24T05:00:00'))).toBe('2026-10-24');
  });

  it('segments crossing the day boundary are split exactly', () => {
    const totals = StudyStats.perDay([segment('2026-10-24T04:30:00', 60)], clock, '2026-10-23', '2026-10-24');
    expect(totals.get('2026-10-23')).toBe(30 * 60_000);
    expect(totals.get('2026-10-24')).toBe(30 * 60_000);
  });

  it('days without study are present with zero', () => {
    const totals = StudyStats.perDay([], clock, '2026-10-01', '2026-10-07');
    expect(totals.size).toBe(7);
    expect([...totals.values()].reduce((a, b) => a + b, 0)).toBe(0);
  });

  it('a DST change moves the instant, not the hour', () => {
    // Sydney springs forward at 02:00 on Sun 4 Oct 2026, inside the study day that starts Sat 3 Oct 05:00.
    expect(clock.endOf('2026-10-03') - clock.startOf('2026-10-03')).toBe(23 * 3_600_000);
  });
});

describe('aggregation', () => {
  it('per subject groups by id and keeps the latest name', () => {
    const result = StudyStats.perSubject(
      [
        segment('2026-10-24T09:00:00', 30, 1, 'Math'),
        segment('2026-10-24T10:00:00', 20, 1, 'Maths', 1),
        segment('2026-10-24T11:00:00', 40, 2, 'Physics', 2),
      ],
      at('2026-10-24T05:00:00'),
      at('2026-10-25T05:00:00'),
    );
    expect(result.map((t) => [t.name, t.ms])).toEqual([
      ['Maths', 50 * 60_000],
      ['Physics', 40 * 60_000],
    ]);
  });

  it('per hour splits at the hour', () => {
    const hours = StudyStats.perHour([segment('2026-10-24T09:45:00', 30)], 0, Number.MAX_SAFE_INTEGER);
    expect(hours[9]).toBe(15 * 60_000);
    expect(hours[10]).toBe(15 * 60_000);
  });

  it('streak survives an empty today and breaks on a gap', () => {
    const today = '2026-10-24';
    const perDay = new Map([
      [plusDays(today, -4), 3_600_000],
      [plusDays(today, -2), 3_600_000],
      [plusDays(today, -1), 120_000],
      [today, 0],
    ]);
    expect(StudyStats.currentStreak(perDay, today)).toBe(2);
    expect(StudyStats.bestStreak(perDay)).toBe(2);
  });
});

describe('formatting and helpers', () => {
  it('dday labels', () => {
    const today = '2026-10-24';
    expect([
      ddayLabel({ id: 1, title: 'Finals', date: plusDays(today, 12) }, today),
      ddayLabel({ id: 2, title: 'Essay', date: today }, today),
      ddayLabel({ id: 3, title: 'Quiz', date: plusDays(today, -3) }, today),
    ]).toEqual(['D-12', 'D-Day', 'D+3']);
  });

  it('duration formats', () => {
    expect(Durations.short(3 * 3_600_000 + 5 * 60_000 + 59_000)).toBe('3h 05m');
    expect(Durations.short(42 * 60_000)).toBe('42m');
    expect(Durations.clock(3_723_000)).toBe('1:02:03');
  });

  it('weeks start on Monday and months clamp their day', () => {
    expect(weekStart('2026-10-25')).toBe('2026-10-19');
    expect(weekStart('2026-10-19')).toBe('2026-10-19');
    expect(plusMonths('2026-01-31', 1)).toBe('2026-02-28');
    expect(plusMonths('2026-03-15', -3)).toBe('2025-12-15');
  });

  it('subject colours take the next unused slot', () => {
    expect(nextSubjectColor([])).toBe('#3987E5');
    expect(nextSubjectColor(['#3987e5'])).toBe('#D95926');
  });

  it('preferences are clamped like on Android', () => {
    const p = sanitizePreferences({ pomodoroFocusMin: 999, dayStartHour: -3, autoStartBreaks: 'yes' as unknown as boolean });
    expect(p.pomodoroFocusMin).toBe(180);
    expect(p.dayStartHour).toBe(0);
    expect(p.autoStartBreaks).toBe(true);
  });
});

describe('appearance and allowed apps', () => {
  const lum = (hex: string) => {
    const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  };
  const contrast = (a: string, b: string) => {
    const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
    return (x + 0.05) / (y + 0.05);
  };

  it('defaults to the original blue, following the device', () => {
    const p = sanitizePreferences(null);
    expect(p.themeMode).toBe('system');
    expect(p.accent).toBe('blue');
    expect(p.allowedApps).toEqual([]);
  });

  it('keeps a valid theme and accent, and drops unknown ones', () => {
    expect(sanitizePreferences({ themeMode: 'dark', accent: 'orange' })).toMatchObject({ themeMode: 'dark', accent: 'orange' });
    expect(sanitizePreferences({ themeMode: 'sepia' as never, accent: 'toString' as never })).toMatchObject({ themeMode: 'system', accent: 'blue' });
  });

  it('resolves dark from the theme and the device', () => {
    expect(isDarkTheme('system', true)).toBe(true);
    expect(isDarkTheme('system', false)).toBe(false);
    expect(isDarkTheme('light', true)).toBe(false);
    expect(isDarkTheme('dark', false)).toBe(true);
  });

  it('every accent keeps readable contrast in light and dark', () => {
    const surfaces = { light: '#EDEFF3', dark: '#171B21' };
    for (const name of ACCENT_NAMES) {
      for (const mode of ['light', 'dark'] as const) {
        const t = ACCENTS[name][mode];
        expect(contrast(t.onPrimary, t.primary), `${name} ${mode} button text`).toBeGreaterThanOrEqual(4.5);
        expect(contrast(t.primary, surfaces[mode]), `${name} ${mode} accent on surface`).toBeGreaterThanOrEqual(3);
        expect(contrast(t.onSelection, t.selection), `${name} ${mode} selected text`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('cleans up allowed apps: trims, drops empty and unsafe links, caps the list', () => {
    const p = sanitizePreferences({
      allowedApps: [
        { name: '  Spotify ', link: ' spotify:// ' },
        { name: 'Bad', link: 'javascript:alert(1)' },
        { name: '', link: 'music://' },
        { name: 'Checkmate', link: 'Open Checkmate' },
        'nonsense' as never,
        ...Array.from({ length: 20 }, (_, i) => ({ name: `App ${i}`, link: `app${i}://` })),
      ],
    });
    expect(p.allowedApps[0]).toEqual({ name: 'Spotify', link: 'spotify://' });
    expect(p.allowedApps[1]).toEqual({ name: 'Checkmate', link: 'Open Checkmate' });
    expect(p.allowedApps.some((a) => a.link.startsWith('javascript'))).toBe(false);
    expect(p.allowedApps).toHaveLength(MAX_ALLOWED_APPS);
  });

  it('tells links from Shortcut names', () => {
    expect(isUrlLink('spotify://')).toBe(true);
    expect(isUrlLink('https://example.com')).toBe(true);
    expect(isUrlLink('Open Checkmate')).toBe(false);
  });
});
