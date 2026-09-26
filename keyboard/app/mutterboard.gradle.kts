// Mutterboard builds Pastiera's source as a library through this file, not
// through Pastiera's own build.gradle.kts, which stays byte-for-byte upstream so
// nightly commits can be cherry-picked across without conflicting here. That
// file is an application on AGP 8; Mutterboard is on AGP 9, where the separate
// Kotlin plugin and kotlinOptions no longer exist.
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// An application gets these on BuildConfig for free; a library does not, and
// Pastiera's code reads them. They describe the Mutterboard app it ships inside.
val appVersionName: String = (project.findProperty("appVersionName") as String?) ?: run {
    val tag = try {
        providers.exec {
            commandLine("git", "describe", "--tags", "--abbrev=0", "--exclude=pastiera/*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (_: Exception) {
        ""
    }
    tag.removePrefix("v").ifBlank { "1.0" }
}

android {
    namespace = "it.palsoftware.pastiera"
    compileSdk = 36

    defaultConfig {
        minSdk = 29

        manifestPlaceholders["appLabel"] = "Mutterboard"
        manifestPlaceholders["imeLabel"] = "Mutterboard"

        buildConfigField("String", "APPLICATION_ID", "\"com.example.mutterboard\"")
        buildConfigField("String", "VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "VERSION_CODE", "0")
        buildConfigField("String", "FLAVOR", "\"mutterboard\"")
        buildConfigField("String", "RELEASE_CHANNEL", "\"stable\"")
        buildConfigField("boolean", "IS_FDROID_BUILD", "false")
        // Pastiera's updater checks Pastiera's GitHub releases and would offer to
        // install Pastiera over Mutterboard.
        buildConfigField("boolean", "ENABLE_GITHUB_UPDATE_CHECKS", "false")
        // Read by the successor-release announcement, which the updater being
        // off keeps from ever firing.
        buildConfigField("String", "SUCCESSOR_GITHUB_REPOSITORY", "\"pkb-rocks/plektra\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        // The raw JSON dictionaries. The keyboard reads the compiled .dict copies
        // beside them; these would nearly double the APK. Pastiera drops them
        // with a packaging exclude, which does not reach a library's assets.
        ignoreAssetsPatterns += "*_base.json"
    }
    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation(libs.okhttp)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-views:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
