#!/usr/bin/env bash
# Boots a profile's emulator, installs a Grupetto build in emulation mode, starts the overlay
# and measures it.
#
# usage: tools/emulator/run-profile.sh <profile> [--apk path] [--period-ms N] [--seconds N] [--warmup N] [--show] [--shutdown] [--no-measure]
#   --apk        APK to install (default: app/build/outputs/apk/debug/app-debug.apk)
#   --period-ms  override the emulated sensor sample period (default: the model's hardware rate)
#   --seconds    measurement window (default 20)
#   --show       show the emulator window (default: headless)
#   --warmup     seconds to let the overlay settle before measuring (default 30)
#   --shutdown   stop the emulator afterwards, so profiles run one at a time
set -euo pipefail
cd "$(dirname "$0")"
. ./profiles.sh
export MSYS_NO_PATHCONV=1   # Git Bash: keep /data/... device paths intact

p=${1:?profile: $PROFILES}; shift
line=$(profile "$p")
REPO=$(cd ../.. && pwd)
APK="$REPO/app/build/outputs/apk/debug/app-debug.apk"; PERIOD=""; SECS=20; WINDOW="-no-window"; MEASURE=1; WARMUP=30; SHUTDOWN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --apk) APK=$2; shift ;;
    --period-ms) PERIOD=$2; shift ;;
    --seconds) SECS=$2; shift ;;
    --show) WINDOW="" ;;
    --warmup) WARMUP=$2; shift ;;
    --shutdown) SHUTDOWN=1 ;;
    --no-measure) MEASURE=0 ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
  shift
done

name=$(field "$line" 1); model=$(field "$line" 8); platform=$(field "$line" 9)
case "$p" in bike) port=5560 ;; bikeplus) port=5562 ;; tread) port=5564 ;; g700) port=5566 ;; esac
SERIAL="emulator-$port"
A() { "$ADB" -s "$SERIAL" "$@"; }
# Host paths handed to adb.exe must be Windows paths under Git Bash.
hostpath() { if command -v cygpath >/dev/null; then cygpath -m "$1"; else echo "$1"; fi; }

if ! "$ADB" devices | grep -q "^$SERIAL"; then
  echo "booting $name on $SERIAL"
  "$EMULATOR" -avd "$name" -port "$port" $WINDOW -no-snapshot -no-audio -no-boot-anim -gpu auto >/dev/null 2>&1 &
  "$ADB" -s "$SERIAL" wait-for-device
fi
until [ "$(A shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done

# Match the Peloton tablets: animations are disabled system-wide on the devices we have read.
for k in animator_duration_scale transition_animation_scale window_animation_scale; do A shell settings put global "$k" 0; done
if [ -n "$platform" ]; then A shell settings put global peloton_platform "$platform"; else A shell settings delete global peloton_platform >/dev/null; fi

A install -r "$(hostpath "$APK")" >/dev/null
A shell appops set com.spop.poverlay SYSTEM_ALERT_WINDOW allow
A shell am force-stop com.spop.poverlay

prefs=$(mktemp)
{
  echo '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>'
  echo '<map>'
  echo "    <string name=\"emulatedModel\">$model</string>"
  [ -n "$PERIOD" ] && echo "    <int name=\"emulatedSamplePeriodMs\" value=\"$PERIOD\" />"
  echo '</map>'
} > "$prefs"
A push "$(hostpath "$prefs")" /data/local/tmp/developer.xml >/dev/null
rm -f "$prefs"
A shell "run-as com.spop.poverlay mkdir -p shared_prefs && run-as com.spop.poverlay cp /data/local/tmp/developer.xml shared_prefs/developer.xml"

# Launch and press "Start overlay" (located via the UI hierarchy rather than fixed coordinates).
A shell monkey -p com.spop.poverlay -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
bounds=""
for _ in $(seq 1 15); do
  sleep 2
  A shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || continue
  bounds=$(A shell cat /sdcard/ui.xml | tr '>' '\n' | grep -i 'text="Start overlay"' | grep -o 'bounds="[^"]*"' | head -1 || true)
  [ -n "$bounds" ] && break
done
[ -n "$bounds" ] || { echo "could not find the Start overlay button" >&2; exit 1; }
read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
A shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
sleep 3
A shell input keyevent KEYCODE_HOME
sleep "$WARMUP"

echo "== $p: $name, model=$model, platform=${platform:-<unset>}, period=${PERIOD:-default}"
if [ "$MEASURE" = 1 ]; then
  ADB="$ADB" python -I measure.py "$SERIAL" "$p" "$SECS"
fi
if [ "$SHUTDOWN" = 1 ]; then A emu kill >/dev/null 2>&1 || true; fi
