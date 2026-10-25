import { useEffect, useState } from 'preact/hooks';
import { formatTime } from '../core/model';
import { store } from '../data/runtime';
import { groupsAvailable } from '../groups/api';
import { startGroups } from '../groups/state';
import { ToastHost, showToast } from './common';
import { GROUPS_USED_KEY, GroupsScreen, NudgeBanner } from './GroupsScreen';
import { Icon, type IconName } from './icons';
import { PlannerScreen } from './PlannerScreen';
import { FocusScreen, InstallScreen, SettingsScreen, SubjectsScreen } from './SettingsScreen';
import { StatsScreen } from './StatsScreen';
import { TimerScreen } from './TimerScreen';

type Tab = 'timer' | 'planner' | 'stats' | 'groups' | 'settings';
type Pushed = 'subjects' | 'focus' | 'install' | null;

const TABS: { id: Tab; label: string; icon: IconName }[] = [
  { id: 'timer', label: 'Timer', icon: 'timer' },
  { id: 'planner', label: 'Planner', icon: 'planner' },
  { id: 'stats', label: 'Stats', icon: 'stats' },
  { id: 'groups', label: 'Groups', icon: 'groups' },
  { id: 'settings', label: 'Settings', icon: 'settings' },
];

const PHASE_NAMES = { FOCUS: 'Focus', SHORT_BREAK: 'Short break', LONG_BREAK: 'Long break' } as const;

function initialTab(): Tab {
  const hash = location.hash.replace('#', '');
  return TABS.some((t) => t.id === hash) ? (hash as Tab) : 'timer';
}

/** Turns timer announcements into toasts (the chime is played by the runtime). */
function useAnnouncements() {
  const list = store.announcements.value;
  useEffect(() => {
    if (list.length === 0) return;
    for (const a of list) {
      if (a.kind === 'interrupted') {
        showToast(`Your clock jumped, so the session paused at ${formatTime(a.creditedUntilWallMs)}. Time up to then was counted.`, { duration: 0 });
      } else {
        const e = a.effect;
        const next = PHASE_NAMES[e.to];
        const text = e.to === 'FOCUS' ? (e.nextStartsAutomatically ? 'Break over. Focus started.' : "Break's over. Resume when you're ready.") : e.nextStartsAutomatically ? `Round done. ${next} started.` : 'Round done. Resume to start your break.';
        showToast(a.fresh ? text : `While you were away: ${text.charAt(0).toLowerCase()}${text.slice(1)}`);
      }
    }
    store.announcements.value = [];
  }, [list]);
}

export function App() {
  const [tab, setTab] = useState<Tab>(initialTab);
  const [pushed, setPushed] = useState<Pushed>(null);
  useAnnouncements();

  useEffect(() => {
    // Keep publishing presence for people who use groups, without loading Firebase for everyone else.
    try {
      if (groupsAvailable && localStorage.getItem(GROUPS_USED_KEY)) startGroups();
    } catch {
      /* storage blocked */
    }
  }, []);

  useEffect(() => {
    history.replaceState(null, '', tab === 'timer' ? location.pathname + location.search : `#${tab}`);
    window.scrollTo(0, 0);
  }, [tab, pushed]);

  const update = useUpdateBanner();

  const storageError = store.storageError.value;
  useEffect(() => {
    if (storageError) showToast("This browser isn't saving Lock In's data. Turn off Private Browsing or free up storage, or it'll be gone when you close Lock In.", { duration: 0 });
  }, [storageError]);

  let screen;
  if (pushed === 'subjects') screen = <SubjectsScreen onBack={() => setPushed(null)} />;
  else if (pushed === 'focus') screen = <FocusScreen onBack={() => setPushed(null)} />;
  else if (pushed === 'install') screen = <InstallScreen onBack={() => setPushed(null)} />;
  else if (tab === 'timer') screen = <TimerScreen />;
  else if (tab === 'planner') screen = <PlannerScreen onSessionStarted={() => setTab('timer')} />;
  else if (tab === 'stats') screen = <StatsScreen />;
  else if (tab === 'groups') screen = <GroupsScreen />;
  else screen = <SettingsScreen onOpen={setPushed} />;

  return (
    <div class="app">
      <NudgeBanner />
      <main>{screen}</main>
      {update}
      {pushed === null && (
        <nav class="tabbar" aria-label="Main">
          <div class="tabbar-inner">
            {TABS.map((t) => (
              <button type="button" class="tab" key={t.id} aria-current={tab === t.id ? 'page' : undefined} onClick={() => setTab(t.id)}>
                <span class="pill">
                  <Icon name={t.icon} />
                </span>
                {t.label}
              </button>
            ))}
          </div>
        </nav>
      )}
      <ToastHost />
    </div>
  );
}

/** Shows "Update ready" when a new version has been downloaded in the background. */
function useUpdateBanner() {
  const [waiting, setWaiting] = useState<ServiceWorker | null>(null);
  useEffect(() => {
    const onUpdate = (e: Event) => setWaiting((e as CustomEvent<ServiceWorker>).detail);
    window.addEventListener('lockin:update', onUpdate);
    return () => window.removeEventListener('lockin:update', onUpdate);
  }, []);
  useEffect(() => {
    if (waiting)
      showToast('A new version of Lock In is ready.', {
        duration: 0,
        action: {
          label: 'Reload',
          run: () => {
            navigator.serviceWorker.addEventListener('controllerchange', () => location.reload(), { once: true });
            waiting.postMessage('skipWaiting');
          },
        },
      });
  }, [waiting]);
  return null;
}
