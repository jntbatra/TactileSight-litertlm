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
fun secret(property: String, environment: String): String =
    (localProps.getProperty(property) ?: System.getenv(environment) ?: "")
        .trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")

val sarvamApiKey: String = secret("sarvam.api.key", "SARVAM_API_KEY")

// Qualcomm Cloud AI 100, via Cirrascale's Imagine API. Held on the phone so
// the cloud tier does not depend on our laptop being up and tunnelled — the
// three destinations must fail independently. It is extractable from the APK
// by anyone holding it, which is acceptable for an MVP only the team installs
// and a key rotated after the event, but it is a deliberate trade, not an
// oversight.
val cirrascaleApiKey: String = secret("cirrascale.api.key", "CIRRASCALE_API_KEY")

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
        buildConfigField("String", "CIRRASCALE_API_KEY", "\"$cirrascaleApiKey\"")

        // QAIRT/Hexagon ships arm64 only; other ABIs would bloat the APK with
        // libs that cannot run the model anyway.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    androidResources {
        // Depth is read straight out of the APK; leaving it uncompressed avoids
        // inflating 600 KB per capture on every load.
        noCompress += "npy"
        // TFLite memory-maps its model straight out of the APK; a compressed
        // asset cannot be mapped and has to be copied to disk first.
        noCompress += "tflite"
    }

    packaging {
        jniLibs {
            // GenieX dlopen()s its plugins by path at runtime. With the modern
            // default (libs left compressed inside the APK) the loader is handed
            // "base.apk!/lib/arm64-v8a/libgeniex.so", which is not a real file,
            // and geniex_init dies. The previous app lost time to exactly this.
            useLegacyPackaging = true
        }
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

    // CameraX for the phone-camera source — the standalone path, no band.
    // On-demand ImageCapture only: nothing streams between presses.
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // GenieX — Qualcomm's on-device LLM/VLM runtime. Carries both backends as
    // native libs: libgeniex_plugin_qairt.so (Hexagon NPU, via libQnnHtpV81.so
    // for 8 Elite Gen 5) and libgeniex_plugin_llama_cpp.so (GPU/CPU fallback).
    implementation("com.qualcomm.qti:geniex-android:0.3.12")

    // LiteRT (TFLite) for the YOLOv11 detector. Separate runtime from GenieX
    // on purpose: GenieX eats QAIRT bundles and GGUF, not .tflite, and the
    // detector is a CNN - the graph shape ONNX/TFLite runtimes are good at.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
