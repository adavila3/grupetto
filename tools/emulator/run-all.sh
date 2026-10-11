#!/usr/bin/env bash
# Runs every profile one after another (one emulator at a time) and prints each measurement.
# usage: tools/emulator/run-all.sh [extra run-profile.sh options]
set -uo pipefail
cd "$(dirname "$0")"
. ./profiles.sh
for p in $PROFILES; do
  bash ./run-profile.sh "$p" --shutdown "$@" 2>&1 | grep -v "file pushed" || echo "!! $p failed"
  sleep 5
done
