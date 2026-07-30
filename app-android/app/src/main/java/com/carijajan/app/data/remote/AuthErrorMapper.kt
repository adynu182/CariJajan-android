package com.carijajan.app.data.remote

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.exceptions.RestException

/**
 * Mengubah exception dari Supabase menjadi pesan singkat berbahasa Indonesia yang
 * aman ditampilkan ke pengguna.
 *
 * PENTING — jangan pernah tampilkan `throwable.message` / `localizedMessage` dari
 * sebuah [RestException] langsung ke UI. Pesan bawaannya menyertakan dump request
 * mentah (URL, seluruh header termasuk Authorization Bearer token & apikey, dan
 * HTTP method) — ini persis yang menyebabkan bug "invalid_credentials (...)" yang
 * menampilkan token & header ke pengguna. Gunakan properti [RestException.error] /
 * [AuthRestException.errorCode] yang lebih terstruktur, seperti di bawah ini.
 */
fun Throwable.toFriendlyAuthMessage(): String = when (this) {
    is AuthRestException -> when (errorCode) {
        AuthErrorCode.InvalidCredentials -> "Email atau password salah."
        AuthErrorCode.EmailNotConfirmed -> "Email belum dikonfirmasi. Silakan cek inbox / folder spam Anda."
        AuthErrorCode.UserAlreadyExists,
        AuthErrorCode.EmailExists -> "Email ini sudah terdaftar. Silakan masuk."
        AuthErrorCode.WeakPassword -> "Password terlalu lemah. Gunakan minimal 6 karakter."
        AuthErrorCode.OverEmailSendRateLimit,
        AuthErrorCode.OverRequestRateLimit -> "Terlalu banyak percobaan. Silakan coba lagi beberapa saat lagi."
        AuthErrorCode.UserBanned -> "Akun ini telah diblokir."
        AuthErrorCode.SignupDisabled -> "Pendaftaran akun baru sedang dinonaktifkan."
        else -> "Autentikasi gagal (${errorCode?.value ?: error})."
    }
    is RestException -> "Terjadi kesalahan pada server (${error})."
    else -> message?.takeIf { it.isNotBlank() } ?: "Terjadi kesalahan. Silakan coba lagi."
}
