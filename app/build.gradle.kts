plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Version and update source. CI overrides them: -Pbtmanage.versionName=1.2.3 and so on.
val versionNameProperty = providers.gradleProperty("btmanage.versionName").getOrElse("0.1.0")
val versionCodeProperty = providers.gradleProperty("btmanage.versionCode").map { it.toInt() }.getOrElse(1)
val updateRepository = providers.gradleProperty("btmanage.updateRepository").getOrElse("fhnb16/btManage-android")

// Release signing. The keystore never lives in the repository: its path and passwords come
// from the environment (see README, "Signing"). Without them the release build is unsigned.
val keystoreFile = providers.environmentVariable("BTM_KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("BTM_KEYSTORE_PASSWORD").orNull
val keyAliasName = providers.environmentVariable("BTM_KEY_ALIAS").orNull
val keyAliasPassword = providers.environmentVariable("BTM_KEY_PASSWORD").orNull
val canSign = listOf(keystoreFile, keystorePassword, keyAliasName).none { it.isNullOrEmpty() }

android {
    namespace = "ru.fhnb16.btmanage"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.fhnb16.btmanage"
        minSdk = 26
        targetSdk = 37
        versionCode = versionCodeProperty
        versionName = versionNameProperty
        // "owner/repository" whose GitHub releases the application checks for updates.
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$updateRepository\"")
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                // A key without its own password uses the keystore's.
                keyPassword = keyAliasPassword?.takeIf { it.isNotEmpty() } ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
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
