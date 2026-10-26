#!/usr/bin/env bash
# Emulator smoke test: installs the debug APK, walks every screen, exercises app blocking, and
# saves screenshots plus logs to $OUT. Exits non-zero on a crash or a missing screen element.
set -u
APK="$1"
OUT="$2"
PKG=app.lockin
mkdir -p "$OUT"
FAIL=0

dump() {
  for _ in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; then
      adb shell cat /sdcard/ui.xml
      return 0
    fi
    sleep 1
  done
  return 1
}

# Taps the first node matching any of the given attribute regexes, e.g. 'text="Planner"'.
tap() {
  local xml bounds pattern
  xml=$(dump | tr '>' '\n')
  for pattern in "$@"; do
    bounds=$(echo "$xml" | grep -m1 -E "$pattern" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
    if [ -n "$bounds" ]; then
      set -- $(echo "$bounds" | grep -oE '[0-9]+')
      adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
      echo "tapped: $pattern $bounds" >> "$OUT/steps.txt"
      sleep 1.5
      return 0
    fi
  done
  echo "MISSING: $*" | tee -a "$OUT/steps.txt"
  FAIL=1
  return 1
}

shot() {
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "shot: $1" >> "$OUT/steps.txt"
}

open_app() {
  adb shell am start -W -n $PKG/.MainActivity >/dev/null
  sleep 2
}

# Like tap, but a missing element is only logged (for apps that may not exist on every image).
tap_optional() {
  local before=$FAIL
  tap "$@" || { FAIL=$before; echo "optional, skipped: $*" >> "$OUT/steps.txt"; return 1; }
}

# Ticks an app in Focus protection: search by a prefix (so the field's own text doesn't match the
# exact label), then tap the row.
allow_app() {
  tap 'class="android.widget.EditText"' || return 0
  for _ in 1 2 3 4 5 6 7 8 9 10; do adb shell input keyevent 67; done
  adb shell input text "$1"
  sleep 1.5
  tap_optional "text=\"$2\""
}

add_subject() {
  tap 'text="Add"' && {
    tap 'class="android.widget.EditText"'
    adb shell input text "$1"
    tap 'text="Save"'
  }
}

adb logcat -c
adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
open_app
sleep 2
shot 01-timer-empty

add_subject Maths
add_subject Physics
shot 02-timer-subjects

tap 'content-desc="Start Maths"'
sleep 5
shot 03-session-running
adb shell cmd statusbar expand-notifications; sleep 2
shot 04-notification
adb shell cmd statusbar collapse; sleep 1
tap 'text="Pause"'
shot 05-session-paused
tap 'text="Stop"'

tap 'text="Pomodoro 25 / 5"'
tap 'content-desc="Start Physics"'
sleep 3
shot 06-pomodoro-running
tap 'text="Stop"'
tap 'text="Stopwatch"'
shot 07-timer-after-sessions

tap 'text="Planner"' && {
  tap 'class="android.widget.EditText"'
  adb shell input text "Chapter%s3%sproblems"
  adb shell input keyevent 66
  sleep 1
  adb shell input keyevent 4
  tap 'text="Add"' && {
    tap 'class="android.widget.EditText"'
    adb shell input text "Final%sexams"
    tap 'text="Save"'
  }
  shot 08-planner
}
tap 'text="Stats"' && shot 09-stats
adb shell input swipe 500 1600 500 400 300; sleep 1
shot 10-stats-scrolled
tap 'text="Groups"' && shot 11-groups
tap 'text="Settings"' && shot 12-settings

# App blocking end to end: grant both special permissions, turn blocking on, allow two apps,
# start focus, and check the lock screen: over a non-allowed app (Clock), Back goes to Lock In, the
# home screen is covered, and an allowed app opens from the lock screen's shortcuts.
adb shell appops set $PKG GET_USAGE_STATS allow
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow
tap 'text="Focus protection"' && {
  tap 'checkable="true"'
  allow_app Calen Calendar
  allow_app Conta Contacts
  tap 'class="android.widget.EditText"' && for _ in 1 2 3 4 5 6 7 8 9 10; do adb shell input keyevent 67; done
  adb shell input keyevent 4 # hides the keyboard
  shot 13-focus-protection-on
  adb shell input keyevent 4
}
tap 'text="Timer"'
tap 'content-desc="Start Maths"'
sleep 2
adb shell monkey -p com.google.android.deskclock -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
shot 14-blocked-app
tap 'content-desc="Back to Lock In"'
sleep 2
shot 15-back-to-lock-in
adb shell input keyevent 3 # Home
sleep 4
shot 16-home-covered
tap_optional 'content-desc="Calendar"' 'content-desc="Contacts"' && {
  sleep 4
  shot 17-allowed-app-opened
}
open_app
tap 'text="Stop"'
shot 18-timer-home

# Dark theme pass (following the phone).
adb shell cmd uimode night yes
sleep 3
open_app
shot 19-dark-timer
tap 'text="Stats"' && shot 20-dark-stats
tap 'text="Planner"' && shot 21-dark-planner
adb shell cmd uimode night no
sleep 2

# Colour theme: pick Orange and force Dark while the phone is in light mode, then check the app,
# the heatmap and the lock screen follow.
open_app
tap 'text="Settings"' && {
  tap 'content-desc="Orange"'
  shot 22-settings-orange
  tap 'text="Dark"'
  shot 23-settings-orange-dark
}
tap 'text="Timer"' && shot 24-timer-orange-dark
tap 'content-desc="Start Maths"'
sleep 2
adb shell monkey -p com.google.android.deskclock -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 4
shot 25-blocked-orange-dark
open_app
tap 'text="Stop"'
tap 'text="Stats"' && {
  adb shell input swipe 500 1600 500 400 300
  sleep 1
  shot 26-stats-orange-dark
}
tap 'text="Settings"' && {
  tap 'text="Light"'
  shot 27-settings-orange-light
  tap 'text="System"'
  tap 'content-desc="Blue"'
}

adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
if grep -q "FATAL EXCEPTION" "$OUT/crash.txt" "$OUT/logcat.txt"; then
  echo "CRASH detected" | tee -a "$OUT/steps.txt"
  FAIL=1
fi
grep -E "AndroidRuntime|LockIn|lockin" "$OUT/logcat.txt" | grep -E " E | W " | head -200 > "$OUT/app-warnings.txt" || true
exit $FAIL
