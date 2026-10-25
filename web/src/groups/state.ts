/**
 * Live group state for the Groups tab, plus the presence publisher: a projection of the timer
 * and stored totals, written only when something a viewer would see changes, never per second.
 */
import { effect, signal } from '@preact/signals';
import { StudyStats, weekStart } from '../core/model';
import { readoutAt, unrecordedFocusMs } from '../core/timer';
import { store } from '../data/runtime';
import { groupsAvailable, loadGroupsApi, type Account, type ChatMessage, type GroupsApi, type MemberPresence, type Nudge, type PresenceState, type StudyGroup } from './api';

export const groupsState = {
  /** 'off' when this build has no Firebase config. */
  status: signal<'off' | 'loading' | 'ready' | 'error'>(groupsAvailable ? 'loading' : 'off'),
  account: signal<Account | null>(null),
  groups: signal<StudyGroup[]>([]),
  presence: signal<Map<string, MemberPresence>>(new Map()),
  selectedId: signal<string | null>(null),
  messages: signal<ChatMessage[]>([]),
  nudges: signal<Nudge[]>([]),
};

let api: GroupsApi | null = null;
let started = false;

export function groupsApi(): GroupsApi | null {
  return api;
}

/** Starts listening once. Safe to call from any screen; does nothing without a Firebase config. */
export function startGroups() {
  if (started || !groupsAvailable) return;
  started = true;
  loadGroupsApi()
    .then((loaded) => {
      api = loaded;
      groupsState.status.value = 'ready';
      wire(loaded);
    })
    .catch(() => {
      groupsState.status.value = 'error';
      started = false;
    });
}

function wire(a: GroupsApi) {
  let offGroups: (() => void) | null = null;
  let offPresence: (() => void) | null = null;
  let offNudges: (() => void) | null = null;
  let offMessages: (() => void) | null = null;
  let presenceKey = '';

  a.onAccount((account) => {
    groupsState.account.value = account;
    offGroups?.();
    offNudges?.();
    offGroups = offNudges = null;
    if (!account) {
      groupsState.groups.value = [];
      groupsState.nudges.value = [];
      groupsState.selectedId.value = null;
      return;
    }
    offGroups = a.myGroups(account.uid, (list) => {
      groupsState.groups.value = list;
      const uids = [...new Set(list.flatMap((g) => g.memberIds))].sort();
      const key = uids.join(',');
      if (key !== presenceKey) {
        presenceKey = key;
        offPresence?.();
        offPresence = a.presence(uids, (members) => {
          groupsState.presence.value = new Map(members.map((m) => [m.uid, m] as const));
        });
      }
    });
    offNudges = a.pendingNudges(account.uid, (list) => {
      groupsState.nudges.value = list;
    });
  });

  effect(() => {
    const id = groupsState.selectedId.value;
    offMessages?.();
    offMessages = null;
    groupsState.messages.value = [];
    if (id) offMessages = a.messages(id, (list) => (groupsState.messages.value = list));
  });

  startPresencePublisher(a);
}

/** Builds what this user's friends see, from timer state and stored totals. */
export function buildPresence(account: Account): MemberPresence {
  const now = store.time();
  const state = store.timer.value;
  const clock = store.clock.value;
  const today = store.today.value;
  const ws = weekStart(today);
  const segments = store.segmentsBetween(clock.startOf(ws), clock.endOf(today));
  const readout = readoutAt(state, now);
  const presenceState: PresenceState =
    state.kind !== 'active' || !readout ? 'IDLE' : !readout.isRunning ? 'PAUSED' : readout.phase === 'FOCUS' ? 'STUDYING' : 'BREAK';
  // Rounded so tiny clock jitter between emissions doesn't cause a rewrite.
  const runningSinceMs = presenceState === 'STUDYING' ? Math.floor((now.wallMs - unrecordedFocusMs(state, now)) / 1_000) * 1_000 : null;
  return {
    uid: account.uid,
    displayName: account.displayName,
    state: presenceState,
    subject: state.kind === 'active' ? state.subject.name : null,
    runningSinceMs,
    todayKey: today,
    todayMs: StudyStats.total(segments, clock.startOf(today), clock.endOf(today)),
    weekKey: ws,
    weekMs: StudyStats.total(segments, clock.startOf(ws), clock.endOf(today)),
    updatedAtMs: now.wallMs,
  };
}

function startPresencePublisher(a: GroupsApi) {
  let lastKey = '';
  let pending: ReturnType<typeof setTimeout> | undefined;
  effect(() => {
    const account = groupsState.account.value;
    // Dependencies: timer state, stored segments, the study day and the account.
    void store.timer.value;
    void store.segments.value;
    void store.today.value;
    if (!account) return;
    const presence = buildPresence(account);
    const key = JSON.stringify({ ...presence, updatedAtMs: 0 });
    if (key === lastKey) return;
    clearTimeout(pending);
    pending = setTimeout(() => {
      lastKey = key;
      a.publishPresence(presence).catch(() => {
        lastKey = '';
      });
    }, 1_000);
  });
}
