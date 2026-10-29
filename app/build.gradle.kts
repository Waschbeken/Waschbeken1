import java.util.Properties

plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

// Release signing. Android only installs an update over the app if every version carries the same key.
// The key lives in signing/release.p12 (created once by the "Signatur einrichten" workflow and protected by
// the SIGNING_PASSWORD repository secret). Locally, keystore.properties (not committed) can point to any
// keystore. Without either, release builds use the shared test key in app/debug.keystore – such APKs
// cannot be updated by signed releases.
val localSigning = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val signingStoreFile: File? = (System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() } ?: localSigning.getProperty("storeFile"))
    ?.let { rootProject.file(it) }
    ?: rootProject.file("signing/release.p12").takeIf { it.exists() }
val signingPassword: String? = System.getenv("SIGNING_PASSWORD")?.takeIf { it.isNotBlank() } ?: localSigning.getProperty("storePassword")
val hasReleaseKey = signingStoreFile?.exists() == true && signingPassword != null

// Versions come from the release workflow (tag app-vX.Y.Z); local builds use the default.
val appVersionName: String = System.getenv("APP_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.1.0"
val appVersionCode: Int = run {
    val p = appVersionName.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    p[0] * 1_000_000 + p[1] * 1_000 + p[2]
}

android {
    namespace = "de.waschbeken.heliolink"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.waschbeken.heliolink"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Test key for debug builds, checked in so every CI debug build installs over the previous one.
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingPassword
                keyAlias = localSigning.getProperty("keyAlias") ?: "heliolink"
                keyPassword = localSigning.getProperty("keyPassword") ?: signingPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
