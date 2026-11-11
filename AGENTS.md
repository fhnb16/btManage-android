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
- Calls outside the public Android interface (`BluetoothDevice.isConnected`, `getBatteryLevel`, the battery level broadcast) go through `runCatching` and have a fallback: they may vanish in any release.

## Interface

Material 3, with the colours Android derives from the wallpaper unless the user picks Bluetooth blue.

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
| `…/data/Updates.kt` | The check for a newer release |
| `…/service/DeviceService.kt` | Scanning, paired devices, notifications, widget refresh; the boot receiver |
| `…/ui/` | Screens and shared pieces: battery cells, meter, chart |
| `…/widget/BatteryWidget.kt` | The Glance widget |

- A new setting: a field in `SettingsData` or `WidgetConfig`, load and save in `Settings`, a control in `ui/SettingsScreen.kt`, strings in both languages.
- A new source of levels: feed `Devices` from `DeviceService`.
- A new widget layout: a branch in `Content` in `BatteryWidget.kt` and an option in the settings.
