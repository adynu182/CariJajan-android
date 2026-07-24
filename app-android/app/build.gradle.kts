import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.carijajan.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.carijajan.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Expose API keys ke BuildConfig dari local.properties
        val localProps = rootProject.file("local.properties")
        val props = Properties().apply { if (localProps.exists()) load(localProps.inputStream()) }
        buildConfigField("String", "SUPABASE_URL",         "\"${props["SUPABASE_URL"] ?: ""}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY",    "\"${props["SUPABASE_ANON_KEY"] ?: ""}\"")
        buildConfigField("String", "MAPTILER_API_KEY",     "\"${props["MAPTILER_API_KEY"] ?: ""}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${props["GOOGLE_WEB_CLIENT_ID"] ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true  // needed for API keys
        aidl = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // ── Core Android ──────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // ── Compose ───────────────────────────────────────────────────────────
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // ── Navigation ────────────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // ── Supabase Kotlin SDK ───────────────────────────────────────────────
    val supabaseBom = platform(libs.supabase.bom)
    implementation(supabaseBom)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.functions)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)

    // ── MapLibre GL Native ────────────────────────────────────────────────
    implementation(libs.maplibre.android)

    // ── CameraX ───────────────────────────────────────────────────────────
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // ── Location (FusedLocationProviderClient) ────────────────────────────
    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)

    // ── Room (offline cache) ──────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── WorkManager ───────────────────────────────────────────────────────
    implementation(libs.workmanager.ktx)

    // ── Coil (image loading) ──────────────────────────────────────────────
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ── ExifInterface ─────────────────────────────────────────────────────
    implementation(libs.exifinterface)

    // ── Kotlinx ───────────────────────────────────────────────────────────
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // ── Tooling (debug only) ──────────────────────────────────────────────
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // ── Tests ─────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

