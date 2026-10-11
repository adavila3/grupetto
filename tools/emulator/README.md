# Optional: emulator profiles for Peloton machines

These scripts are **opt-in developer tooling**. Nothing in the Gradle build, CI or the app uses
them. You never need them to build, test or contribute. Use them only if you want to see how a
change behaves on each Peloton machine Grupetto supports before trying it on real hardware.

They create Android emulators that approximate each machine (Android version, screen, RAM, core
count, `peloton_platform`). Grupetto runs in its developer emulation mode, which publishes
simulated sensor data at the hardware's sample rate. Then the overlay is measured: frames, CPU
per thread, and a 5 s frame trace.

| Profile | Machine | Android | Emulated as | Notes |
|---|---|---|---|---|
| `bike` | Original Bike (PLTN-RB1VQ, MT8173, 2 GB) | 11 / API 30 | `Bike` | Read from a device |
| `bikeplus` | Bike+ (PLTN-TTR01 "topaz", QCS605, ~4 GB) | 10 / API 29 | `BikePlus` | Density assumed |
| `tread` | Tread (PLTN-TTR01, `peloton_platform=prism`) | 10 / API 29 | `Tread` | Assumed same image as Bike+ |
| `g700` | G700 CrossTrainer (PLTN-ATR01 / g700) | 13 / API 33 | `CrossTrainer` | SoC, RAM and density unknown |

**Limits:**
- Emulators run on your PC's CPU and GPU, so absolute numbers are far better than the real
  tablets (an MT8173 is a 2015 chip). Compare before and after a change on the same profile, and
  confirm on hardware.
- Model names, Peloton's sensor services and the ANT+ radio exist only on real devices.

## Setup (once)

Requires the Android SDK (`ANDROID_HOME`, or the default location), its command-line tools,
JDK 17 and Python 3. On Windows, run the scripts from Git Bash.

```sh
sdkmanager "system-images;android-29;google_apis;x86_64" \
           "system-images;android-30;google_apis;x86_64" \
           "system-images;android-33;google_apis;x86_64"
tools/emulator/create-avds.sh            # or: create-avds.sh bike tread
```

## Use

```sh
./gradlew assembleDebug
tools/emulator/run-profile.sh bike       # one profile, emulator left running
tools/emulator/run-all.sh                # every profile, one emulator at a time
```

Options for `run-profile.sh`:

| Option | Effect |
|---|---|
| `--apk path` | APK to install (default: the debug build) |
| `--period-ms N` | Sensor sample period (default: the machine's rate) |
| `--seconds N` | Measurement window (default 20) |
| `--warmup N` | Seconds to let the overlay settle first (default 30) |
| `--show` | Show the emulator window (default: headless) |
| `--shutdown` | Stop the emulator afterwards |
| `--no-measure` | Set up only |

To remove everything: `avdmanager delete avd -n <name>` for each `Grupetto_*` emulator.
