import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Meta credentials live in local.properties (git-ignored), never in source.
// Missing values yield empty strings so a fresh clone still builds and runs --
// the dashboard works, only Facebook login is inert until they are filled in.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun localProp(key: String): String = localProps.getProperty(key).orEmpty().trim()

/**
 * The Facebook SDK initialises itself from a ContentProvider at process start, before any
 * of our code runs, and it throws if the app id resource is empty. That would crash the
 * app on launch whenever local.properties has not been filled in yet.
 *
 * Substituting a syntactically valid placeholder keeps the app launchable in that state;
 * GraphConfig.isConfigured still reports false, so the dashboard shows "not configured"
 * instead of pretending login might work.
 */
val facebookAppId: String = localProp("FACEBOOK_APP_ID").ifBlank { "000000000000000" }

// Release signing, also git-ignored. Absent on a fresh clone, in which case release
// builds simply stay unsigned rather than failing the whole configuration phase.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.hashir.sync"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hashir.sync"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        // Read by the Facebook SDK straight out of resources.
        resValue("string", "facebook_app_id", facebookAppId)
        resValue("string", "facebook_client_token", localProp("FACEBOOK_CLIENT_TOKEN"))
        resValue("string", "fb_login_protocol_scheme", "fb$facebookAppId")

        // The *real* values, which may legitimately be blank. Our own code reads these
        // rather than the resources above, so the SDK's placeholder app id can never be
        // mistaken for a configured app.
        //
        // FACEBOOK_APP_SECRET is needed for the short-lived -> long-lived token exchange.
        // It is handed to :core-api as a constructor argument; :core-api never reads
        // BuildConfig itself, which is what keeps that module server-portable.
        buildConfigField("String", "FACEBOOK_APP_ID", "\"${localProp("FACEBOOK_APP_ID")}\"")
        buildConfigField("String", "FACEBOOK_APP_SECRET", "\"${localProp("FACEBOOK_APP_SECRET")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Null when keystore.properties is absent, which leaves the APK unsigned
            // instead of breaking the build for anyone who does not have the key.
            signingConfig = signingConfigs.findByName("release")

            // Left unminified for now. R8 rules for Hilt/Room/Facebook/yt-dlp
            // are worth doing once, properly, at the packaging milestone rather
            // than debugging obfuscation crashes alongside feature work.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // One APK per architecture instead of a single fat one. With ~100 MB of
    // native payload per ABI this roughly halves what lands on each phone.
    // universalApk stays on because sideloading to a phone of unknown ABI is
    // easier when one file definitely works.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
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

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core-api"))
    implementation(project(":downloader"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    implementation(libs.work.runtime.ktx)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.coil.compose)

    implementation(libs.facebook.login)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
