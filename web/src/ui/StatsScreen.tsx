import { useState } from 'preact/hooks';
import {
  Durations,
  GENERAL_COLOR,
  StudyStats,
  dayOfMonth,
  formatDate,
  lengthOfMonth,
  monthStart,
  plusDays,
  plusMonths,
  weekStart,
  type LocalDate,
} from '../core/model';
import { store } from '../data/runtime';
import { liveSegment } from '../data/store';
import { Section } from './common';
import { useInterval } from './hooks';
import { Icon } from './icons';

type Range = 'DAY' | 'WEEK' | 'MONTH';
const HEATMAP_WEEKS = 18;

function bounds(range: Range, anchor: LocalDate): [LocalDate, LocalDate] {
  if (range === 'DAY') return [anchor, anchor];
  if (range === 'WEEK') {
    const ws = weekStart(anchor);
    return [ws, plusDays(ws, 6)];
  }
  const ms = monthStart(anchor);
  return [ms, plusDays(ms, lengthOfMonth(ms) - 1)];
}

export function StatsScreen() {
  const [range, setRange] = useState<Range>('WEEK');
  const [anchorPick, setAnchor] = useState<LocalDate | null>(null);
  const [heatPick, setHeatPick] = useState<LocalDate | null>(null);
  const timer = store.timer.value;
  const now = useInterval(30_000, timer.kind === 'active');

  const today = store.today.value;
  const clock = store.clock.value;
  const anchor = anchorPick ?? today;
  const [from, to] = bounds(range, anchor);
  const heatFrom = plusDays(weekStart(today), -7 * (HEATMAP_WEEKS - 1));
  const queryFrom = from < heatFrom ? from : heatFrom;
  const queryTo = to > today ? to : today;
  const stored = store.segmentsBetween(clock.startOf(queryFrom), clock.endOf(queryTo));
  const live = liveSegment(timer, now);
  const segments = live ? [...stored, live] : stored;
  const startMs = clock.startOf(from);
  const endMs = clock.endOf(to);
  const perDay = StudyStats.perDay(segments, clock, from, to);
  const total = StudyStats.total(segments, startMs, endMs);

  let bars: number[];
  let labels: string[];
  let emphasis: number | null = null;
  let tiles: [string, string][];
  if (range === 'DAY') {
    const hours = StudyStats.perHour(segments, startMs, endMs);
    bars = Array.from({ length: 24 }, (_, i) => hours[(clock.startHour + i) % 24]);
    labels = Array.from({ length: 24 }, (_, i) => (i % 6 === 0 ? String((clock.startHour + i) % 24) : ''));
    const inDay = segments.filter((s) => StudyStats.overlap(s, startMs, endMs) > 0);
    tiles = [
      ['Total', Durations.short(total)],
      ['Sessions', String(new Set(inDay.map((s) => s.sessionId)).size)],
      ['Longest stretch', Durations.short(inDay.reduce((m, s) => Math.max(m, s.durationMs), 0))],
    ];
  } else {
    const days = [...perDay.keys()];
    bars = [...perDay.values()];
    labels = days.map((d) =>
      range === 'WEEK' ? formatDate(d, { weekday: 'narrow' }) : dayOfMonth(d) === 1 || dayOfMonth(d) % 5 === 0 ? String(dayOfMonth(d)) : '',
    );
    const ti = days.indexOf(today);
    emphasis = ti >= 0 ? ti : null;
    const elapsed = Math.max(days.filter((d) => d <= today).length, 1);
    let best: [LocalDate, number] | null = null;
    for (const [d, ms] of perDay) if (ms > 0 && (!best || ms > best[1])) best = [d, ms];
    tiles = [
      ['Total', Durations.short(total)],
      ['Daily average', Durations.short(total / elapsed)],
      ['Best day', best ? `${Durations.short(best[1])} · ${formatDate(best[0], { weekday: 'short', day: 'numeric' })}` : '–'],
    ];
  }

  const bySubject = StudyStats.perSubject(segments, startMs, endMs);
  const subjectTotal = Math.max(bySubject.reduce((a, t) => a + t.ms, 0), 1);
  const byId = store.subjectsById.value;
  const heat = StudyStats.perDay(segments, clock, heatFrom, today);
  const containsToday = today >= from && today <= to;
  const title =
    range === 'DAY'
      ? from === today
        ? 'Today'
        : formatDate(from, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
      : range === 'WEEK'
        ? containsToday
          ? 'This week'
          : `${formatDate(from, { day: 'numeric', month: 'short' })} – ${formatDate(to, { day: 'numeric', month: 'short' })}`
        : containsToday
          ? 'This month'
          : formatDate(from, { month: 'long', year: 'numeric' });

  const shift = (steps: number) => {
    const next = range === 'DAY' ? plusDays(anchor, steps) : range === 'WEEK' ? plusDays(anchor, 7 * steps) : plusMonths(anchor, steps);
    setAnchor(next > today ? null : next);
  };

  return (
    <div class="screen">
      <h1 class="screen-title">Stats</h1>
      <div class="segmented" role="group" aria-label="Range">
        {(['DAY', 'WEEK', 'MONTH'] as Range[]).map((r) => (
          <button
            type="button"
            key={r}
            aria-pressed={range === r}
            onClick={() => {
              setRange(r);
              setAnchor(null);
            }}
          >
            {range === r && <Icon name="check" />}
            {r === 'DAY' ? 'Day' : r === 'WEEK' ? 'Week' : 'Month'}
          </button>
        ))}
      </div>

      <div class="weeknav" style="margin-top:12px">
        <button type="button" class="icon-btn" aria-label="Previous" onClick={() => shift(-1)}>
          <Icon name="back" />
        </button>
        <span class="label">{title}</span>
        <button type="button" class="icon-btn" aria-label="Next" disabled={!(to < today)} style={to < today ? '' : 'opacity:0.3'} onClick={() => shift(1)}>
          <Icon name="forward" />
        </button>
      </div>

      <div class="tiles">
        {tiles.map(([label, value]) => (
          <div class="tile" key={label}>
            <div class="label">{label}</div>
            <div class="value">{value}</div>
          </div>
        ))}
      </div>

      <div class="card chart" style="margin-top:12px">
        <div style="font-weight:600;font-size:14px;margin-bottom:6px">{range === 'DAY' ? 'By hour' : 'By day'}</div>
        <BarChart values={bars} labels={labels} emphasis={emphasis} />
      </div>

      <Section title="By subject" />
      <div class="card">
        {bySubject.length === 0 ? (
          <div class="muted small">No study time in this period yet.</div>
        ) : (
          bySubject.map((t) => {
            const subject = t.subjectId !== null ? byId.get(t.subjectId) : undefined;
            const color = subject?.color ?? GENERAL_COLOR;
            const share = t.ms / subjectTotal;
            return (
              <div class="subject-stat" key={`${t.subjectId}:${t.name}`}>
                <div class="top">
                  <span style="display:inline-flex;align-items:center;gap:8px">
                    <span class="dot" style={{ background: color }} />
                    {subject?.name ?? t.name}
                  </span>
                  <span class="muted tnum">
                    {Durations.short(t.ms)} · {Math.round(share * 100)}%
                  </span>
                </div>
                <div class="bar" style={{ background: color, width: `${Math.max(share * 100, 1)}%` }} />
              </div>
            );
          })
        )}
      </div>

      <Section title="Consistency" />
      <div class="card">
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:8px">
          <div>
            <div class="small muted">Current streak</div>
            <div style="font-size:22px;font-weight:600">{plural(StudyStats.currentStreak(heat, today), 'day')}</div>
          </div>
          <div>
            <div class="small muted">Best streak ({HEATMAP_WEEKS} weeks)</div>
            <div style="font-size:22px;font-weight:600">{plural(StudyStats.bestStreak(heat), 'day')}</div>
          </div>
        </div>
        <Heatmap perDay={heat} from={heatFrom} today={today} selected={heatPick} onSelect={setHeatPick} />
        <div class="small muted" style="display:flex;justify-content:space-between;align-items:center;margin-top:8px">
          <span>{heatPick ? `${formatDate(heatPick, { weekday: 'short', day: 'numeric', month: 'short' })}: ${Durations.short(heat.get(heatPick) ?? 0)}` : 'Tap a day for its total'}</span>
          <span style="display:inline-flex;align-items:center;gap:3px">
            Less
            {[0, 1, 2, 3, 4].map((l) => (
              <span key={l} style={{ width: '10px', height: '10px', borderRadius: '2px', background: `var(--heat-${l})` }} />
            ))}
            4h+
          </span>
        </div>
      </div>
    </div>
  );
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

function niceMax(ms: number): number {
  const steps = [15, 30, 60, 120, 180, 240, 360, 480, 600, 720, 960, 1440].map((m) => m * 60_000);
  return steps.find((s) => s >= ms) ?? Math.ceil(ms / 3_600_000) * 3_600_000;
}

function BarChart({ values, labels, emphasis }: { values: number[]; labels: string[]; emphasis: number | null }) {
  const W = 340;
  const H = 150;
  const top = 18;
  const bottom = 20;
  const max = niceMax(Math.max(...values, 1));
  const n = values.length;
  const slot = W / n;
  const barW = Math.max(Math.min(slot * 0.6, 28), 2);
  const plotH = H - top - bottom;
  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Bar chart, highest ${Durations.short(Math.max(...values, 0))}`}>
      <line x1="0" x2={W} y1={top} y2={top} stroke="var(--outline-variant)" stroke-dasharray="3 3" stroke-width="0.5" />
      <line x1="0" x2={W} y1={top + plotH} y2={top + plotH} stroke="var(--outline-variant)" stroke-width="0.5" />
      <text x="0" y={top - 5}>
        {Durations.short(max)}
      </text>
      {values.map((v, i) => {
        const h = v > 0 ? Math.max((v / max) * plotH, 2) : 0;
        const x = i * slot + (slot - barW) / 2;
        return (
          <g key={i}>
            {h > 0 && (
              <rect x={x} y={top + plotH - h} width={barW} height={h} rx={Math.min(3, barW / 2)} fill="var(--primary)" opacity={emphasis === null || emphasis === i ? 1 : 0.55}>
                <title>{Durations.short(v)}</title>
              </rect>
            )}
            {labels[i] && (
              <text x={i * slot + slot / 2} y={H - 5} text-anchor="middle" style={emphasis === i ? 'font-weight:700;fill:var(--on-bg)' : ''}>
                {labels[i]}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

function level(ms: number): number {
  if (ms < 60_000) return 0;
  if (ms < 3_600_000) return 1;
  if (ms < 2 * 3_600_000) return 2;
  if (ms < 4 * 3_600_000) return 3;
  return 4;
}

function Heatmap({ perDay, from, today, selected, onSelect }: { perDay: Map<LocalDate, number>; from: LocalDate; today: LocalDate; selected: LocalDate | null; onSelect: (d: LocalDate) => void }) {
  const cell = 15;
  const gap = 3;
  const left = 16;
  const topPad = 14;
  const W = left + HEATMAP_WEEKS * (cell + gap);
  const H = topPad + 7 * (cell + gap);
  const months: { x: number; label: string }[] = [];
  let lastMonth = '';
  for (let w = 0; w < HEATMAP_WEEKS; w++) {
    const d = plusDays(from, w * 7);
    const m = d.slice(0, 7);
    if (m !== lastMonth) {
      if (w < HEATMAP_WEEKS - 1 || lastMonth === '') months.push({ x: left + w * (cell + gap), label: formatDate(d, { month: 'short' }) });
      lastMonth = m;
    }
  }
  return (
    <div class="heatmap" style="margin-top:14px">
      <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} style="width:100%;height:auto" role="img" aria-label={`Study days over the last ${HEATMAP_WEEKS} weeks`}>
        {months.map((m) => (
          <text key={m.x} x={m.x} y="9">
            {m.label}
          </text>
        ))}
        {['M', 'W', 'F'].map((l, i) => (
          <text key={l} x="0" y={topPad + (i * 2) * (cell + gap) + cell - 3}>
            {l}
          </text>
        ))}
        {Array.from({ length: HEATMAP_WEEKS * 7 }, (_, i) => {
          const day = plusDays(from, i);
          if (day > today) return null;
          const w = Math.floor(i / 7);
          const r = i % 7;
          const ms = perDay.get(day) ?? 0;
          return (
            <rect
              key={day}
              data-day={day}
              x={left + w * (cell + gap)}
              y={topPad + r * (cell + gap)}
              width={cell}
              height={cell}
              rx="3"
              fill={`var(--heat-${level(ms)})`}
              stroke={day === selected ? 'var(--on-bg)' : day === today ? 'var(--primary)' : 'none'}
              stroke-width={day === selected || day === today ? 1.5 : 0}
              onClick={() => onSelect(day)}
            >
              <title>{`${day}: ${Durations.short(ms)}`}</title>
            </rect>
          );
        })}
      </svg>
    </div>
  );
}
