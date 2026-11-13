# Working in btManage-android

Guidance for anyone, human or AI, changing this repository.

## What this is

The Android client of btManage: Kotlin, Jetpack Compose for the screens, Glance for the widget, a foreground service for the watching. Parsing, the device list, history and the update check live in the Rust core (`../btManage-core`), reached through JNI.

The maintainer develops on Windows and tries builds on a phone of their own; there is no emulator and none is wanted. Almost nothing here has been confirmed on a device. Treat every runtime behaviour as unconfirmed, and say so when you add more.

When something does not work on the phone, ask for the text under Settings, About, Diagnostics (`data/Diagnostics.kt`) before guessing. Add a line there for every new source of data.

## Rules

- **Logic that every platform needs goes into the core**, not here.
- **`btmanage.core.Native` is a contract.** Its package and method names are baked into the core's JNI symbols (`Java_btmanage_core_Native_*`). Change both sides together. The application's own package, `ru.fhnb16.btmanage`, is free of that.
- **The application id is `ru.fhnb16.btmanage`.** Changing it makes a different application to Android: installed copies stop updating.
- **Never put a keystore or a password into the repository.** Signing reads the environment; see `README.md`.
- **The release file name ends in `.apk`**, which is what the updater looks for.
- Tooling is Gradle, Kotlin and Rust. No Python or Node.
- Do not commit or push unless asked. The maintainer does that.
- **Raise the version with every change that ships**, as `major.minor.patch`: patch for a fix, minor for a new capability, major for a break. The version is `btmanage.versionName` in `gradle.properties`. The version code comes from CI, and CI adds a fourth number by itself only when the same version is built again.
- Calls outside the public Android interface (`BluetoothDevice.isConnected`, `getBatteryLevel`, the battery level broadcast, anything reached by reflection) are caught and have a fallback: they may vanish in any release.
- **`elevated/Helper.kt` does not run inside the application.** It is started with `app_process` as the shell user or as root: no `Context`, no resources, no permissions of the package. Keep it free of everything but the Android framework and the Kotlin standard library.
- **Nothing asks for Shizuku or root on its own.** The user presses a button that needs it, or switches root on. Reading (the codec, Android's record of a device) uses only access that was already given.
- **The scan permission stays `neverForLocation`.** CAPod, OpenPods and LibrePods declare it the same way and receive what AirPods broadcast, so a missing level is not a reason to ask for location.

## Interface

Four designs, chosen in the settings: Material You (Material 3, colours from the wallpaper unless the user picks Bluetooth blue), Fluent (after the Windows application), Holo (Android 4.3) and Eclair (Android 2.1). `ui/Design.kt` holds their colours, shapes and type. `ui/Controls.kt` holds their controls: Material You uses the Material 3 components, the other three draw their own.

- Screens must look right in all of them, so screens do not use Material 3 controls directly. Use what `ui/Controls.kt` offers: `AppTopBar`, `AppTabs`, `AppButton`, `Section` with `Modifier.entry()` or `Entry` for its children, `SettingToggle`, `SettingChoice`, `OptionRow`, `Note`, `Modifier.pressable` for anything that is tapped, `Modifier.page()` for the padding of a scrolling page; and `Panel` from `ui/Design.kt` for a card. A new kind of control gets a branch per design there, not an `if` in a screen.
- Everything that is tapped must also work with a TV remote or a keyboard, and show where the focus is. `Modifier.pressable` and the controls of `ui/Controls.kt` do both; anything made with `clickable` directly, or a Material 3 control used directly, gets `Modifier.focusRing()` right before it.
- Take colours from `MaterialTheme.colorScheme` in screens. Colours that belong to one design only (the grey gradients of Android 2.1) are constants in `ui/Controls.kt`.
- The widget has no Compose theme; its colours for each design are in `palette` in `widget/BatteryWidget.kt`.

- One accent, kept for the charge meters, the leading line of the chart and the selection. The error colour only for a low battery.
- Numbers use tabular figures and one format (`DeviceText.percent`).
- Sentence case. No exclamation marks, no trailing period on labels, no emoji.
- An unknown value is left out. A remembered value is dimmed and carries its age.
- The ten-segment meter is the signature: earbuds report their charge in steps of 10 %.
- Every string is in `res/values/strings.xml` and `res/values-ru/strings.xml`. The one exception is the diagnostics text, which stays in English so that it can be pasted into a report. A string missing from either file fails the release build.

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
| `…/data/DeviceActions.kt` | Connect, disconnect, the codec: what is tried and in which order |
| `…/data/Elevated.kt` | Shizuku and root: asking for them, running a command with them |
| `…/data/Diagnostics.kt` | What the service has seen, for the diagnostics page |
| `…/data/Discovery.kt` | The search for devices to pair with, and the pairing |
| `…/data/DeviceFacts.kt` | Android's own record of a device (levels of single earbuds, maker, model, firmware), read with Shizuku or root |
| `…/PopupActivity.kt` | The panel a tap on the widget opens, over the home screen |
| `…/elevated/Helper.kt` | The program run as shell or root; also the connect request both sides share |
| `…/service/DeviceService.kt` | Scanning, paired devices, battery broadcasts and headset events, notifications, widget refresh; the boot receiver |
| `…/service/Links.kt` | Android's profile services: who is connected, the codec; the parser of `dumpsys` |
| `…/service/GattBattery.kt` | Reading the Battery Service of a Bluetooth LE device |
| `…/service/Aap.kt` | The channel of Apple's accessory protocol to AirPods: levels, noise control; the packets come from the core |
| `…/ui/Design.kt`, `…/ui/Controls.kt` | The designs: colours, shapes, type; and the controls each one draws |
| `…/ui/` | Screens and shared pieces: battery cells, meter, chart; `PairingScreen.kt` is the search for a new device, as a panel and as a dialog |
| `…/widget/BatteryWidget.kt` | The Glance widget |

- A new setting: a field in `SettingsData` or `WidgetConfig`, load and save in `Settings`, a control in `ui/SettingsScreen.kt`, strings in both languages.
- A new source of levels: feed `Devices` from `DeviceService`, and count it in `Diagnostics`.
- A new widget layout: a branch in `Content` in `BatteryWidget.kt` and an option in the settings.
