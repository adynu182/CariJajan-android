package com.carijajan.app.data.remote

import com.carijajan.app.domain.model.User
import com.carijajan.app.domain.model.UserRole
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.IDToken
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

/**
 * Hasil dari proses registrasi.
 *
 * Supabase Auth (secara default) mewajibkan konfirmasi email sebelum sebuah
 * session diberikan. Artinya `signUpWith(Email)` seringkali TIDAK langsung
 * menghasilkan session — ini bukan kondisi error, melainkan alur normal yang
 * harus ditangani secara eksplisit oleh caller (lihat [ConfirmationRequired]).
 */
enum class RegisterOutcome {
    /** Session langsung tersedia (auto-confirm aktif) & profil sudah dibuat. */
    SIGNED_IN,

    /** Registrasi di Supabase Auth berhasil, tapi user harus konfirmasi email dulu. */
    CONFIRMATION_REQUIRED,
}

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

    /**
     * Insert baris profil di public.users jika belum ada (idempotent).
     * Dipisah dari alur signUp karena signUp tidak selalu punya session aktif
     * (lihat catatan di [registerSeller]), jadi insert profil juga dicoba lagi
     * saat login pertama kali via [ensureProfileFromSessionMetadata].
     */
    private suspend fun ensureProfileRow(
        userId: String,
        role: String,
        fullName: String,
        phoneNumber: String? = null,
    ) {
        val existing = runCatching {
            client.postgrest["users"]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<UserRecord>()
        }.getOrNull()
        if (existing != null) return

        client.postgrest["users"].insert(
            buildJsonObject {
                put("id", userId)
                put("role", role)
                put("full_name", fullName)
                if (phoneNumber != null) put("phone_number", phoneNumber)
            }
        )
    }

    /**
     * Setelah login berhasil, pastikan profil di public.users ada.
     * Ini menutup celah kasus registrasi yang sempat "menggantung" karena
     * email belum dikonfirmasi saat signUp (session belum ada waktu itu,
     * jadi insert profil ditunda sampai user benar-benar berhasil login).
     * Data (nama, no. HP, role) diambil dari user_metadata yang disimpan
     * saat signUp lewat parameter `data`.
     */
    private suspend fun ensureProfileFromSessionMetadata(defaultRole: String) {
        val user = client.auth.currentSessionOrNull()?.user ?: return
        val metadata = user.userMetadata
        val fullName = metadata?.get("full_name")?.toString()?.trim('"') ?: "Pengguna"
        val phoneNumber = metadata?.get("phone_number")?.toString()?.trim('"')
        val role = metadata?.get("role")?.toString()?.trim('"') ?: defaultRole
        ensureProfileRow(user.id, role = role, fullName = fullName, phoneNumber = phoneNumber)
    }

    // ── Penjual auth ──────────────────────────────────────────────────────────

    /**
     * Registrasi penjual baru.
     *
     * CATATAN PENTING: jika project Supabase mewajibkan konfirmasi email
     * (pengaturan default), `signUpWith(Email)` TIDAK akan langsung memberi
     * session — user harus klik link konfirmasi di emailnya dulu. Ini bukan
     * error, jadi kita tidak boleh melempar exception di sini seperti versi
     * sebelumnya. Nama & no. HP disimpan sebagai user_metadata saat signUp,
     * lalu baris profil di public.users baru benar-benar dibuat saat user
     * berhasil login pertama kali (lihat [loginSeller]).
     */
    suspend fun registerSeller(
        email: String,
        password: String,
        fullName: String,
        phoneNumber: String,
    ): RegisterOutcome {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject {
                put("full_name", fullName)
                put("phone_number", phoneNumber)
                put("role", "seller")
            }
        }

        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: return RegisterOutcome.CONFIRMATION_REQUIRED

        ensureProfileRow(userId, role = "seller", fullName = fullName, phoneNumber = phoneNumber)
        return RegisterOutcome.SIGNED_IN
    }

    /** Login penjual dengan email + password */
    suspend fun loginSeller(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        ensureProfileFromSessionMetadata(defaultRole = "seller")
    }

    // ── Pembeli auth (opsional — hanya untuk rating & favorit) ───────────────

    /**
     * Registrasi pembeli baru via email.
     * Lihat catatan konfirmasi email di [registerSeller] — logikanya sama.
     */
    suspend fun registerBuyer(email: String, password: String, fullName: String): RegisterOutcome {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = buildJsonObject {
                put("full_name", fullName)
                put("role", "buyer")
            }
        }

        val userId = client.auth.currentSessionOrNull()?.user?.id
            ?: return RegisterOutcome.CONFIRMATION_REQUIRED

        ensureProfileRow(userId, role = "buyer", fullName = fullName)
        return RegisterOutcome.SIGNED_IN
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
        ensureProfileFromSessionMetadata(defaultRole = "buyer")
    }

    /** Login pembeli dengan email + password */
    suspend fun loginBuyer(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        ensureProfileFromSessionMetadata(defaultRole = "buyer")
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
