package com.carijajan.app.data.remote

import com.carijajan.app.domain.model.User
import com.carijajan.app.domain.model.UserRole
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.IDToken
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class UserRecord(
    val id: String,
    val role: String,
    @SerialName("full_name") val fullName: String,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
)

class AuthApi {

    private val client = SupabaseClientProvider.client

    // ── Session state ─────────────────────────────────────────────────────────

    val currentUserFlow: Flow<User?> = client.auth.sessionStatus.map { status ->
        val session = client.auth.currentSessionOrNull() ?: return@map null
        val userId = session.user?.id ?: return@map null
        getUserProfile(userId)
    }

    fun isLoggedIn(): Boolean = client.auth.currentSessionOrNull() != null

    fun getCurrentUserId(): String? = client.auth.currentSessionOrNull()?.user?.id

    private suspend fun getUserProfile(userId: String): User? {
        return runCatching {
            val record = client.postgrest["users"]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<UserRecord>() ?: return null
            record.toDomain()
        }.getOrNull()
    }

    // ── Penjual auth ──────────────────────────────────────────────────────────

    /**
     * Registrasi penjual baru.
     * 1. Buat akun di Supabase Auth
     * 2. Insert record ke tabel public.users dengan role = 'seller'
     */
    suspend fun registerSeller(
        email: String,
        password: String,
        fullName: String,
        phoneNumber: String,
    ) {
        // Step 1: buat akun auth
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }

        // Step 2: tunggu session tersedia lalu insert profil
        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: throw IllegalStateException("Registrasi berhasil tapi session tidak ditemukan")

        client.postgrest["users"].insert(
            buildJsonObject {
                put("id", userId)
                put("role", "seller")
                put("full_name", fullName)
                put("phone_number", phoneNumber)
            }
        )
    }

    /** Login penjual dengan email + password */
    suspend fun loginSeller(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    // ── Pembeli auth (opsional — hanya untuk rating & favorit) ───────────────

    /**
     * Registrasi pembeli baru via email.
     */
    suspend fun registerBuyer(email: String, password: String, fullName: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }

        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: throw IllegalStateException("Registrasi berhasil tapi session tidak ditemukan")

        client.postgrest["users"].insert(
            buildJsonObject {
                put("id", userId)
                put("role", "buyer")
                put("full_name", fullName)
            }
        )
    }

    /**
     * Login pembeli via Google OAuth.
     * Caller harus menyediakan context Activity untuk launch OAuth flow.
     * Di Compose, gunakan rememberLauncherForActivityResult untuk handle result.
     */
    suspend fun loginWithGoogle(idToken: String, rawNonce: String? = null) {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            this.provider = Google
            if (rawNonce != null) this.nonce = rawNonce
        }

        // Pastikan user tercatat di tabel public.users
        val userId = client.auth.currentSessionOrNull()?.user?.id ?: return
        val existing = runCatching {
            client.postgrest["users"]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<UserRecord>()
        }.getOrNull()

        if (existing == null) {
            val googleUser = client.auth.currentSessionOrNull()?.user
            client.postgrest["users"].insert(
                buildJsonObject {
                    put("id", userId)
                    put("role", "buyer")
                    put("full_name", googleUser?.userMetadata?.get("full_name")?.toString()?.trim('"') ?: "Pembeli")
                }
            )
        }
    }

    /** Login pembeli dengan email + password */
    suspend fun loginBuyer(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /** Logout (berlaku untuk semua role) */
    suspend fun logout() {
        client.auth.signOut()
    }

    /** Update profil user yang sedang login */
    suspend fun updateProfile(fullName: String? = null, phoneNumber: String? = null, avatarUrl: String? = null) {
        val userId = getCurrentUserId() ?: return
        val update = buildJsonObject {
            if (fullName != null) put("full_name", fullName)
            if (phoneNumber != null) put("phone_number", phoneNumber)
            if (avatarUrl != null) put("avatar_url", avatarUrl)
        }
        client.postgrest["users"].update(update) { filter { eq("id", userId) } }
    }

    private fun UserRecord.toDomain() = User(
        id = id,
        role = if (role == "seller") UserRole.SELLER else UserRole.BUYER,
        fullName = fullName,
        phoneNumber = phoneNumber,
        avatarUrl = avatarUrl,
        isVerified = isVerified,
    )
}
