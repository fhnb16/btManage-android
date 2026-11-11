plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.fhnb16.btmanage"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.fhnb16.btmanage"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// The Rust core (btmanage-ffi) is built with cargo-ndk into src/main/jniLibs,
// where the Android plugin picks it up. Needs `cargo install cargo-ndk`, the
// Rust targets for the three ABIs and an Android NDK.
val coreDir = rootProject.file(providers.gradleProperty("btmanage.coreDir").getOrElse("../btManage-core"))
val skipRust = providers.gradleProperty("btmanage.skipRust").map { it.toBoolean() }.getOrElse(false)
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust core for Android with cargo-ndk"
    enabled = !skipRust

    workingDir = coreDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-o", jniLibsDir.asFile.absolutePath,
        "build", "--release", "--locked", "-p", "btmanage-ffi",
    )

    inputs.files(File(coreDir, "Cargo.toml"), File(coreDir, "Cargo.lock"))
    inputs.dir(File(coreDir, "crates"))
    outputs.dir(jniLibsDir)

    // Locals, not the script properties above: the action must not hold on to the build
    // script, or the configuration cache cannot store it.
    val manifest = File(coreDir, "Cargo.toml")
    val corePath = coreDir.absolutePath
    doFirst {
        check(manifest.exists()) {
            "btManage-core was not found at $corePath. Clone it next to this repository " +
                "or set btmanage.coreDir in gradle.properties."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
}
