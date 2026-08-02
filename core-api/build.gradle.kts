import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// This module is deliberately a PLAIN KOTLIN/JVM module, not an Android one.
//
// It holds every Meta Graph API call the product makes. Because it has no
// Android dependency, the exact same code can later back a website's server
// without modification -- and today it can be unit-tested on the JVM with
// MockWebServer in milliseconds, with no emulator and no real uploads.
//
// Two rules keep that true. Please don't break them:
//   1. No Android types cross this boundary. No Context, Uri, Bitmap, LiveData.
//      Media is passed as File/InputStream, results as plain data classes.
//      This is compiler-enforced -- `import android.*` simply won't resolve.
//   2. Credentials are constructor parameters, never globals. Nothing here
//      reads BuildConfig. The Android app passes the secret in; a server would
//      pass it from an environment variable. Same code either way.
// ---------------------------------------------------------------------------

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp.logging)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
    }
}
