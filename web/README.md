# Lock In for iPhone (web app)

Lock In for iPhone, iPad and any computer: an installable web app (PWA) with the same timer rules,
planner, stats and study groups as the Android app. People open it in Safari and add it to their
Home Screen, so it needs no App Store, no Apple Developer account and no Mac.

It's published by GitHub Actions to **https://tymonbitesthedust.github.io/Lock-In/** (see
[Publishing](#publishing)).

## What's the same as Android, and what isn't

| | Android app | Web app on iPhone |
|---|---|---|
| Stopwatch and Pomodoro | ✓ | ✓ Same engine, ported line by line, same tests |
| Time counts with the screen off or the app closed | ✓ | ✓ The session stores its start time, nothing ticks |
| Planner, to-dos, D-Days, stats, heatmap, streaks | ✓ | ✓ |
| Study groups, live clocks, rankings, chat, nudges | ✓ | ✓ Same Firebase project and rules: iPhone and Android friends share groups |
| Ongoing notification with a live clock | ✓ | — Open Lock In to see the clock; it can keep the screen on |
| Alert when a round or break ends | ✓ Always | While Lock In is open (chime + message). Closed, the next open catches up exactly |
| Block other apps during focus | ✓ A lock screen covers everything else, the home screen included | — iOS doesn't allow it for any app outside Screen Time. Lock In walks people through a Study Focus and Screen Time limits, and can run a Shortcut to switch the Focus on and off |
| Allowed apps as shortcuts during focus | ✓ On the lock screen, with real app icons | ✓ Under the clock, as links (`spotify://`, a website, or a Shortcut name). The Study Focus can show a Home Screen page with only those apps |
| Colour theme (Light / Dark / System, six accents) | ✓ | ✓ Same colours (`ACCENTS` in `src/core/model.ts` mirrors `core/model` `Appearance.kt`) |
| Nudges when the app is closed | Within ~15 min | When Lock In is next opened |
| Data | On the phone | On the phone (browser storage), with export/restore to a file |

The web app and the Android app keep separate local data; only group presence is shared.

## How it maps to the Android code

```
web/src/core/timer.ts     ← core/timer (TimerEngine, readout, snapshot codec)       pure, tested
web/src/core/model.ts     ← core/model (StudyDayClock, StudyStats, D-Days, palette)  pure, tested
web/src/data/store.ts     ← core/data + DefaultTimerController + Room/DataStore      localStorage
web/src/data/runtime.ts   ← system/timer (alarms, receivers, foreground service)     browser events
web/src/groups/           ← core/network + system/presence                           Firebase JS SDK
web/src/ui/               ← feature/* screens                                        Preact
web/test/                 ← TimerEngineTest, StudyStatsTest + storage and presence tests
web/e2e/                  end-to-end checks on an iPhone 15-sized screen (Playwright)
```

The rules from `docs/ARCHITECTURE.md` hold here too: time is derived, never counted; the
next-boundary timeout is a projection of state; segments are written before the snapshot, so a
page reload replays safely; "today" is a time window, not a midnight job.

One difference: browsers have no clock that keeps running across a closed tab, so the web build
measures with the wall clock (`Date.now()`). iPhones set their clock automatically. If the clock
moves backwards mid-session, the session pauses at its last checkpoint instead of losing or
inventing time (tested in `test/timer.test.ts`).

## Develop

Requirements: Node 22 or later.

```bash
cd web
npm install
npm run dev          # http://localhost:5173, open it on your phone via your computer's IP
npm test             # unit tests (engine, stats, storage, presence)
npm run build        # dist/
npm run e2e          # end-to-end tests against dist/ (WebKit; BROWSER=chromium to use Chromium)
```

`npm run e2e` needs Playwright's browsers once: `npx playwright install webkit chromium`.

## Study groups (Firebase)

The web app uses the same Firebase project as Android. Without a config the Groups tab says groups
aren't set up; everything else works.

1. Firebase console → **Project settings** → **Your apps** → **Add app** → **Web** (`</>`). Give it
   any nickname; Firebase Hosting isn't needed.
2. Copy the `firebaseConfig` object it shows.
3. GitHub → **Settings → Secrets and variables → Actions → New repository secret**, name
   `FIREBASE_WEB_CONFIG`, value: the config (the `{ … }` object or the whole snippet both work).
4. Firebase console → **Authentication → Settings → Authorized domains** → add
   `tymonbitesthedust.github.io`.
5. The Firestore rules are the same file as Android's (`firestore.rules`); nothing else to publish.

For local builds, put the config in `web/.env.local`:

```
VITE_FIREBASE_CONFIG={"apiKey":"…","authDomain":"…","projectId":"…","appId":"…"}
```

The web config is not a secret (it ships in every page); access is controlled by `firestore.rules`.

## Publishing

`.github/workflows/web.yml` runs on every push to `main` that touches `web/`: typecheck, unit
tests, end-to-end tests, then deploys `web/dist` to GitHub Pages.

One-time setup: GitHub → **Settings → Pages → Build and deployment → Source: GitHub Actions**.

Installed copies update themselves: the service worker downloads the new version in the
background and Lock In shows "A new version of Lock In is ready · Reload".

## iPhone notes

- **Add to Home Screen.** Safari may clear data for sites not opened for 7 days; Home Screen
  apps are exempt. Settings shows a banner with the steps until Lock In runs from the Home Screen.
- **Home Screen and Safari keep separate storage** on iOS, so people should use one of them.
- **Audio** only plays from a context started by a tap, so the chime is unlocked when a session is
  started or resumed.
- **Shortcuts:** `shortcuts://run-shortcut?name=…` opens the Shortcuts app; Lock In uses it only
  when someone types a Shortcut name in Focus protection.
