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
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth) {
                // Simpan session ke SharedPreferences (auto-refresh token)
                autoLoadFromStorage = true
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(Storage)
            install(Functions)
        }
    }

    val auth get() = client.auth
    val postgrest get() = client.postgrest
    val storage get() = client.storage
    val functions get() = client.functions
}
