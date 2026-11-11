# btManage for Android

Shows the battery levels of wireless earbuds, in the application and in a home screen widget. Kotlin, Jetpack Compose and Glance; the parsing and the device state come from the Rust core.

**Status: skeleton.** The screen, the widget and the JNI link to the core are in place. Bluetooth scanning is not, so both show demo data for now.

Android 8.0 (API 26) or later.

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

The `cargoNdkBuild` task (`app/build.gradle.kts`) builds the core for three ABIs into `app/src/main/jniLibs` before the application is packaged. Two properties in `gradle.properties` control it:

- `btmanage.coreDir`: where the core is checked out
- `btmanage.skipRust=true`: package without the core, for work on the Kotlin side alone. The application then starts and says the core is missing

## CI

`.github/workflows/build.yml` builds a debug APK and uploads it as an artifact. Because btManage-core is private, the workflow needs a repository secret `CORE_REPO_TOKEN`: a fine-grained personal access token with read access to the contents of btManage-core. Pull requests from forks are skipped, since they do not receive secrets.
