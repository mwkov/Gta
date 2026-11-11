#!/usr/bin/env bash
# Runs on the emulator: installs Spoiler Shield + the test harness, switches the shield on,
# performs scripted swipes on a recycling list and on a web page, records the screen and
# the trace logs into $1.
set -x
OUT="$1"; mkdir -p "$OUT"
PKG=dev.mwkov.spoilershield
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
adb root; sleep 4; adb wait-for-device

# Settings: shield on, cover only the matching text (so cover vs text can be compared
# exactly), debug numbers + test trace on.
adb shell am start -W -n $PKG/.MainActivity; sleep 3; adb shell am force-stop $PKG
cat > "$OUT/shield.xml" <<X
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="enabled" value="true" />
    <boolean name="cover_card" value="false" />
    <boolean name="scroll_blackout" value="true" />
    <boolean name="debug" value="true" />
    <boolean name="trace" value="true" />
</map>
X
adb push "$OUT/shield.xml" /data/local/tmp/shield.xml
adb shell "mkdir -p /data/data/$PKG/shared_prefs && cp /data/local/tmp/shield.xml /data/data/$PKG/shared_prefs/shield.xml && U=\$(stat -c %u /data/data/$PKG) && chown -R \$U:\$U /data/data/$PKG/shared_prefs && chmod 771 /data/data/$PKG/shared_prefs && chmod 660 /data/data/$PKG/shared_prefs/shield.xml && restorecon -R /data/data/$PKG/shared_prefs"
adb shell appops set $PKG ACCESS_RESTRICTED_SETTINGS allow || true
adb shell settings put secure enabled_accessibility_services $PKG/$PKG.ShieldService
adb shell settings put secure accessibility_enabled 1
sleep 6
adb shell dumpsys accessibility | grep -i -A2 "spoiler" | head -20 > "$OUT/service.txt"
adb shell wm size > "$OUT/screen.txt"; adb shell wm density >> "$OUT/screen.txt"

adb logcat -c
adb logcat -v raw -s ShieldTrace:I ShieldHarness:I > "$OUT/log.txt" &
LOGPID=$!

mark() { adb shell log -p i -t ShieldHarness "M $1"; }

gestures() {
  mark slow_drag;   adb shell input swipe 540 1800 540 900 1500; sleep 2.5
  mark medium;      adb shell input swipe 540 1800 540 700 400;  sleep 3
  mark fling_down;  adb shell input swipe 540 2000 540 500 80;   sleep 4
  mark fling_up;    adb shell input swipe 540 500 540 2000 80;   sleep 4
  mark drag_hold;   adb shell input motionevent DOWN 540 1800; sleep 0.15
                    for y in 1750 1700 1650 1600 1550 1500 1450 1400; do adb shell input motionevent MOVE 540 $y; done
                    sleep 0.8
                    for y in 1350 1300 1250 1200 1150 1100; do adb shell input motionevent MOVE 540 $y; done
                    adb shell input motionevent UP 540 1100; sleep 3
  mark repeat_flings
  for i in 1 2 3; do adb shell input swipe 540 1900 540 900 120; sleep 1.2; done
  sleep 3
  mark end
}

run() {   # name activity
  mark "screen $1"
  adb shell am start -W -n dev.mwkov.shieldtest/.$2
  sleep 6                                  # first read of the screen
  adb shell screenrecord --bit-rate 8000000 --time-limit 120 /sdcard/$1.mp4 &
  REC=$!
  sleep 1
  gestures
  adb shell pkill -INT screenrecord; sleep 3; kill $REC 2>/dev/null
  adb pull /sdcard/$1.mp4 "$OUT/$1.mp4"
  adb shell am force-stop dev.mwkov.shieldtest
}

run list ListActivity
run web WebActivity

sleep 2; kill $LOGPID
adb shell screencap -p /sdcard/last.png; adb pull /sdcard/last.png "$OUT/last.png"
wc -l "$OUT/log.txt"
