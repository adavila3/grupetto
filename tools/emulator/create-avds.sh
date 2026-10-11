#!/usr/bin/env bash
# Creates (or recreates) the emulator for each profile in profiles.sh.
# usage: tools/emulator/create-avds.sh [profile ...]   (default: all)
set -euo pipefail
cd "$(dirname "$0")"
. ./profiles.sh

AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
for p in ${@:-$PROFILES}; do
  line=$(profile "$p")
  name=$(field "$line" 1); image=$(field "$line" 2)
  echo "== $p -> $name ($image)"
  echo no | "$AVDMANAGER" create avd --force -n "$name" -k "$image" -d pixel_c >/dev/null
  cfg="$AVD_HOME/$name.avd/config.ini"
  grep -v -E '^(hw\.lcd\.(width|height|density)|hw\.ramSize|hw\.cpu\.ncore|hw\.initialOrientation|skin\.(name|path)|hw\.keyboard|vm\.heapSize|hw\.gpu\.(enabled|mode))[ ]*=' "$cfg" > "$cfg.tmp"
  cat >> "$cfg.tmp" <<EOF
hw.lcd.width = $(field "$line" 3)
hw.lcd.height = $(field "$line" 4)
hw.lcd.density = $(field "$line" 5)
hw.ramSize = $(field "$line" 6)
hw.cpu.ncore = $(field "$line" 7)
vm.heapSize = 256
hw.initialOrientation = landscape
skin.name = $(field "$line" 3)x$(field "$line" 4)
hw.keyboard = yes
hw.gpu.enabled = yes
hw.gpu.mode = auto
EOF
  mv "$cfg.tmp" "$cfg"
done
"$EMULATOR" -list-avds
