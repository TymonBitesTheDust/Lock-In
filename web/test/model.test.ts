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

  it('per hour survives the repeated hour when DST ends', () => {
    // Sydney falls back at 03:00 on Sun 5 Apr 2026: 02:00–03:00 happens twice.
    const start = new Date(2026, 3, 5, 1, 30).getTime();
    const seg: StudySegment = { sessionId: 's', index: 0, subjectId: 1, subjectName: 'Maths', startWallMs: start, durationMs: 150 * 60_000 };
    const hours = StudyStats.perHour([seg], 0, Number.MAX_SAFE_INTEGER);
    expect(hours[1]).toBe(30 * 60_000);
    expect(hours[2]).toBe(120 * 60_000);
    expect(hours.reduce((a, b) => a + b, 0)).toBe(150 * 60_000);
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
