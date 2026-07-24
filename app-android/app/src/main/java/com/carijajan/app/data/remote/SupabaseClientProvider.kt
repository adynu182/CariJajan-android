package com.carijajan.app.data.remote

import com.carijajan.app.BuildConfig
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage

/**
 * Singleton Supabase client.
 * Akses via [SupabaseClientProvider.client].
 *
 * Jangan gunakan service_role key di sini — hanya anon key yang boleh ada di app.
 */
object SupabaseClientProvider {

    val client by lazy {
        val rawUrl = BuildConfig.SUPABASE_URL.trim()
        val rawKey = BuildConfig.SUPABASE_ANON_KEY.trim()

        val validUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
            rawUrl
        } else {
            "https://placeholder.supabase.co"
        }
        val validKey = rawKey.ifEmpty { "placeholder-anon-key" }

        runCatching {
            createSupabaseClient(
                supabaseUrl = validUrl,
                supabaseKey = validKey,
            ) {
                install(Auth) {
                    autoLoadFromStorage = true
                    alwaysAutoRefresh = true
                }
                install(Postgrest)
                install(Storage)
                install(Functions)
            }
        }.getOrElse {
            createSupabaseClient(
                supabaseUrl = "https://placeholder.supabase.co",
                supabaseKey = "placeholder-anon-key",
            ) {}
        }
    }

    val auth get() = client.auth
    val postgrest get() = client.postgrest
    val storage get() = client.storage
    val functions get() = client.functions
}
