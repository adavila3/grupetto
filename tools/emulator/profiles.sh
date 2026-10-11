# Emulator profiles approximating each Peloton machine Grupetto supports.
# Sourced by create-avds.sh and run-profile.sh.
#
# Fields: AVD name | system image | width | height | density | RAM MB | cores | emulated model | peloton_platform
#
# Hardware sources (see README.md in this folder):
#   bike     original Bike, PLTN-RB1VQ: MT8173 (2x A72 + 2x A53), 2 GB, Android 11 / API 30,
#            1920x1080 @ 240 dpi. Read from a device with getprop.
#   bikeplus Bike+, PLTN-TTR01 "topaz": Qualcomm QCS605 (2x A75 + 6x A55), ~4 GB, Android 10 / API 29.
#            Density not yet confirmed; 240 assumed.
#   tread    Tread: same TTR01 tablet as the Bike+, peloton_platform=prism.
#   g700     G700 CrossTrainer, PLTN-ATR01 / g700: Android 13 / API 33. SoC, RAM and density
#            unknown; Bike+-class values assumed.
PROFILES="bike bikeplus tread g700"

profile() {
  case "$1" in
    bike)     echo "Grupetto_Bike_API30|system-images;android-30;google_apis;x86_64|1920|1080|240|2048|4|Bike|v1" ;;
    bikeplus) echo "Grupetto_BikePlus_API29|system-images;android-29;google_apis;x86_64|1920|1080|240|4096|8|BikePlus|titan" ;;
    tread)    echo "Grupetto_Tread_API29|system-images;android-29;google_apis;x86_64|1920|1080|240|4096|8|Tread|prism" ;;
    g700)     echo "Grupetto_G700_API33|system-images;android-33;google_apis;x86_64|1920|1080|240|4096|8|CrossTrainer|" ;;
    *) echo "unknown profile: $1" >&2; return 1 ;;
  esac
}

# Field accessor: field <profile-line> <1-based index>
field() { echo "$1" | cut -d'|' -f"$2"; }

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
ADB="$SDK/platform-tools/adb"
[ -x "$ADB.exe" ] && ADB="$ADB.exe"
EMULATOR="$SDK/emulator/emulator"
[ -x "$EMULATOR.exe" ] && EMULATOR="$EMULATOR.exe"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
[ -f "$AVDMANAGER.bat" ] && AVDMANAGER="$AVDMANAGER.bat"
