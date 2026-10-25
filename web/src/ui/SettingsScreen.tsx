import { useRef, useState } from 'preact/hooks';
import { nextSubjectColor, type Subject } from '../core/model';
import { isIOS, isStandalone, store, wakeLockSupported } from '../data/runtime';
import { ConfirmSheet, Section, StepperRow, SubjectSheet, ToggleRow, TopBar, showToast } from './common';
import { Icon } from './icons';

const APK_URL = 'https://github.com/TymonBitesTheDust/Lock-In/releases/latest/download/Lock-In.apk';
const REPO_URL = 'https://github.com/TymonBitesTheDust/Lock-In';

export function SettingsScreen({ onOpen }: { onOpen: (screen: 'subjects' | 'focus' | 'install') => void }) {
  const p = store.prefs.value;
  const update = store.updatePrefs;
  const fileInput = useRef<HTMLInputElement>(null);
  const [confirmImport, setConfirmImport] = useState<unknown>(null);
  const hourLabel = (h: number) => `${String(h).padStart(2, '0')}:00`;

  return (
    <div class="screen">
      <h1 class="screen-title">Settings</h1>

      {!isStandalone() && (
        <button type="button" class="banner" style="width:100%;border:0;text-align:left" onClick={() => onOpen('install')}>
          <Icon name="share" />
          <span class="grow">
            <b>Add Lock In to your Home Screen</b>
            It opens like an app, works offline, and keeps your data safe from Safari's clean-ups.
          </span>
        </button>
      )}

      <div class="list">
        <button type="button" class="row" onClick={() => onOpen('subjects')}>
          <span class="grow">
            <span class="title" style="display:block">
              Subjects
            </span>
            <span class="sub">Add, rename, recolour or remove subjects</span>
          </span>
          <Icon name="chevron" class="muted" />
        </button>
        <button type="button" class="row" onClick={() => onOpen('focus')}>
          <span class="grow">
            <span class="title" style="display:block">
              Focus protection
            </span>
            <span class="sub">{isIOS() ? 'Block distracting apps with iPhone Focus and Screen Time' : 'Keep the screen on and silence distractions'}</span>
          </span>
          <Icon name="chevron" class="muted" />
        </button>
      </div>

      <Section title="Pomodoro" />
      <div class="list">
        <StepperRow title="Focus" value={p.pomodoroFocusMin} min={1} max={180} format={(v) => `${v} min`} onChange={(v) => update(() => ({ pomodoroFocusMin: v }))} />
        <StepperRow title="Short break" value={p.shortBreakMin} min={1} max={60} format={(v) => `${v} min`} onChange={(v) => update(() => ({ shortBreakMin: v }))} />
        <StepperRow title="Long break" value={p.longBreakMin} min={1} max={90} format={(v) => `${v} min`} onChange={(v) => update(() => ({ longBreakMin: v }))} />
        <StepperRow title="Long break every" value={p.longBreakEvery} min={1} max={12} format={(v) => `${v} rounds`} onChange={(v) => update(() => ({ longBreakEvery: v }))} />
        <ToggleRow title="Start breaks automatically" checked={p.autoStartBreaks} onChange={(v) => update(() => ({ autoStartBreaks: v }))} />
        <ToggleRow title="Start focus automatically after a break" checked={p.autoStartFocus} onChange={(v) => update(() => ({ autoStartFocus: v }))} />
        <ToggleRow title="Chime when a round or break ends" sub="Plays while Lock In is open" checked={p.chime} onChange={(v) => update(() => ({ chime: v }))} />
      </div>
      <p class="hint">Changes apply to the next Pomodoro you start.</p>

      <Section title="Study day" />
      <div class="list">
        <StepperRow title="New day starts at" value={p.dayStartHour} min={0} max={23} format={hourLabel} onChange={(v) => update(() => ({ dayStartHour: v }))} />
      </div>
      <p class="hint">Late-night study before this hour counts for the previous day, in totals, stats and the planner.</p>

      <Section title="Your data" />
      <div class="list">
        <button
          type="button"
          class="row"
          onClick={async () => {
            const data = JSON.stringify(store.exportBackup());
            const name = `lock-in-backup-${store.today.value}.json`;
            const file = new File([data], name, { type: 'application/json' });
            const nav = navigator as Navigator & { canShare?: (d: { files: File[] }) => boolean };
            try {
              if (isIOS() && nav.canShare?.({ files: [file] })) {
                await navigator.share({ files: [file], title: 'Lock In backup' });
                return;
              }
            } catch (e) {
              if ((e as DOMException)?.name === 'AbortError') return;
            }
            const url = URL.createObjectURL(file);
            const a = document.createElement('a');
            a.href = url;
            a.download = name;
            document.body.append(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(url), 10_000);
          }}
        >
          <span class="grow">
            <span class="title" style="display:block">
              Export a backup
            </span>
            <span class="sub">Subjects, study history, to-dos and D-Days as one file</span>
          </span>
        </button>
        <button type="button" class="row" onClick={() => fileInput.current?.click()}>
          <span class="grow">
            <span class="title" style="display:block">
              Restore from a backup
            </span>
            <span class="sub">Replaces what's on this device</span>
          </span>
        </button>
      </div>
      <input
        ref={fileInput}
        type="file"
        accept="application/json,.json"
        class="sr-only"
        onChange={async (e) => {
          const input = e.currentTarget as HTMLInputElement;
          const file = input.files?.[0];
          input.value = '';
          if (!file) return;
          try {
            setConfirmImport(JSON.parse(await file.text()));
          } catch {
            showToast("That file isn't a Lock In backup.");
          }
        }}
      />
      <p class="hint">Everything stays on this device unless you join a study group. Lock In on the web and the Android app keep separate data.</p>
      {store.storageError.value && <p class="hint" style="color:var(--error)">This browser refused to save some changes. Free up storage, or turn off Private Browsing.</p>}

      <Section title="About" />
      <div class="list">
        <a class="row" href={REPO_URL} target="_blank" rel="noopener" style="color:inherit;text-decoration:none">
          <span class="grow title">Source code and updates</span>
          <Icon name="chevron" class="muted" />
        </a>
        {!isIOS() && (
          <a class="row" href={APK_URL} style="color:inherit;text-decoration:none">
            <span class="grow">
              <span class="title" style="display:block">
                Android app
              </span>
              <span class="sub">Adds app blocking during focus</span>
            </span>
            <Icon name="chevron" class="muted" />
          </a>
        )}
      </div>
      <p class="hint">Lock In for the web · {__APP_VERSION__}</p>

      {confirmImport !== null && (
        <ConfirmSheet
          title="Restore this backup?"
          body="Your subjects, study history, to-dos, D-Days and settings on this device are replaced with the ones in the file. A running session is stopped first."
          confirm="Restore"
          danger
          onConfirm={() => {
            try {
              store.importBackup(confirmImport);
              showToast('Backup restored.');
            } catch (err) {
              showToast((err as Error).message);
            }
          }}
          onClose={() => setConfirmImport(null)}
        />
      )}
    </div>
  );
}

export function SubjectsScreen({ onBack }: { onBack: () => void }) {
  const [editing, setEditing] = useState<Subject | 'new' | null>(null);
  const [removing, setRemoving] = useState<Subject | null>(null);
  const subjects = store.subjects.value;
  return (
    <div class="screen pushed">
      <TopBar title="Subjects" onBack={onBack} />
      {subjects.length === 0 ? (
        <p class="hint">No subjects yet. Add the courses or topics you study, like Maths or IELTS Reading.</p>
      ) : (
        <div class="list">
          {subjects.map((s, i) => (
            <div class="row" key={s.id}>
              <span class="dot" style={{ background: s.color }} />
              <button type="button" class="grow title ellipsis" style="border:0;background:none;text-align:left;padding:8px 0" onClick={() => setEditing(s)} aria-label={`Edit ${s.name}`}>
                {s.name}
              </button>
              <button type="button" class="icon-btn" aria-label={`Move ${s.name} up`} disabled={i === 0} style={i === 0 ? 'opacity:0.3' : ''} onClick={() => store.subjectRepo.move(s.id, -1)}>
                <Icon name="up" />
              </button>
              <button
                type="button"
                class="icon-btn"
                aria-label={`Move ${s.name} down`}
                disabled={i === subjects.length - 1}
                style={i === subjects.length - 1 ? 'opacity:0.3' : ''}
                onClick={() => store.subjectRepo.move(s.id, 1)}
              >
                <Icon name="down" />
              </button>
            </div>
          ))}
        </div>
      )}
      <button type="button" class="btn block" style="margin-top:16px" onClick={() => setEditing('new')}>
        Add subject
      </button>
      <p class="hint">Removing a subject hides it from the timer and planner. Its study history stays in your stats.</p>

      {editing && (
        <SubjectSheet
          title={editing === 'new' ? 'New subject' : 'Edit subject'}
          initialName={editing === 'new' ? '' : editing.name}
          initialColor={editing === 'new' ? nextSubjectColor(subjects.map((s) => s.color)) : editing.color}
          onSave={(name, color) => (editing === 'new' ? store.subjectRepo.add(name, color) : store.subjectRepo.update(editing.id, name, color))}
          onClose={() => setEditing(null)}
          extra={
            editing !== 'new' && (
              <button
                type="button"
                class="btn danger"
                onClick={() => {
                  setRemoving(editing);
                  setEditing(null);
                }}
              >
                Remove
              </button>
            )
          }
        />
      )}
      {removing && (
        <ConfirmSheet
          title={`Remove ${removing.name}?`}
          body="Its study history stays in your stats."
          confirm="Remove"
          danger
          onConfirm={() => store.subjectRepo.archive(removing.id)}
          onClose={() => setRemoving(null)}
        />
      )}
    </div>
  );
}

export function FocusScreen({ onBack }: { onBack: () => void }) {
  const p = store.prefs.value;
  const ios = isIOS();
  return (
    <div class="screen pushed">
      <TopBar title="Focus protection" onBack={onBack} />
      <div class="prose">
        {ios ? (
          <p>
            iPhone doesn't let any app see or cover other apps, so Lock In can't block them itself. iOS can, with a <b>Focus</b> and <b>Screen Time</b>. Set them up once and Lock In can switch them on for you.
          </p>
        ) : (
          <p>The Android app blocks other apps during focus. In a browser, Lock In can keep your screen on and chime between rounds; your phone's own focus tools do the rest.</p>
        )}
      </div>

      <Section title="While you study" />
      <div class="list">
        {wakeLockSupported() && (
          <ToggleRow title="Keep the screen on" sub="Leave your phone face up on the desk and watch the clock" checked={p.keepScreenOn} onChange={(v) => store.updatePrefs(() => ({ keepScreenOn: v }))} />
        )}
        <ToggleRow title="Chime when a round or break ends" checked={p.chime} onChange={(v) => store.updatePrefs(() => ({ chime: v }))} />
      </div>

      {ios && (
        <>
          <div class="prose">
            <h3>1. Make a Study Focus</h3>
            <ol>
              <li>
                Open <span class="path">Settings → Focus</span>, tap <b>+</b>, then <b>Custom</b>, and name it <b>Study</b>.
              </li>
              <li>
                Under <b>Allow notifications</b>, keep only the people and apps you need.
              </li>
              <li>
                Under <b>Customise screens</b>, pick a Home Screen page without games or social apps.
              </li>
            </ol>

            <h3>2. Lock distracting apps (optional)</h3>
            <ol>
              <li>
                Open <span class="path">Settings → Screen Time → App Limits → Add Limit</span>.
              </li>
              <li>Choose the apps that pull you away, set 1 minute, and turn on <b>Block at End of Limit</b>.</li>
              <li>Ask a friend to set the Screen Time passcode so you can't just tap "Ignore".</li>
            </ol>

            <h3>3. Let Lock In switch it on</h3>
            <ol>
              <li>
                In the <b>Shortcuts</b> app, make a shortcut named <b>Lock In Start</b> with the action <b>Set Focus → Study → On</b>.
              </li>
              <li>
                Make another named <b>Lock In Stop</b> with <b>Set Focus → Study → Off</b>.
              </li>
              <li>Type the names below. Lock In runs them when you start and stop a session; tap ◀ Lock In in the top-left corner to come back.</li>
            </ol>
          </div>
          <div class="list" style="margin-top:12px">
            <ShortcutField label="Shortcut when a session starts" value={p.shortcutOnStart} placeholder="Lock In Start" onChange={(v) => store.updatePrefs(() => ({ shortcutOnStart: v }))} />
            <ShortcutField label="Shortcut when a session stops" value={p.shortcutOnStop} placeholder="Lock In Stop" onChange={(v) => store.updatePrefs(() => ({ shortcutOnStop: v }))} />
          </div>
          <p class="hint">Leave a name empty to skip that step. Shortcuts run only on iPhone and iPad.</p>
        </>
      )}
    </div>
  );
}

function ShortcutField({ label, value, placeholder, onChange }: { label: string; value: string; placeholder: string; onChange: (v: string) => void }) {
  return (
    <div class="row" style="flex-direction:column;align-items:stretch;gap:6px;padding:12px 16px">
      <label class="small muted" for={label}>
        {label}
      </label>
      <input id={label} class="field" value={value} placeholder={placeholder} maxLength={80} autoCapitalize="words" onChange={(e) => onChange((e.currentTarget as HTMLInputElement).value)} />
    </div>
  );
}

export function InstallScreen({ onBack }: { onBack: () => void }) {
  const ios = isIOS();
  return (
    <div class="screen pushed">
      <TopBar title="Add to Home Screen" onBack={onBack} />
      <div class="prose">
        {ios ? (
          <>
            <ol>
              <li>
                Open this page in <b>Safari</b>.
              </li>
              <li>
                Tap the <b>Share</b> button <Icon name="share" class="small" /> (at the bottom, or next to the address bar).
              </li>
              <li>
                Scroll down and tap <b>Add to Home Screen</b>, then <b>Add</b>.
              </li>
              <li>Open Lock In from your Home Screen from now on.</li>
            </ol>
            <h3>Why bother?</h3>
            <ul>
              <li>It opens full-screen like an app and works without internet.</li>
              <li>Safari can clear data of websites you haven't opened for a week. Home Screen apps are kept.</li>
              <li>Your Home Screen app and Safari keep separate data, so pick one and stick to it.</li>
            </ul>
          </>
        ) : (
          <ol>
            <li>
              In Chrome or Edge, open the browser menu and choose <b>Install app</b> or <b>Add to Home screen</b>.
            </li>
            <li>In Safari on a Mac, choose File → Add to Dock.</li>
          </ol>
        )}
      </div>
    </div>
  );
}
