# Device maps

One JSON file per device family. A map adds what the PowerMonitor API does not say about a device's rails;
without one the app still works and guesses the sources from rail names.

Maps are bundled into the app as assets. The app uses the first file whose `devices` list contains `Build.DEVICE`.

## Fields

| Field | Required | Meaning |
|---|---|---|
| `format` | yes | Schema version, currently `1` |
| `devices` | yes | `Build.DEVICE` values the map applies to |
| `description` | no | Shown in the app, e.g. model and SoC |
| `provenance` | no | Where the facts come from, and what was checked on a real device |
| `batteryRail` | no | Rail that measures the battery's output; the root of the tree on battery power |
| `sources` | yes | Where rails are measured: `id`, display `name`, and a `railPattern` regular expression matched against the whole rail name (the part inside the brackets of the monitor name) |
| `unmonitored` | no | Rails the device has but exposes no monitor for: `rail`, `subsystem`, `kind` (`buck` or `ldo`) |

Regular expressions must not rely on backslash escapes that JSON would need doubled; prefer `[0-9]` to `\d`.

## Rules for contributions

- Only facts with a source: a published device tree, or readings from the device itself. Say which in `provenance`.
- No parent/child links between rails unless the source states them. The Pixel 10 device tree, for example,
  lists every buck and LDO with its subsystem but does not say which LDO is fed by which buck, so the map does
  not say so either.
- `app/src/test/.../DeviceMapTest.kt` parses every file in this folder; run `./gradlew testDebugUnitTest`.

## Current maps

| File | Devices | Checked on a device |
|---|---|---|
| `blazer.json` | Pixel 10 Pro (`blazer`), Pixel 10 Pro XL (`mustang`) | Pixel 10 Pro, Android 17: the 32 monitored rail names match the device tree. `mustang` is included because its PMIC device tree is identical; it has not been checked on a device. |
