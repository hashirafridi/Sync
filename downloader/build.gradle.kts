import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// ---------------------------------------------------------------------------
// Split out from :app for one concrete reason -- it carries roughly 100 MB of
// native payload (CPython 3.8 + yt-dlp + FFmpeg) and needs packaging rules that
// nothing else in the project wants. Keeping it separate also means the whole
// downloader can be swapped for a server-side implementation later by replacing
// one interface, without touching any UI code.
// ---------------------------------------------------------------------------

android {
    namespace = "com.hashir.sync.downloader"
    compileSdk = 35

    defaultConfig {
        minSdk = 24

        // arm only. x86/x86_64 exist solely for emulators, and the emulator is
        // useless for this app anyway (no Facebook app, no hardware encoder).
        // Including them would add ~50 MB for nothing.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // youtubedl-android ships executables that must be extracted to disk at
    // install time -- they are launched as real processes, not loaded as JNI
    // libraries. This is the Gradle equivalent of extractNativeLibs="true".
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    api(libs.youtubedl.android)
    implementation(libs.youtubedl.ffmpeg)

    testImplementation(libs.junit)
}
