import { useEffect, useRef, useState } from 'preact/hooks';
import { Durations, weekStart } from '../core/model';
import { readoutAt } from '../core/timer';
import { store } from '../data/runtime';
import { GroupError, MAX_MEMBERS, PRESENCE_ORDER, todayTotal, weekTotal, type PresenceState } from '../groups/api';
import { groupsApi, groupsState, startGroups } from '../groups/state';
import { ConfirmSheet, Section, Sheet, TopBar, showToast } from './common';
import { useInterval } from './hooks';
import { Icon } from './icons';

export const GROUPS_USED_KEY = 'lockin.groups.used';
const NUDGE_COOLDOWN_MS = 5 * 60_000;
const STATE_LABEL: Record<PresenceState, string> = { STUDYING: 'Studying', BREAK: 'On a break', PAUSED: 'Paused', IDLE: 'Not studying' };
const AVATAR_COLORS = ['#3987E5', '#D95926', '#199E70', '#C98500', '#D55181', '#008300', '#9085E9', '#E66767'];

function avatarColor(uid: string): string {
  let h = 0;
  for (const c of uid) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return AVATAR_COLORS[h % AVATAR_COLORS.length];
}

function useAction() {
  const [busy, setBusy] = useState(false);
  const run = async (block: () => Promise<unknown>) => {
    if (busy) return;
    setBusy(true);
    try {
      await block();
    } catch (e) {
      showToast(e instanceof GroupError ? e.message : 'Something went wrong. Check your connection and try again.');
    } finally {
      setBusy(false);
    }
  };
  return [busy, run] as const;
}

export function GroupsScreen() {
  useEffect(() => {
    try {
      localStorage.setItem(GROUPS_USED_KEY, '1');
    } catch {
      /* private mode */
    }
    startGroups();
  }, []);

  const status = groupsState.status.value;
  if (status === 'off') {
    return (
      <div class="screen">
        <h1 class="screen-title">Groups</h1>
        <div class="card">
          <p style="margin-top:0">Study groups aren't connected on this website yet.</p>
          <p class="muted small" style="margin-bottom:0">
            Groups need the app's Firebase project. Everything else (timer, planner, stats) works without it.
          </p>
        </div>
      </div>
    );
  }
  if (status === 'loading' || status === 'error') {
    return (
      <div class="screen">
        <h1 class="screen-title">Groups</h1>
        <p class="muted">{status === 'loading' ? 'Connecting…' : "Couldn't reach study groups. Check your connection."}</p>
        {status === 'error' && (
          <button type="button" class="btn" onClick={startGroups}>
            Try again
          </button>
        )}
      </div>
    );
  }
  const account = groupsState.account.value;
  if (!account) return <SignIn />;
  const selected = groupsState.groups.value.find((g) => g.id === groupsState.selectedId.value);
  return selected ? <GroupDetail groupId={selected.id} /> : <GroupList />;
}

function SignIn() {
  const [mode, setMode] = useState<'in' | 'up'>('in');
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, run] = useAction();
  const api = groupsApi();
  return (
    <div class="screen">
      <h1 class="screen-title">Groups</h1>
      <p class="muted" style="margin-top:0">Study with friends: see who's studying right now, compare hours, and nudge each other back to work. Works with friends on Android too.</p>
      <div class="segmented" role="group" aria-label="Account">
        <button type="button" aria-pressed={mode === 'in'} onClick={() => setMode('in')}>
          Sign in
        </button>
        <button type="button" aria-pressed={mode === 'up'} onClick={() => setMode('up')}>
          Create account
        </button>
      </div>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (!api) return;
          void run(() => (mode === 'in' ? api.signIn(email, password) : api.signUp(name, email, password)));
        }}
      >
        {mode === 'up' && (
          <>
            <label class="stack" for="g-name">
              Name your groups will see
            </label>
            <input id="g-name" class="field" autoComplete="nickname" maxLength={40} value={name} onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)} />
          </>
        )}
        <label class="stack" for="g-email">
          Email
        </label>
        <input id="g-email" class="field" type="email" autoComplete="email" autoCapitalize="off" value={email} onInput={(e) => setEmail((e.currentTarget as HTMLInputElement).value)} />
        <label class="stack" for="g-password">
          {mode === 'up' ? 'Password (6+ characters)' : 'Password'}
        </label>
        <input
          id="g-password"
          class="field"
          type="password"
          autoComplete={mode === 'up' ? 'new-password' : 'current-password'}
          value={password}
          onInput={(e) => setPassword((e.currentTarget as HTMLInputElement).value)}
        />
        <button type="submit" class="btn primary block" style="margin-top:20px" disabled={busy}>
          {mode === 'in' ? 'Sign in' : 'Create account'}
        </button>
      </form>
      {mode === 'in' && (
        <button
          type="button"
          class="btn text block"
          style="margin-top:8px"
          disabled={busy}
          onClick={() =>
            api &&
            void run(async () => {
              await api.resetPassword(email);
              showToast('Password reset email sent.');
            })
          }
        >
          Forgot password?
        </button>
      )}
    </div>
  );
}

function GroupList() {
  const [creating, setCreating] = useState(false);
  const [joining, setJoining] = useState(false);
  const [busy, run] = useAction();
  const api = groupsApi();
  const account = groupsState.account.value!;
  const presence = groupsState.presence.value;
  const groups = groupsState.groups.value;
  return (
    <div class="screen">
      <h1 class="screen-title">Groups</h1>
      {groups.length === 0 ? (
        <p class="muted">You're not in a group yet. Create one and share its code, or join a friend's.</p>
      ) : (
        <div class="list">
          {groups.map((g) => {
            const studying = g.memberIds.filter((id) => presence.get(id)?.state === 'STUDYING').length;
            return (
              <button type="button" class="row" key={g.id} onClick={() => (groupsState.selectedId.value = g.id)}>
                <span class="grow">
                  <span class="title" style="display:block">
                    {g.name}
                  </span>
                  <span class="sub">
                    {g.memberIds.length} {g.memberIds.length === 1 ? 'member' : 'members'}
                    {studying > 0 && ` · ${studying} studying`}
                  </span>
                </span>
                <Icon name="chevron" class="muted" />
              </button>
            );
          })}
        </div>
      )}
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px;margin-top:16px">
        <button type="button" class="btn primary" onClick={() => setCreating(true)}>
          Create group
        </button>
        <button type="button" class="btn" onClick={() => setJoining(true)}>
          Join with code
        </button>
      </div>
      <Section title="Account" />
      <div class="list">
        <div class="row">
          <span class="grow">
            <span class="title" style="display:block">
              {account.displayName}
            </span>
            <span class="sub">{account.email}</span>
          </span>
          <button type="button" class="btn text" onClick={() => api?.signOut()}>
            Sign out
          </button>
        </div>
      </div>

      {creating && (
        <TextSheet
          title="New study group"
          label="Group name"
          maxLength={40}
          confirm="Create"
          busy={busy}
          onClose={() => setCreating(false)}
          onSubmit={(name) =>
            run(async () => {
              if (!api) return;
              const g = await api.create(name, account);
              setCreating(false);
              groupsState.selectedId.value = g.id;
              showToast(`Group created. Share code ${g.code} with friends.`, { duration: 6000 });
            })
          }
        />
      )}
      {joining && (
        <TextSheet
          title="Join a group"
          label="Invite code"
          maxLength={6}
          code
          confirm="Join"
          busy={busy}
          onClose={() => setJoining(false)}
          onSubmit={(code) =>
            run(async () => {
              if (!api) return;
              groupsState.selectedId.value = await api.join(code, account);
              setJoining(false);
            })
          }
        />
      )}
    </div>
  );
}

function TextSheet(props: { title: string; label: string; maxLength: number; confirm: string; busy: boolean; code?: boolean; onClose: () => void; onSubmit: (v: string) => void }) {
  const [value, setValue] = useState('');
  return (
    <Sheet title={props.title} onClose={props.onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (value.trim()) props.onSubmit(value);
        }}
      >
        <label class="stack" for="text-sheet">
          {props.label}
        </label>
        <input
          id="text-sheet"
          class={`field ${props.code ? 'code' : ''}`}
          maxLength={props.maxLength}
          value={value}
          autoCapitalize={props.code ? 'characters' : 'sentences'}
          autoComplete="off"
          onInput={(e) => setValue((e.currentTarget as HTMLInputElement).value)}
        />
        <div class="actions">
          <button type="button" class="btn text" onClick={props.onClose}>
            Cancel
          </button>
          <button type="submit" class="btn primary" disabled={props.busy || !value.trim()}>
            {props.confirm}
          </button>
        </div>
      </form>
    </Sheet>
  );
}

function GroupDetail({ groupId }: { groupId: string }) {
  const nowTp = useInterval(1_000, true);
  const nowMs = nowTp.wallMs;
  const [lastNudged, setLastNudged] = useState<Record<string, number>>({});
  const [leaving, setLeaving] = useState(false);
  const [rankBy, setRankBy] = useState<'today' | 'week'>('today');
  const [draft, setDraft] = useState('');
  const [busy, run] = useAction();
  const chatEnd = useRef<HTMLDivElement>(null);
  const api = groupsApi();
  const account = groupsState.account.value!;
  const group = groupsState.groups.value.find((g) => g.id === groupId)!;
  const presence = groupsState.presence.value;
  const messages = groupsState.messages.value;
  const dayKey = store.today.value;
  const weekKey = weekStart(dayKey);
  const readout = readoutAt(store.timer.value, nowTp);
  const chatLocked = readout !== null && readout.isRunning && readout.phase === 'FOCUS';

  useEffect(() => {
    chatEnd.current?.scrollIntoView({ block: 'nearest' });
  }, [messages.length]);

  const members = group.memberIds
    .map((uid) => {
      const p = presence.get(uid);
      const state: PresenceState = p?.state ?? 'IDLE';
      const isMe = uid === account.uid;
      return {
        uid,
        name: p?.displayName ?? (isMe ? account.displayName : 'Member'),
        state,
        subject: p?.subject ?? null,
        todayMs: p ? todayTotal(p, dayKey, nowMs) : 0,
        weekMs: p ? weekTotal(p, weekKey, nowMs) : 0,
        isMe,
        canNudge: !isMe && state !== 'STUDYING' && nowMs - (lastNudged[uid] ?? 0) > NUDGE_COOLDOWN_MS,
      };
    })
    .sort((a, b) => PRESENCE_ORDER.indexOf(a.state) - PRESENCE_ORDER.indexOf(b.state) || b.todayMs - a.todayMs);
  const ranking = [...members].sort((a, b) => (rankBy === 'today' ? b.todayMs - a.todayMs : b.weekMs - a.weekMs));

  const share = async () => {
    const text = `Study with me on Lock In! Join "${group.name}" with code ${group.code}. ${location.origin}${location.pathname}`;
    try {
      if (navigator.share) await navigator.share({ text });
      else {
        await navigator.clipboard.writeText(text);
        showToast('Invite copied.');
      }
    } catch {
      /* cancelled */
    }
  };

  return (
    <div class="screen">
      <TopBar title={group.name} onBack={() => (groupsState.selectedId.value = null)} />
      <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
        <span class="chip">
          Code <span class="code">{group.code}</span>
        </span>
        <button type="button" class="btn text" style="display:inline-flex;align-items:center;gap:6px" onClick={share}>
          <Icon name="share" />
          Share invite code
        </button>
      </div>

      <Section title={`Members · ${group.memberIds.length}/${MAX_MEMBERS}`} />
      <div class="list">
        {members.map((m) => (
          <div class="member" key={m.uid}>
            <span class="avatar" style={{ background: avatarColor(m.uid) }} aria-hidden="true">
              {m.name.slice(0, 1).toUpperCase()}
              <span class={`state state-${m.state}`} />
            </span>
            <span class="grow" style="min-width:0">
              <span class="title ellipsis" style="display:block">
                {m.name}
                {m.isMe && <span class="muted"> (you)</span>}
              </span>
              <span class="sub ellipsis" style="display:block">
                {STATE_LABEL[m.state]}
                {m.subject && m.state !== 'IDLE' ? ` · ${m.subject}` : ''}
              </span>
            </span>
            <span class="tnum">{Durations.clock(m.todayMs)}</span>
            {m.canNudge && (
              <button
                type="button"
                class="icon-btn"
                aria-label={`Nudge ${m.name}`}
                disabled={busy}
                onClick={() =>
                  run(async () => {
                    if (!api) return;
                    await api.nudge(m.uid, account, group.name);
                    setLastNudged((x) => ({ ...x, [m.uid]: Date.now() }));
                    showToast('Nudge sent.');
                  })
                }
              >
                <Icon name="bell" />
              </button>
            )}
          </div>
        ))}
      </div>
      <p class="hint">Nudge a friend who isn't studying to remind them to lock in.</p>

      <Section title="Ranking" />
      <div class="segmented" role="group" aria-label="Ranking period">
        <button type="button" aria-pressed={rankBy === 'today'} onClick={() => setRankBy('today')}>
          Today
        </button>
        <button type="button" aria-pressed={rankBy === 'week'} onClick={() => setRankBy('week')}>
          This week
        </button>
      </div>
      <div class="card" style="margin-top:10px;padding:8px 16px">
        {ranking.map((m, i) => (
          <div class={`rank ${m.isMe ? 'me' : ''}`} key={m.uid}>
            <span class="muted tnum">{i + 1}</span>
            <span class="ellipsis">{m.name}</span>
            <span class="tnum">{Durations.short(rankBy === 'today' ? m.todayMs : m.weekMs)}</span>
          </div>
        ))}
      </div>

      <Section title="Messages" />
      {chatLocked ? (
        <div class="card">
          <b>Chat opens on your break</b>
          <p class="muted small" style="margin-bottom:0">
            Messages stay hidden while you focus. {messages.length} in this group.
          </p>
        </div>
      ) : (
        <>
          <div class="chat" aria-live="polite">
            {messages.length === 0 && <p class="muted small">No messages yet. Say hi on your break.</p>}
            {messages.map((m) => (
              <div class={`bubble ${m.uid === account.uid ? 'mine' : ''}`} key={m.id}>
                <div class="meta">
                  {m.uid === account.uid ? 'You' : m.name}
                  {m.sentAtMs > 0 && ` · ${new Date(m.sentAtMs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`}
                </div>
                {m.text}
              </div>
            ))}
            <div ref={chatEnd} />
          </div>
          <form
            class="composer"
            onSubmit={(e) => {
              e.preventDefault();
              const text = draft;
              if (!text.trim() || !api) return;
              setDraft('');
              void run(() => api.send(group.id, account, text));
            }}
          >
            <input class="field" placeholder="Message" aria-label="Message" maxLength={500} value={draft} enterKeyHint="send" onInput={(e) => setDraft((e.currentTarget as HTMLInputElement).value)} />
            <button type="submit" class="btn primary" disabled={!draft.trim()}>
              Send
            </button>
          </form>
        </>
      )}

      <button type="button" class="btn danger block" style="margin-top:28px" onClick={() => setLeaving(true)}>
        Leave group
      </button>
      {leaving && (
        <ConfirmSheet
          title={`Leave ${group.name}?`}
          body="You can rejoin later with the code."
          confirm="Leave"
          danger
          onConfirm={() =>
            run(async () => {
              if (!api) return;
              await api.leave(group.id, account.uid);
              groupsState.selectedId.value = null;
            })
          }
          onClose={() => setLeaving(false)}
        />
      )}
    </div>
  );
}

/** A banner on any tab when a friend nudges you. */
export function NudgeBanner() {
  const nudges = groupsState.nudges.value;
  const account = groupsState.account.value;
  if (!account || nudges.length === 0) return null;
  const n = nudges[0];
  return (
    <div class="banner" role="alert" style="position:fixed;left:12px;right:12px;top:calc(env(safe-area-inset-top) + 8px);z-index:70;max-width:536px;margin:0 auto;box-shadow:0 4px 16px rgb(0 0 0 / 25%)">
      <Icon name="bell" />
      <span class="grow">
        <b>Time to lock in.</b>
        {n.fromName} nudged you from {n.groupName}.
      </span>
      <button
        type="button"
        class="btn text"
        onClick={() => {
          for (const x of nudges) void groupsApi()?.markNudgeSeen(account.uid, x.id);
        }}
      >
        OK
      </button>
    </div>
  );
}
