# Third-party software and data

Gymnotus is licensed under the GNU General Public License, version 3 or (at your option) any later version; see
[LICENSE](LICENSE). It is built with the following components, each under its own licence.

## Libraries

| Component | Version | Licence | Used for |
|---|---|---|---|
| [libadb-android](https://github.com/MuntashirAkon/libadb-android) | 3.1.1 | GPL-3.0-or-later or Apache-2.0 (used here under GPL-3.0-or-later) | ADB client for the fast-mode setup |
| [spake2-java](https://github.com/MuntashirAkon/spake2-java) (`spake2-android`) | 2.2.1 | LGPL-3.0 | SPAKE2 key exchange for ADB pairing, including a native library |
| [sun-security-android](https://github.com/MuntashirAkon/sun-security-android) | 1.1 | GPL-2.0 with the Classpath exception | Building the X.509 certificate of the ADB client |
| [Conscrypt](https://github.com/google/conscrypt) | 2.7.0 | Apache-2.0 | TLS 1.3 for ADB pairing, including a native library |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk15to18`) | 1.81 | Bouncy Castle Licence (MIT-style) | Cryptographic primitives, brought in by libadb-android |
| AndroidX, Jetpack Compose | see `gradle/libs.versions.toml` | Apache-2.0 | User interface and platform support |
| Kotlin standard library, kotlinx.coroutines | see `gradle/libs.versions.toml` | Apache-2.0 | Language runtime |

Versions are those in `gradle/libs.versions.toml`; the licences are as stated by each project at the time of
writing and should be re-checked when a version changes.

## Data

`data/device-maps/blazer.json` records facts about the Pixel 10 Pro and Pixel 10 Pro XL power rails: their names,
the subsystem each is labelled with, and which PMIC they belong to. These were read from the device tree in
Google's kernel release for those devices, which is licensed GPL-2.0-only. The map holds those facts in its own
format and contains no device-tree source.

## Icons

The settings icon (`app/src/main/res/drawable/ic_settings.xml`) is from Google's Material Icons, Apache-2.0.

## Colour palette

The chart colours are the validated categorical palette described in `app/src/main/kotlin/.../ui/TimelineChart.kt`.
