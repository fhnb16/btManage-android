# Working in btManage-android

Guidance for anyone, human or AI, changing this repository.

## What this is

The Android client of btManage: Kotlin, Jetpack Compose for the screens, Glance for the widget, a foreground service for the watching. Parsing, the device list, history and the update check live in the Rust core (`../btManage-core`), reached through JNI.

The maintainer tests on Windows only. This application has been compiled and packaged but **not run on a device**. Treat every runtime behaviour as unconfirmed, and say so when you add more.

## Rules

- **Logic that every platform needs goes into the core**, not here.
- **`btmanage.core.Native` is a contract.** Its package and method names are baked into the core's JNI symbols (`Java_btmanage_core_Native_*`). Change both sides together. The application's own package, `ru.fhnb16.btmanage`, is free of that.
- **The application id is `ru.fhnb16.btmanage`.** Changing it makes a different application to Android: installed copies stop updating.
- **Never put a keystore or a password into the repository.** Signing reads the environment; see `README.md`.
- **The release file name ends in `.apk`**, which is what the updater looks for.
- Tooling is Gradle, Kotlin and Rust. No Python or Node.
- Do not commit or push unless asked. The maintainer does that.
- **Raise the version with every change that ships**, as `major.minor.patch`: patch for a fix, minor for a new capability, major for a break. The version is `btmanage.versionName` in `gradle.properties`. The version code comes from CI, and CI adds a fourth number by itself only when the same version is built again.
- Calls outside the public Android interface (`BluetoothDevice.isConnected`, `getBatteryLevel`, the battery level broadcast) go through `runCatching` and have a fallback: they may vanish in any release.

## Interface

Four designs, chosen in the settings and defined in `ui/Design.kt`: Material You (Material 3, colours from the wallpaper unless the user picks Bluetooth blue), Fluent (the palette, corners and type sizes of the Windows application), Holo (Android 4.3) and Eclair (Android 2.1). All run on the same Material 3 components; a design changes the colour scheme, shapes and typography.

- Screens must look right in all of them. Do not use `Card`, a button's default shape or `SegmentedButtonDefaults.itemShape` directly: use `Panel`, `buttonShape()` and `segmentShape()` from `ui/Design.kt`, which pick the right form. Tab labels and section headers go through `caps()`, which Holo turns into capitals; the top bar takes `topBarColors()`.
- Take colours from `MaterialTheme.colorScheme` only. A literal colour in a screen breaks one of the designs.
- The widget has no Compose theme; its colours for each design are in `palette` in `widget/BatteryWidget.kt`.

- One accent, kept for the charge meters, the leading line of the chart and the selection. The error colour only for a low battery.
- Numbers use tabular figures and one format (`DeviceText.percent`).
- Sentence case. No exclamation marks, no trailing period on labels, no emoji.
- An unknown value is left out. A remembered value is dimmed and carries its age.
- The ten-segment meter is the signature: earbuds report their charge in steps of 10 %.
- Every string is in `res/values/strings.xml` and `res/values-ru/strings.xml`.

## Checking a change

There is no NDK on the maintainer's machine, so the Rust part builds in CI only. The Kotlin side builds locally without it:

```
./gradlew :app:assembleDebug -Pbtmanage.skipRust=true
./gradlew :app:assembleRelease -Pbtmanage.skipRust=true      also runs lint
```

An APK built this way has no core library and shows "The core library is missing"; it proves that the code compiles and packages, nothing more. After a change to the JNI surface, compile the core for Android: in `btManage-core`, `cargo clippy -p btmanage-ffi --target aarch64-linux-android -- -D warnings`.

## Where things go

| Path | What is there |
|---|---|
| `app/src/main/kotlin/btmanage/core/Native.kt` | JNI declarations |
| `…/data/Devices.kt` | The device list as a flow, over the core's hub; state on disk |
| `…/data/Settings.kt` | Preferences, including the widget's configuration |
| `…/data/Updates.kt` | The updater: check, download, hand-over to Android's package installer, and the receiver of its result |
| `…/service/DeviceService.kt` | Scanning, paired devices, notifications, widget refresh; the boot receiver |
| `…/ui/` | Screens and shared pieces: battery cells, meter, chart |
| `…/widget/BatteryWidget.kt` | The Glance widget |

- A new setting: a field in `SettingsData` or `WidgetConfig`, load and save in `Settings`, a control in `ui/SettingsScreen.kt`, strings in both languages.
- A new source of levels: feed `Devices` from `DeviceService`.
- A new widget layout: a branch in `Content` in `BatteryWidget.kt` and an option in the settings.
