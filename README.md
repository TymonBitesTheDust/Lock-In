# Lock In

Android study timer with app blocking and live study groups. Kotlin, Jetpack Compose, Material 3.

**Read first:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## What works today

- Stopwatch and Pomodoro (25/5/15) sessions from the timer screen.
- Foreground service with an ongoing notification (pause, resume, skip, stop) and a system-drawn clock; Live Update chip on Android 16+.
- Time stays exact through Doze, process death, late alarms and wall-clock changes. After a reboot the session pauses with time credited up to the last heartbeat.

Finished focus segments are logged, not stored, until the Room schema lands (`LoggingStudyRecordSink`).

## Build

Requirements: JDK 17+, an Android Studio release that supports AGP 9.4, Android SDK Platform 37.

```bash
./gradlew :core:timer:test        # engine and controller tests, plain JVM
./gradlew :app:installDebug
```

## Layout

```
app/                  Application, MainActivity, Hilt root
build-logic/          Convention plugins (lockin.android.*, lockin.hilt, lockin.jvm.library)
core/timer/           Pure-Kotlin timer engine, controller, ports  (no Android)
core/designsystem/    Theme and shared UI
system/timer/         StudyTimerService, notifications, alarms, receivers, DataStore
feature/timer/        Timer screen (MVI ViewModel + Compose)
docs/                 Architecture
```
