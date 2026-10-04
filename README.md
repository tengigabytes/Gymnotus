<img src="store/logo.svg" width="96" alt="Gymnotus logo">

# Gymnotus

English | [正體中文](README.zh-TW.md)

An Android app that shows how much power each power rail of the phone is drawing, on the phone itself: no root,
no computer. It reads the on-device power monitors (ODPM) through the PowerMonitor API of Android 15.

## What it shows

- **Live**: the sum of the measured rails, a flow diagram from power sources to subsystems, a trend chart of the
  rails you pick, and the rails as a list grouped by subsystem or by power source.
- **Dashboard**: one tile per rail with its power now, the last minute as a sparkline and its range, plus the
  battery's voltage, current and power.
- **Log**: writes every reading to a CSV file you choose while you use other apps or turn the screen off, and
  exports the last five minutes on demand.
- **Details**: what is needed to judge the measurement itself: polling statistics, cross-checks between the
  system's own totals and the rails they are made of, and the raw readings.
- **Settings** (the gear icon): update mode, polling interval, display options, licence and privacy texts.

The interface is in English and Traditional Chinese.

## Requirements

- Android 15 (API 35) or later, on a device that has ODPM rails. That is expected of Pixel 6 and later; other
  devices return no power monitors and the app says so.
- Tested on one device only so far: a Pixel 10 Pro on Android 17. Reports from other devices are welcome.

## Build and install

You need JDK 17 or later and Android SDK Platform 37.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` makes the minified build. It is signed with your own key if a `keystore.properties`
file (not in the repository) names one with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`; without
that file it is signed with the debug key, which is enough to try it on a device.

## Update rate and the optional permission

The app needs no permission to work, but the system then refreshes the readings at most every 20 seconds. With
`ACCESS_FINE_POWER_MONITORS` the limit is 250 ms; on the Pixel 10 Pro readings were seen to change about every
0.5 s. The permission cannot be asked for in the usual dialog. It is granted once and stays after a reboot, in
either of two ways:

- **Without a computer**: in Settings, tap "Set up fast mode", open Developer options > Wireless debugging >
  Pair device with pairing code, and type the six-digit code into the Gymnotus notification. The app connects to
  the phone's own wireless debugging as an ADB client and grants itself the permission. Wi-Fi must be on; the
  connection stays on the phone (loopback).
- **With a computer**:

  ```sh
  adb shell pm grant io.github.tengigabytes.gymnotus android.permission.ACCESS_FINE_POWER_MONITORS
  ```

## How to read the numbers

- Power is always an energy difference divided by a time difference, both taken from the rail's own readings.
- A value that is not available is shown as missing, never as 0.
- The system adds a small random noise to the energy values it returns, in both modes.
- The battery rail counts discharge only. On battery it is close to the whole device; on external power there
  is no figure for the whole device, and the app says so instead of showing one.
- Voltage and current of a single rail are not available to an app; only the battery has all three.
- With the screen off the phone sleeps and polling pauses. No energy is lost: the first reading after waking
  covers the whole sleep. By default the app polls every 5 s while the screen is off.

## CSV files

Lines starting with `#` hold metadata (device, monitors, statistics). The rest is a long-format table, one row
per rail per poll, with the raw accumulated energy, the rail's own timestamp, and battery and device context.
Missing values are left empty. With pandas: `pd.read_csv(path, comment="#")`.

## Device maps

Which rail belongs to which power source, and which rails add up to which system total, comes from a JSON file
per device in [`data/device-maps`](data/device-maps). Without a map the app still works and groups rails by the
subsystem in their names. Maps for other devices are welcome; see the README there.

## Privacy

Everything stays on the device; see [PRIVACY.md](PRIVACY.md).

## Licence

Copyright © 2026 tengigabytes and Gymnotus contributors

Gymnotus is free software under the GNU General Public License, version 3 or (at your option) any later
version; see [LICENSE](LICENSE). Third-party libraries and data sources are listed in
[THIRD_PARTY.md](THIRD_PARTY.md).

## The name

*Gymnotus* is a genus of weakly electric fish of the Neotropics, which sense their surroundings through
disturbances of their own electric field, much as this tool watches where current goes without getting in its
way. Linnaeus first named the electric eel *Gymnotus electricus* in 1766; it was moved to *Electrophorus* in 1864.

The word itself means "naked back", for the missing dorsal fin, and has nothing to do with electricity.
