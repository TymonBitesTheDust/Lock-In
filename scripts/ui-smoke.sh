#!/usr/bin/env bash
# Emulator smoke test: installs the debug APK, walks every screen, saves screenshots and the
# crash log to $OUT. Exits non-zero if the app crashed or an expected screen element is missing.
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

# Taps the first node whose attributes match the regex, e.g. 'text="Planner"'.
tap() {
  local bounds
  bounds=$(dump | tr '>' '\n' | grep -m1 -E "$1" | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')
  if [ -z "$bounds" ]; then
    echo "MISSING: $1" | tee -a "$OUT/steps.txt"
    FAIL=1
    return 1
  fi
  set -- $(echo "$bounds" | grep -oE '[0-9]+')
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
  echo "tapped: $bounds" >> "$OUT/steps.txt"
  sleep 1.5
}

shot() {
  sleep 1
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "shot: $1" >> "$OUT/steps.txt"
}

adb logcat -c
adb install -r "$APK" || exit 1
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n $PKG/.MainActivity
sleep 4
shot 01-timer-empty

tap 'text="Add"' && {
  tap 'class="android.widget.EditText"'
  adb shell input text "Maths"
  tap 'text="Save"'
}
tap 'text="Add"' && {
  tap 'class="android.widget.EditText"'
  adb shell input text "Physics"
  tap 'text="Save"'
}
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
shot 06-timer-after-session

tap 'text="Planner"' && {
  tap 'class="android.widget.EditText"'
  adb shell input text "Chapter%s3%sproblems"
  adb shell input keyevent 66
  sleep 1
  adb shell input keyevent 4
  shot 07-planner
  tap 'text="Add"' && {
    tap 'class="android.widget.EditText"'
    adb shell input text "Final%sexams"
    tap 'text="Save"'
  }
  shot 08-planner-dday
}
tap 'text="Stats"' && shot 09-stats
adb shell input swipe 500 1600 500 400 300; sleep 1
shot 10-stats-scrolled
tap 'text="Settings"' && shot 11-settings
tap 'text="Focus protection"' && {
  shot 12-focus-protection
  tap 'class="android.widget.Switch"' || tap 'checkable="true"'
  shot 13-focus-protection-on
  adb shell input keyevent 4
}
tap 'text="Subjects"' && { shot 14-subjects; adb shell input keyevent 4; }
tap 'text="Timer"' && shot 15-timer-home

adb logcat -d > "$OUT/logcat.txt"
adb logcat -d -b crash > "$OUT/crash.txt"
if grep -q "FATAL EXCEPTION" "$OUT/crash.txt" "$OUT/logcat.txt"; then
  echo "CRASH detected" | tee -a "$OUT/steps.txt"
  FAIL=1
fi
grep -E "AndroidRuntime|LockIn|lockin" "$OUT/logcat.txt" | grep -E " E | W " | head -200 > "$OUT/app-warnings.txt" || true
exit $FAIL
