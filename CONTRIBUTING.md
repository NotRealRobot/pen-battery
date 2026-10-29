# Contributing

Setup and conventions for anyone building or changing Pen Battery. The user-facing overview is in
[README.md](README.md).

## Contents

- [Environment](#environment)
- [Build](#build)
- [Signing a release](#signing-a-release)
- [Source map](#source-map)
- [Translations](#translations)
- [Interface copy](#interface-copy)
- [Conventions](#conventions)

## Environment

| | |
|---|---|
| Android | 13 (API 33) or later on the device. `minSdk`/`targetSdk` are both 33. |
| JDK | 17+ (`compileOptions` targets 17; AGP 9.4.1 requires it). |
| Android SDK | any recent platform; the project sets `compileSdk 37`. |
| Gradle | don't install it — the wrapper in `gradle-app/` fetches 9.6.0. |

The Gradle project root is **`gradle-app/`**, not the repository root. Android Studio can open that
folder directly.

## Build

```bash
cd gradle-app
./gradlew :app:assembleDebug          # Windows: gradlew.bat :app:assembleDebug
./gradlew :app:assembleRelease        # unsigned without a keystore, see below
```

Two mirror quirks are baked in, and both are safe to change:

- `gradle/wrapper/gradle-wrapper.properties` downloads Gradle from a public Tencent CDN mirror.
  The canonical equivalent is `https://services.gradle.org/distributions/gradle-9.6.0-bin.zip`.
- `settings.gradle` lists `dl.google.com/dl/android/maven2` and two Aliyun mirrors alongside
  `google()` / `mavenCentral()`. Removing them leaves dependency resolution working as long as you can
  reach the official endpoints; they exist because the author's network can't.

`gradle.properties` sets `android.overridePathCheck=true`. Without it AGP refuses to build any
project whose path contains non-ASCII characters — the author's checkout needs it, and it is
otherwise harmless.

Do **not** enable `org.gradle.vfs.watch` on Windows: file watching there has proven unreliable for
this project, and resources can change without `R` being regenerated.

## Signing a release

Signing material never goes in the repository, so a release build without configuration produces
`app/build/outputs/apk/release/app-release-unsigned.apk`, which Android refuses to install. That is
the expected outcome, not a broken build. To sign:

```bash
cp keystore.properties.example keystore.properties   # then fill it in
```

Put the keystore **outside** the project directory — a relative path is resolved against the project
root, an absolute one is used as-is, and a missing file fails the build loudly rather than silently
producing an unsigned "release". With a valid file the output is `app-release.apk`.

Keeping keys outside the tree matters: project folders get zipped, synced and shared; signing
authority should not travel with them.

## Source map

`gradle-app/app/src/main/java/com/playbuddy/penbattery/` — 12 files, about 2 500 lines.

| File | Responsibility |
|---|---|
| `MainActivity` | permission / Bluetooth / target gating, bottom navigation, the language dialog |
| `PenTarget` | resolve the target device at runtime (remembered → keyword → ask); report whether it is a verified model |
| `PenGatt` | GATT client: `0x180F` → `0x2A19`, subscribe, 60 s poll fallback, reconnect with backoff |
| `State` | one immutable snapshot per refresh; `shouldMask()` is the single place that decides what counts as unsupported |
| `History`, `Samples` | day-by-day persistence and the timestamped sample series; charge-cycle endpoints and sealed cycles are derived from them |
| `BatteryFragment`, `HistoryFragment`, `AboutFragment` | the three tabs |
| `Disabled` | the "not supported" overlay panel (translucent fill + hatch + short label) |
| `Links` | word-level clickable spans, with hit testing that follows the marked word's actual line bounds |
| `ExportHelper` | CSV export via the system file picker |

## Translations

`app/src/main/res/values/strings.xml` is the **English default** (AOSP convention: the
unqualified resource is the fallback), and `values-zh/strings.xml` is the Chinese mirror.

- Key sets must stay identical. A missing key silently falls back to English inside a Chinese UI.
- Strings that only ever reach logcat are deliberately left in Chinese: they are debugging output
  and shouldn't follow the interface language.
- Anything user-visible must be a resource, never a literal in Java. Status text is compared by
  resource id (`State.statusRes`), not by string content — matching on translated text would break
  the moment the language changes.
- Weekday names and duration units are per-locale `string-array`s rather than the device's
  `java.time` formatting, because vendor locale data on cheap ROMs is not dependable.

## Interface copy

State the situation, then the next step. Avoid "error", "failed", "invalid": a device that isn't the
verified model is in a normal, recoverable state, and the UI should read that way. Overlay labels
stay short (roughly six Chinese characters / two or three English words) because they sit on top of
a panel.

Anything tappable must look tappable: an underlined accent-coloured **word**, not a whole panel that
happens to have a click listener.

## Conventions

- Comments in this codebase are mostly Chinese and explain **why** a line exists — a platform quirk,
  a workaround, an invariant that would surprise a reader. Don't delete them for tidiness; add one
  only where the reasoning isn't obvious from the code.
- No new permission without a concrete reason. This app is better off boring.
- Prefer AndroidX / Material / platform APIs over third-party libraries. A new dependency needs a
  license check: the current dependency graph is uniformly Apache-2.0 and adding GPL/LGPL code would
  change what this project can do with its own license.
- Sample only in the foreground. If a change needs background collection, discuss it first — that
  capability was deliberately removed rather than left unimplemented.
