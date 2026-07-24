# CariJajan — R8 / ProGuard rules for release builds (isMinifyEnabled = true).
#
# This file was referenced by app/build.gradle.kts (proguardFiles(...)) but did not
# exist anywhere in the repo, which put every `assembleRelease` / `bundleRelease` at
# risk of failing outright. The rules below are a reasonable, evidence-based starting
# point for this dependency stack; after adding this file, do a real release build +
# install and watch logcat for ClassNotFoundException / NoSuchMethodError, which would
# mean a dependency needs an additional keep rule.

# ── kotlinx.serialization ──────────────────────────────────────────────────
# Standard rules recommended by the kotlinx.serialization project so the
# generated $$serializer companions for our @Serializable DTOs survive shrinking.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.carijajan.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.carijajan.app.**$$serializer {
    *** INSTANCE;
}
-keepclasseswithmembers class com.carijajan.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Ktor client (Android engine) ───────────────────────────────────────────
# The Android engine is discovered via a ServiceLoader entry
# (io.ktor.client.engine.android.AndroidEngineContainer). R8 can strip the
# service-loader metadata/class if nothing keeps it, which surfaces at
# runtime as "no HttpClientEngine found" rather than at build time.
-keep class io.ktor.client.engine.android.** { *; }
-keep class io.ktor.client.engine.* { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# ── Supabase Kotlin SDK ─────────────────────────────────────────────────────
-dontwarn io.github.jan.supabase.**
-keep class io.github.jan.supabase.** { *; }

# ── Room ─────────────────────────────────────────────────────────────────
# Room ships its own consumer rules, this just protects our entities/DAOs.
-keep class com.carijajan.app.data.local.** { *; }

# ── General ─────────────────────────────────────────────────────────────
-keepattributes Signature, Exceptions
