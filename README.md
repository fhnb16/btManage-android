# btManage for Android

Shows the battery of your Bluetooth devices: in the application, in notifications and in a home screen widget. Earbuds get a level for each bud and the case. Kotlin, Jetpack Compose and Glance; the device list, the history and the parsing come from the Rust core.

Android 8.0 (API 26) or later.

**Not run on a device yet.** The application compiles and packages, and its logic lives in the tested core, but nobody has started it on a phone. Expect rough edges; what is known to be open is in [TODO.md](TODO.md).

## What it does

- **Devices.** Every paired device, grouped into connected, nearby and the rest, with its level. Earbuds that are not paired with the phone but advertise nearby are listed too.
- **Device page.** Batteries, an estimate of the time left, a chart of the last 24 hours, type, address, profiles, signal.
- **Notifications.** A quiet ongoing one with the current levels; alerts when your headphones connect and when a battery runs low.
- **Widget.** The levels of up to four devices on the home screen. Configured in the application's settings: how many devices and which, layout (side by side, stacked, grid), detail (one level, or each battery), background (solid, translucent, none), theme and accent colour. Android gives a widget no way to blur the wallpaper behind it, so there is no blur option.
- **Settings.** Notifications and their threshold, theme, accent colour (from the wallpaper, or Bluetooth blue), start with the phone, updates.
- **Updates.** Looks at the GitHub releases of this repository when the application opens and offers the newer APK for download.

A background service does the watching. It keeps scanning with the screen off and starts with the phone unless switched off in the settings.

## Where the levels come from

| Device | Source |
|---|---|
| AirPods, Beats | Bluetooth LE advertisements: left, right, case |
| Fast Pair earbuds | Bluetooth LE advertisements |
| Headsets, mice, keyboards, controllers | The level Android itself holds for a connected device |

Android does not tell applications the product identifier of a paired device, so AirPods are matched to their pairing by name: the pairing must have "AirPods" or "Beats" in its name.

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
