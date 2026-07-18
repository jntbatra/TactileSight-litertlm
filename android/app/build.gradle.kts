import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Sarvam credentials come from local.properties (gitignored) or the environment.
// Never commit a key — this repo is public (TEAM.md).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Tolerate a quoted value — local.properties is hand-edited, and stray quotes
// would otherwise be baked into the key and fail every request with a 401.
val sarvamApiKey: String =
    (localProps.getProperty("sarvam.api.key") ?: System.getenv("SARVAM_API_KEY") ?: "")
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")

android {
    namespace = "com.tactilesight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tactilesight"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "SARVAM_API_KEY", "\"$sarvamApiKey\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        // Depth is read straight out of the APK; leaving it uncompressed avoids
        // inflating 600 KB per capture on every load.
        noCompress += "npy"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    testOptions {
        // android.util.Log is a stub in JVM unit tests; let it no-op instead of
        // throwing "not mocked", so pure logic stays testable without Robolectric.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
