plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ── Versioning ────────────────────────────────────────────────────────────────
// CI sets GITHUB_REF_NAME to the pushed tag (e.g. "v1.2.3").
// Local dev builds fall back to "dev" so the project always compiles.
val tagName = System.getenv("GITHUB_REF_NAME") ?: "dev"
val versionNameStr = tagName.removePrefix("v")          // "1.2.3" or "dev"
val versionParts   = versionNameStr.split(".").mapNotNull { it.toIntOrNull() }
// versionCode must be a positive integer — derive from semver digits, or 1 for dev.
val versionCodeInt = if (versionParts.size == 3)
    versionParts[0] * 10_000 + versionParts[1] * 100 + versionParts[2]
else 1

android {
    namespace = "com.rem.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rem.downloader"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeInt
        versionName = versionNameStr
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ── Signing ───────────────────────────────────────────────────────────────
    // Credentials come from environment variables injected by the CI workflow.
    // Never hard-code passwords or commit the keystore file.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile    = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias      = System.getenv("KEY_ALIAS")
                keyPassword   = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }

    // Required for youtubedl-android packaging
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Prevent duplicate libc++_shared.so conflicts
            pickFirsts += setOf("lib/arm64-v8a/libc++_shared.so", "lib/armeabi-v7a/libc++_shared.so", "lib/x86/libc++_shared.so", "lib/x86_64/libc++_shared.so")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // Activity KTX for viewModels() delegate
    implementation("androidx.activity:activity-ktx:1.9.0")

    // WorkManager for background downloads
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ExoPlayer for video preview
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Coil for thumbnails
    implementation("io.coil-kt:coil:2.6.0")

    // youtubedl-android (yt-dlp wrapper for Android)
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.3")
    implementation("io.github.junkfood02.youtubedl-android:aria2c:0.17.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
