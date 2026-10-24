# Lock In

Android study timer with per-subject tracking, a planner, statistics, app blocking and live study groups. Kotlin, Jetpack Compose, Material 3.

**Read first:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Features

- **Timer**: stopwatch or Pomodoro (lengths in Settings) per subject, to the second. Today's total and each subject's time on the home screen. Foreground service with an ongoing notification and a system-drawn clock; time stays exact through Doze, process death, late alarms and clock changes.
- **Subjects**: add, rename, recolour, remove (history is kept).
- **Planner**: week strip, to-dos per day (tag a subject, start the timer from a task, move to the next day), and the day's study log.
- **D-Day**: countdowns to exams and deadlines; the nearest shows on the timer screen.
- **Stats**: day (by hour), week and month (by day), per-subject breakdown, 18-week consistency heatmap and streaks.
- **Focus protection**: during running focus phases, any app not on your allowed list is covered by a lock screen. Breaks and pauses never block. Needs usage access and display-over-other-apps.
- **Groups** (Firebase): email sign-in, create or join by 6-character code, see who is studying right now with live clocks, today/week rankings, "shake to wake" nudges, and chat that only opens outside focus.

## Build

Requirements: JDK 17+, an Android Studio release that supports AGP 9.4, Android SDK Platform 37.

```bash
./gradlew :core:timer:test :core:model:test   # engine, controller and stats tests, plain JVM
./gradlew :app:assembleDebug                   # app/build/outputs/apk/debug/app-debug.apk
```

Every push to `main` builds the APK on GitHub Actions (artifact `lock-in-debug-apk`) and runs it on an emulator, walking every screen (`scripts/ui-smoke.sh`). Debug builds are signed with the shared `keystore/debug.keystore`, so a new APK installs over the old one. Never use that key for release.

## Firebase (study groups)

Without Firebase the app works fully; the Groups tab says groups aren't connected.

1. In the Firebase console, create a project, add an Android app with package `app.lockin`, and download `google-services.json`.
2. Authentication → Sign-in method → enable **Email/Password**.
3. Firestore Database → create (production mode) → **Rules** → paste [`firestore.rules`](firestore.rules) → Publish.
4. Local builds: put the file at `app/google-services.json` (git-ignored). CI: save its contents as the repository secret `GOOGLE_SERVICES_JSON`.

Everything runs on the free Spark plan. Nudges arrive instantly while the friend's app is running and within about 15 minutes otherwise; instant delivery to a closed app needs FCM from a Cloud Function (Blaze plan).

## Layout

```
app/                  Application, MainActivity, tab navigation, Hilt root
build-logic/          Convention plugins (lockin.android.*, lockin.hilt, lockin.jvm.library)
core/timer/           Pure-Kotlin timer engine, controller, ports (no Android)
core/model/           Pure-Kotlin domain models and study-time maths (no Android)
core/database/        Room schema and DAOs
core/data/            Repositories, preferences, Room record sink, session launcher
core/network/         Firebase auth, groups, presence, chat, nudges
core/designsystem/    Theme, charts, heatmap, shared components
system/timer/         StudyTimerService, notifications, alarms, receivers, DataStore
system/blocker/       Foreground-app detection and the lock overlay
system/presence/      Live status publishing, nudge notifications and background check
feature/*             timer, planner, stats, subjects, blocker, settings, groups
docs/                 Architecture
```
