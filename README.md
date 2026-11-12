# btManage for Android

Shows the battery of your Bluetooth devices: in the application, in notifications and in a home screen widget. Earbuds get a level for each bud and the case. Kotlin, Jetpack Compose and Glance; the device list, the history and the parsing come from the Rust core.

Android 8.0 (API 26) or later.

**Barely run on a device.** The first start on a phone listed the paired devices but showed no battery levels. Version 0.3.0 answers that with more sources for the level, a connect button, drawn controls for three of the designs and a diagnostics page; none of it has been compiled or started yet. Expect rough edges; what is known to be open is in [TODO.md](TODO.md). If a level is still missing, the text under Settings, About, Diagnostics says why.

## What it does

- **Devices.** Every paired device, grouped into connected, nearby and the rest, with its level. Earbuds that are not paired with the phone but advertise nearby are listed too.
- **Device page.** Connect or disconnect, batteries, an estimate of the time left, a chart of the last 24 hours, type, address, profiles, the audio codec in use, signal.
- **Notifications.** A quiet ongoing one with the current levels; alerts when your headphones connect and when a battery runs low.
- **Widget.** The levels of up to four devices on the home screen. Configured in the application's settings: how many devices and which, layout (side by side, stacked, grid), detail (one level, or each battery), background (solid, translucent, none), theme and accent colour. Android gives a widget no way to blur the wallpaper behind it, so there is no blur option.
- **Four designs.** Material You, Android's own look, with colours from the wallpaper on Android 12 and later. Fluent, the look of the Windows application: grey surfaces, bordered cards, toggle switches, combo boxes, the Windows type sizes. Android 4.3 (Holo): the action bar, tabs over a blue line, the ON/OFF switch, spinners, lists under headers in capitals. Android 2.1: the narrow title bar, grey gradient buttons and tabs, tick boxes, the orange highlight. Material You uses the Material 3 components; the other three draw their own controls. The widget follows the choice.
- **Settings**, on four tabs. General: notifications and their threshold, start with the phone, extended access (Shizuku, root). Appearance: design, theme, accent colour (the system's, or Bluetooth blue). Widget. About: updates, versions, diagnostics.
- **Updates.** Looks at the GitHub releases of this repository when the application opens and once a day in the background. A newer release is downloaded, checked against the SHA-256 GitHub reports, and handed to Android's package installer, without a trip to the browser. See [Updates](#updates) for what Android asks the user.

A background service does the watching. It keeps scanning with the screen off and starts with the phone unless switched off in the settings.

## Where the levels come from

| Device | Source |
|---|---|
| AirPods, Beats | Bluetooth LE advertisements: left, right, case |
| Fast Pair earbuds | Bluetooth LE advertisements |
| Headsets and headphones | The level Android holds for a connected device; the broadcast Android sends when it changes; the battery events a headset sends over its hands-free link (`+IPHONEACCEV`, `+XEVENT`, the hands-free battery indicator) |
| Bluetooth LE mice, keyboards, remotes | The level Android holds; where it holds none, the GATT Battery Service is read directly |
| Controllers and everything else | The level Android holds for a connected device |

Whether a device is connected comes from Android's profile services (audio, hands-free, input, hearing aid, LE Audio, GATT), with the connection broadcasts and a call outside the public interface as further witnesses.

The scan for advertisements is restarted every twenty minutes, because Android stops delivering to a scan that runs for half an hour. In the background it carries filters, which Android demands for a scan that survives the screen going off; while the application is on screen it takes everything.

Android does not tell applications the product identifier of a paired device, so AirPods are matched to their pairing by name: the pairing must have "AirPods" or "Beats" in its name. A pairing under another name is matched by the product identifier the accessory gives over its hands-free link (`+XAPL`), once it has connected while the application was running.

## Connecting, disconnecting and the codec

Android has no public call for connecting or disconnecting a paired device, and from Android 13 it tells only privileged applications the audio codec. The buttons on a device page try, in this order:

1. The calls an ordinary application could use before Android 12.
2. [Shizuku](https://shizuku.rikka.app/), if it is running. The first time, Shizuku asks whether btManage may use it.
3. Root, if "Use root access" is switched on under Settings, General.

With Shizuku or root the application starts a small part of itself (`elevated/Helper.kt`) as the shell user or as root, and that part asks Android to connect or disconnect. The codec is read from `dumpsys bluetooth_manager` the same way. Without either, the page says so and offers Android's Bluetooth settings.

None of the three paths has been run on a device.

## Updates

The application updates itself from the releases of this repository. Android has the last word on installing, so the first update takes two answers from the user:

1. **Letting btManage install apps.** The application opens Android's own screen for it ("Install unknown apps") the first time an update is found, and when "Download and install updates on their own" is switched on.
2. **Confirming the installation**, in Android's dialog. If the application is not on screen at that moment, a notification leads to the dialog.

From Android 12 on, Android lets an application update itself without the second question once it has done so before, so later updates go through silently. Before Android 12 every update is confirmed.

Android refuses an update that is not signed with the same key as the installed application, whatever the application itself checks.

In the background an update is downloaded only over a connection that is not metered. With automatic installation switched off, or without the right to install, the user gets a notification that a newer version exists and installs it from the banner in the application.

## Build

Needs JDK 17 or later, the Android SDK with an NDK, Rust (stable) and the btManage-core repository checked out next to this one:

```
btManage-core/
btManage-android/
```

One-time setup for the Rust part:

```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

```
./gradlew :app:assembleDebug
```

The `cargoNdkBuild` task (`app/build.gradle.kts`) builds the core for three ABIs into `app/src/main/jniLibs` before the application is packaged. Properties, in `gradle.properties` or with `-P`:

| Property | Meaning |
|---|---|
| `btmanage.versionName` | Version of the application. CI overrides it |
| `btmanage.versionCode` | Version code; must grow with every release. CI passes the run number |
| `btmanage.coreDir` | Where the core is checked out |
| `btmanage.skipRust=true` | Package without the core, for work on the Kotlin side alone. The application then starts and says the core is missing |
| `btmanage.updateRepository` | `owner/repository` whose releases are checked for updates |

## Signing

Release builds are signed with your own key. The key never enters the repository: the build reads it from the environment.

| Variable | Meaning |
|---|---|
| `BTM_KEYSTORE_FILE` | Path to the keystore (`.jks`, `.keystore` or `.p12`) |
| `BTM_KEYSTORE_PASSWORD` | Password of the keystore |
| `BTM_KEY_ALIAS` | Alias of the key inside it |
| `BTM_KEY_PASSWORD` | Password of the key; leave unset if it is the keystore's |

With these set, `./gradlew :app:assembleRelease` produces a signed `app/build/outputs/apk/release/app-release.apk`. Without them the release APK is unsigned and cannot be installed.

### On GitHub

In the repository: **Settings → Secrets and variables → Actions → New repository secret**. Add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | The keystore file as base64 text, see below |
| `ANDROID_KEYSTORE_PASSWORD` | Password of the keystore |
| `ANDROID_KEY_ALIAS` | Alias of the key |
| `ANDROID_KEY_PASSWORD` | Password of the key, if it differs from the keystore's |
| `CORE_REPO_TOKEN` | Fine-grained token with read access to the contents of btManage-core |

To turn the keystore into text, in PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\my.keystore")) | Set-Clipboard
```

or on Linux and macOS: `base64 -w0 my.keystore`. Paste the result as the value of `ANDROID_KEYSTORE_BASE64`.

If you do not remember the alias: `keytool -list -v -keystore my.keystore` prints it.

Once the secrets are there, every build is a signed release build. Without `ANDROID_KEYSTORE_BASE64` the workflow falls back to a debug build, named `…-debug.apk`.

Keep the same key for good. Android only installs an update over an application signed with the same key, and a debug build cannot be updated to a release build or back.

## CI and releases

`.github/workflows/build.yml` builds the APK on every push. A push to `main` or `master` also publishes a release: the version is `btmanage.versionName` from `gradle.properties`, with a fourth number appended when that version has been released before. Pushing a tag `v1.2.3` releases exactly that version. Pull requests from forks are skipped, since they do not receive secrets.
