plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.mutterboard"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.mutterboard"
        minSdk = 24
        targetSdk = 36
        // Overridable from CI so a release derives its version from the git tag.
        // Bumped above the latest release (v1.4) so this test build sideloads as
        // an upgrade on a phone that has the release installed. Not a real
        // release — only tagged CI builds reach users.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 10500
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.5-parakeet"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // sherpa-onnx ships native libs for 4 ABIs; keep only the two we use
            // (arm64 phones + x86_64 emulator) to avoid bloating the APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        // Shared key committed to the repo so every build environment (local
        // Android Studio + GitHub Actions) signs identically, allowing in-place
        // updates across sources. This is a debug-style key, not a release key.
        create("shared") {
            storeFile = file("mutterboard.keystore")
            storePassword = "mutterboard"
            keyAlias = "mutterboard"
            keyPassword = "mutterboard"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            signingConfig = signingConfigs.getByName("shared")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.material)
    // On-device speech recognition (NVIDIA Parakeet via ONNX). The .aar bundles
    // the Kotlin API + native libs; fetch it with scripts/fetch-sherpa.sh.
    implementation(files("libs/sherpa-onnx-1.13.3.aar"))
    // Extracts the .tar.bz2 model bundle downloaded at runtime (pure-Java bzip2+tar).
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}