import { useState } from 'preact/hooks';
import {
  Durations,
  GENERAL,
  StudyStats,
  dayOfMonth,
  ddayDaysFrom,
  ddayLabel,
  formatDate,
  formatTime,
  plusDays,
  segmentEnd,
  weekStart,
  type LocalDate,
  type StudyTask,
} from '../core/model';
import { unrecordedFocusMs } from '../core/timer';
import { store } from '../data/runtime';
import { liveTotalsForDay } from '../data/store';
import { ConfirmSheet, Section, Sheet } from './common';
import { useInterval } from './hooks';
import { Icon } from './icons';
import { startSubject } from './TimerScreen';

export function PlannerScreen({ onSessionStarted }: { onSessionStarted: () => void }) {
  const [picked, setPicked] = useState<LocalDate | null>(null);
  const [newDDay, setNewDDay] = useState(false);
  const [deleting, setDeleting] = useState<{ id: number; title: string } | null>(null);
  const [menuFor, setMenuFor] = useState<StudyTask | null>(null);
  const timer = store.timer.value;
  // Totals here don't need to tick per second.
  const now = useInterval(30_000, timer.kind === 'active');

  const today = store.today.value;
  const selected = picked ?? today;
  const clock = store.clock.value;
  const ws = weekStart(selected);
  const we = plusDays(ws, 6);
  const weekSegments = store.segmentsBetween(clock.startOf(ws), clock.endOf(we));
  const perDay = StudyStats.perDay(weekSegments, clock, ws, we);
  const liveNow = unrecordedFocusMs(timer, now);
  const dayStart = clock.startOf(selected);
  const dayEnd = clock.endOf(selected);
  const totals = liveTotalsForDay(weekSegments, dayStart, dayEnd, timer, now);
  const allTasks = store.tasks.value;
  const dayTasks = allTasks.filter((t) => t.date === selected).sort((a, b) => a.createdAtMs - b.createdAtMs);
  const byId = store.subjectsById.value;
  const done = dayTasks.filter((t) => t.done).length;

  const shares = [...totals.bySubjectId.entries()]
    .filter(([, ms]) => ms > 0)
    .map(([id, ms]) => {
      const fallback = weekSegments.filter((s) => s.subjectId === id).at(-1)?.subjectName ?? (timer.kind === 'active' ? timer.subject.name : GENERAL);
      return { name: (id !== null ? byId.get(id)?.name : undefined) ?? fallback, color: store.colorOf(id), ms };
    })
    .sort((a, b) => b.ms - a.ms);
  const log = weekSegments
    .filter((s) => StudyStats.overlap(s, dayStart, dayEnd) > 0)
    .sort((a, b) => a.startWallMs - b.startWallMs)
    .map((s) => ({
      key: `${s.sessionId}:${s.index}`,
      range: `${formatTime(s.startWallMs)}–${formatTime(segmentEnd(s))}`,
      name: (s.subjectId !== null ? byId.get(s.subjectId)?.name : undefined) ?? s.subjectName,
      color: store.colorOf(s.subjectId),
      ms: s.durationMs,
    }));

  return (
    <div class="screen">
      <h1 class="screen-title">Planner</h1>

      <div class="weeknav">
        <button type="button" class="icon-btn" aria-label="Previous week" onClick={() => setPicked(plusDays(selected, -7))}>
          <Icon name="back" />
        </button>
        <button type="button" class="btn text label" onClick={() => setPicked(null)} aria-label="Go to today">
          {formatDate(ws, { day: 'numeric', month: 'short' })} – {formatDate(we, { day: 'numeric', month: 'short' })}
        </button>
        <button type="button" class="icon-btn" aria-label="Next week" onClick={() => setPicked(plusDays(selected, 7))}>
          <Icon name="forward" />
        </button>
      </div>

      <div class="week">
        {Array.from({ length: 7 }, (_, i) => {
          const date = plusDays(ws, i);
          const studied = (perDay.get(date) ?? 0) + (date === today ? liveNow : 0);
          const tasks = allTasks.filter((t) => t.date === date);
          return (
            <button
              type="button"
              key={date}
              class={`day ${date === today ? 'today' : ''}`}
              aria-pressed={date === selected}
              aria-label={formatDate(date, { weekday: 'long', day: 'numeric', month: 'long' })}
              onClick={() => setPicked(date === today ? null : date)}
            >
              <span class="dow">{formatDate(date, { weekday: 'short' })}</span>
              <span class="num">{dayOfMonth(date)}</span>
              <span class="marks">{studied >= 60_000 && <span class="studied-dot" />}</span>
              <span class="marks">{tasks.length > 0 && `${tasks.filter((t) => t.done).length}/${tasks.length}`}</span>
            </button>
          );
        })}
      </div>

      <div style="margin-top:16px">
        <div style="font-weight:600">{formatDate(selected, { weekday: 'long', day: 'numeric', month: 'long' })}</div>
        <div class="small muted">Studied {Durations.short(totals.totalMs)}</div>
      </div>

      <Section title="D-Day" action={{ label: 'Add', icon: 'add', onClick: () => setNewDDay(true) }} />
      {store.ddays.value.length === 0 ? (
        <p class="hint" style="margin-top:0">Count down to exams, deadlines and milestones.</p>
      ) : (
        <div class="ddays">
          {store.ddays.value.map((d) => (
            <div class={`dday ${ddayDaysFrom(d, today) < 0 ? 'past' : ''}`} key={d.id}>
              <div class="big">{ddayLabel(d, today)}</div>
              <div class="ellipsis">{d.title}</div>
              <div class="small">{formatDate(d.date, { day: 'numeric', month: 'short', year: 'numeric' })}</div>
              <button type="button" class="icon-btn x" aria-label={`Delete ${d.title}`} onClick={() => setDeleting({ id: d.id, title: d.title })}>
                <Icon name="close" />
              </button>
            </div>
          ))}
        </div>
      )}

      <Section title={`To-do · ${done} of ${dayTasks.length} done`} />
      <AddTask date={selected} />
      {dayTasks.length > 0 && (
        <div class="list" style="margin-top:10px">
          {dayTasks.map((t) => {
            const subject = t.subjectId !== null ? byId.get(t.subjectId) : undefined;
            return (
              <div class={`task ${t.done ? 'done' : ''}`} key={t.id}>
                <input type="checkbox" checked={t.done} aria-label={t.title} onChange={(e) => store.taskRepo.setDone(t.id, (e.currentTarget as HTMLInputElement).checked)} />
                <span class="grow">
                  <span class="title" style="display:block">
                    {t.title}
                  </span>
                  {subject && (
                    <span class="small muted" style="display:inline-flex;align-items:center;gap:6px">
                      <span class="dot" style={{ background: subject.color, width: '8px', height: '8px' }} />
                      {subject.name}
                    </span>
                  )}
                </span>
                {!t.done && (
                  <button
                    type="button"
                    class="icon-btn"
                    aria-label={`Start studying for ${t.title}`}
                    onClick={() => {
                      startSubject(t.subjectId);
                      onSessionStarted();
                    }}
                  >
                    <Icon name="play" />
                  </button>
                )}
                <button type="button" class="icon-btn" aria-label={`More for ${t.title}`} onClick={() => setMenuFor(t)}>
                  <Icon name="more" />
                </button>
              </div>
            );
          })}
        </div>
      )}

      <Section title="Study log" />
      <div class="card">
        {shares.length === 0 ? (
          <div class="muted small">Nothing logged on this day yet.</div>
        ) : (
          <>
            <div class="sharebar" aria-hidden="true">
              {shares.map((s) => (
                <span key={s.name} style={{ background: s.color, flex: s.ms }} />
              ))}
            </div>
            {shares.map((s) => (
              <div class="legend-row" key={s.name}>
                <span class="dot" style={{ background: s.color }} />
                <span class="grow">{s.name}</span>
                <span class="tnum muted">{Durations.short(s.ms)}</span>
              </div>
            ))}
            {log.length > 0 && <hr style="border:0;border-top:0.5px solid var(--outline-variant);margin:12px 0" />}
            {log.map((l) => (
              <div class="log-row" key={l.key}>
                <span class="tnum muted" style="min-width:96px">
                  {l.range}
                </span>
                <span class="dot" style={{ background: l.color, width: '8px', height: '8px' }} />
                <span class="grow ellipsis">{l.name}</span>
                <span class="tnum">{Durations.short(l.ms)}</span>
              </div>
            ))}
          </>
        )}
      </div>

      {newDDay && <NewDDaySheet today={today} onClose={() => setNewDDay(false)} />}
      {deleting && (
        <ConfirmSheet title={`Delete ${deleting.title}?`} confirm="Delete" danger onConfirm={() => store.ddayRepo.delete(deleting.id)} onClose={() => setDeleting(null)} />
      )}
      {menuFor && (
        <Sheet title={menuFor.title} onClose={() => setMenuFor(null)}>
          <EditTask task={menuFor} onClose={() => setMenuFor(null)} />
        </Sheet>
      )}
    </div>
  );
}

function SubjectSelect({ value, onChange, id }: { value: number | null; onChange: (v: number | null) => void; id?: string }) {
  return (
    <select id={id} class="field" value={value === null ? '' : String(value)} onChange={(e) => {
      const v = (e.currentTarget as HTMLSelectElement).value;
      onChange(v === '' ? null : Number(v));
    }}>
      <option value="">No subject</option>
      {store.subjects.value.map((s) => (
        <option value={String(s.id)} key={s.id}>
          {s.name}
        </option>
      ))}
    </select>
  );
}

function AddTask({ date }: { date: LocalDate }) {
  const [title, setTitle] = useState('');
  const [subjectId, setSubjectId] = useState<number | null>(null);
  const submit = (e: Event) => {
    e.preventDefault();
    if (!title.trim()) return;
    store.taskRepo.add(title, date, subjectId);
    setTitle('');
  };
  return (
    <form class="add-task" onSubmit={submit}>
      <input
        class="field"
        placeholder="Add a task"
        aria-label="Add a task"
        value={title}
        maxLength={200}
        enterKeyHint="done"
        onInput={(e) => setTitle((e.currentTarget as HTMLInputElement).value)}
      />
      {store.subjects.value.length > 0 && <SubjectSelect value={subjectId} onChange={setSubjectId} />}
      <button type="submit" class="play-btn" style="align-self:center" aria-label="Add task" disabled={!title.trim()}>
        <Icon name="add" />
      </button>
    </form>
  );
}

function EditTask({ task, onClose }: { task: StudyTask; onClose: () => void }) {
  const [title, setTitle] = useState(task.title);
  const [subjectId, setSubjectId] = useState<number | null>(task.subjectId);
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (title.trim()) store.taskRepo.edit(task.id, title, subjectId);
        onClose();
      }}
    >
      <label class="stack" for="task-title">
        Task
      </label>
      <input id="task-title" class="field" value={title} maxLength={200} onInput={(e) => setTitle((e.currentTarget as HTMLInputElement).value)} />
      <label class="stack" for="task-subject">
        Subject
      </label>
      <SubjectSelect id="task-subject" value={subjectId} onChange={setSubjectId} />
      <div class="actions" style="flex-wrap:wrap">
        <button
          type="button"
          class="btn danger"
          onClick={() => {
            store.taskRepo.delete(task.id);
            onClose();
          }}
        >
          Delete
        </button>
        <button
          type="button"
          class="btn text"
          onClick={() => {
            store.taskRepo.moveTo(task.id, plusDays(task.date, 1));
            onClose();
          }}
        >
          Move to next day
        </button>
        <span style="flex:1" />
        <button type="submit" class="btn primary">
          Save
        </button>
      </div>
    </form>
  );
}

function NewDDaySheet({ today, onClose }: { today: LocalDate; onClose: () => void }) {
  const [title, setTitle] = useState('');
  const [date, setDate] = useState(plusDays(today, 30));
  return (
    <Sheet title="New D-Day" onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!title.trim() || !date) return;
          store.ddayRepo.add(title, date);
          onClose();
        }}
      >
        <label class="stack" for="dday-title">
          What's coming up?
        </label>
        <input id="dday-title" class="field" placeholder="Final exams" maxLength={60} value={title} onInput={(e) => setTitle((e.currentTarget as HTMLInputElement).value)} />
        <label class="stack" for="dday-date">
          Date
        </label>
        <input id="dday-date" class="field" type="date" value={date} onInput={(e) => setDate((e.currentTarget as HTMLInputElement).value)} />
        <div class="actions">
          <button type="button" class="btn text" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" class="btn primary" disabled={!title.trim() || !date}>
            Add
          </button>
        </div>
      </form>
    </Sheet>
  );
}
