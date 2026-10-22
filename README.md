<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/Hero-dark.svg">
  <img src="docs/images/Hero.svg" width="100%" alt="Pen Battery — a stylus drawn as a battery gauge">
</picture>

# 🔋 Pen Battery

**Reads a stylus battery level over Bluetooth LE on Android** — including on devices whose system UI
never shows it.

**English** · [简体中文](README.zh-CN.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Android 13+](https://img.shields.io/badge/Android-13%2B-2d2d2d?logo=android&logoColor=8bc34a)
![Language: Java](https://img.shields.io/badge/Java-AndroidX%20%C2%B7%20Material%203-b07219?logo=openjdk&logoColor=white)
![Network: none](https://img.shields.io/badge/INTERNET-never%20requested-4c1?logo=networkx&logoColor=white)
![Tracking: none](https://img.shields.io/badge/Analytics%20%C2%B7%20telemetry%20%C2%B7%20crash%20reporting-0-4c1)

</div>

> Developed end-to-end with AI assistance — code, documentation and tooling — under human direction,
> with behaviour verified on real hardware.

| | | | |
|:---:|:---:|:---:|:---:|
| 🔌 **Standard GATT**<br>`0x180F` → `0x2A19` | 📴 **Offline by design**<br>no `INTERNET` permission | 🗣️ **Two languages**<br>follows the system | 🧭 **Runtime target**<br>no address baked in |

<details open>
<summary>📖 Contents</summary>

- [⬇️ Installation](#-installation)
- [📸 Screenshots](#-screenshots)
- [✨ Features](#-features)
- [🔒 Permissions and privacy](#-permissions-and-privacy)
- [📱 Supported devices](#-supported-devices)
- [⚠️ Limitations](#-limitations)
- [🧱 Architecture](#-architecture)
- [🤝 Contributing](#-contributing)
- [📚 Third-party notices](#-third-party-notices)
- [📜 License](#-license)

</details>

Styluses report their charge through the standard Bluetooth Battery Service (`0x180F`), but a
tablet's Bluetooth stack does not necessarily subscribe to that service's Battery Level
characteristic (`0x2A19`), so the value never appears in the system settings. This app subscribes
directly over GATT and records the readings locally.

- No network access: the manifest declares no `INTERNET` permission.
- The target device is selected at runtime rather than fixed at build time; the Dell PN7522W
  remains the only model verified so far.
- English and Simplified Chinese, following the system language.

## ⬇️ Installation

Install the prebuilt package from this repository's **Releases** page: the asset `app-release.apk`
attached to the newest tag (currently `v2.7.2`, versionCode 67). It is signed with the project's
release key — signing certificate SHA-1 `4374fe36ebc1326bc9be2e338ecfaae675e6de3a` — and later
releases use the same key, so updating in place keeps the recorded history.

To build from source instead:

```bash
git clone https://github.com/NotRealRobot/pen-battery.git && cd pen-battery/gradle-app
./gradlew :app:assembleDebug          # Windows: gradlew.bat :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires Android 13 (API 33) or later on the tablet. Building requires JDK 17+ and an Android SDK;
Gradle itself is provided by the wrapper. See [CONTRIBUTING.md](CONTRIBUTING.md) for signing a
release and for build-environment details.

## 📸 Screenshots

The interface is Material 3 throughout: tonal surfaces that follow the light or dark theme, and a
floating navigation pill whose background is a real-time Gaussian blur of the content beneath it.

| ☀️ Battery | ☀️ History |
|:---:|:---:|
| <img src="docs/images/Battery_Light.png" width="470"> | <img src="docs/images/History_Light.png" width="470"> |
| 🌙 Battery | 🌙 History |
| <img src="docs/images/Battery_Dark.png" width="470"> | <img src="docs/images/History_Dark.png" width="470"> |

## ✨ Features

| | |
|---|---|
| 🔋 Live level | Subscribes to Battery Level (`0x2A19`) and updates on notification, with a 60-second poll as fallback. |
| ⏱️ Charge-cycle duration | Elapsed time from the last 100 % reading to the first reading at or below 10 %. |
| 📈 History | One point per day as a trend line, plus a per-day table with the last value and the reading count. |
| 📤 Export | CSV through the system file picker. |
| 🧹 Clear | Removes all stored records. |
| 🗣️ Languages | English and Simplified Chinese; default follows the system, overridable in app and persisted by the platform. |
| 🎨 Interface | Material 3 components, light and dark themes, floating navigation pill with a real-time Gaussian blur. |

## 🔒 Permissions and privacy

| | |
|---|---|
| Requested | `BLUETOOTH_CONNECT` — the only runtime permission. The merged manifest additionally carries one app-internal permission that AndroidX declares for its own receiver; it grants nothing outside this app. |
| Not requested | Location, camera, microphone, storage, `BLUETOOTH_SCAN`, `BLUETOOTH_ADMIN`. No device discovery: only already-paired devices can be selected. |
| Network | No `INTERNET` permission, so no socket can be opened. No analytics, telemetry or crash reporting. |
| Storage | Records are kept in the app's private directory. `android:allowBackup="false"`. |
| About screen | Shows the selected device's name and its full Bluetooth address. |

The full declaration is [AndroidManifest.xml](gradle-app/app/src/main/AndroidManifest.xml): one
`uses-permission` element and one activity.

## 📱 Supported devices

Verified on a Dell PN7522W stylus with a Xiaomi Pad 5 (Android 13 / API 33). The read path is not
model-specific: any device exposing the Battery Service (`0x180F`) with a readable and notifiable
Battery Level characteristic (`0x2A19`) is within scope, but only that pair has field data behind it.

The target device is resolved at runtime: a previously selected device if it is still paired,
otherwise a paired device whose name matches common stylus keywords, otherwise a selection dialog.
For an unverified model, the level, trend, per-day details and data-source panels are covered by a
"not supported" overlay and no GATT connection is attempted, so unverified values never enter the
stored history.

## ⚠️ Limitations

- Sampling occurs only while the app is in the foreground. There is no foreground service, no boot
  receiver and no persistent notification; gaps in the chart correspond to gaps in the data.
- One recorded point per day.
- A charge cycle completes only after both endpoints (100 %, then ≤ 10 %) have been observed.
- Battery values are reported by the device firmware (0–100) and are not reinterpreted.
- Some vendor firmware reports an unreliable Bluetooth adapter state.

## 🧱 Architecture

Subscribe to `0x180F` → `0x2A19`, enable the client characteristic configuration descriptor, record
the level on each callback and every 60 seconds, persist one value per day, reconnect with backoff.
12 Java files, approximately 2 200 lines; a file-by-file map is in
[CONTRIBUTING.md](CONTRIBUTING.md#source-map).

## 🤝 Contributing

Issue reports and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) documents the build
environment, the translation constraint (the two `strings.xml` files must keep identical key sets)
and the policy that new permissions require a specific justification.

## 📚 Third-party notices

All direct and transitive dependencies on the release runtime classpath are licensed under
Apache-2.0; no GPL or LGPL component is included.

| Library | Version | License |
|---|---|---|
| [Material Components for Android](https://github.com/material-components/material-components-android) | 1.14.0 | Apache-2.0 |
| [AndroidX](https://android.googlesource.com/) (constraintlayout, swiperefreshlayout, appcompat and transitive dependencies) | various | Apache-2.0 |
| [BlurView](https://github.com/Dimezis/BlurView) | 3.2.0 | Apache-2.0 |
| [MPAndroidChart](https://github.com/PhilJay/MPAndroidChart) | 3.1.0 | Apache-2.0 |
| Kotlin stdlib, kotlinx.coroutines | various | Apache-2.0 |

## 📜 License

Distributed under the [MIT License](LICENSE).
