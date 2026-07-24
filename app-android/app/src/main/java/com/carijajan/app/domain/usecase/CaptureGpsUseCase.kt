package com.carijajan.app.domain.usecase

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import com.carijajan.app.domain.model.GpsCapture
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Use case untuk mengambil GPS fix berkualitas tinggi saat penjual mengambil foto lapak.
 *
 * Alur:
 * 1. Minta GPS fix HIGH_ACCURACY
 * 2. Validasi akurasi < GPS_ACCURACY_THRESHOLD_M
 * 3. Cek mock location (Location.isMock)
 * 4. Return GpsCapture atau throw GpsException
 */
class CaptureGpsUseCase(private val context: Context) {

    companion object {
        const val GPS_ACCURACY_THRESHOLD_M = 50f   // akurasi minimum dalam meter
        const val GPS_TIMEOUT_MS = 15_000L         // timeout 15 detik
    }

    sealed class GpsException(message: String) : Exception(message) {
        class Timeout : GpsException("GPS tidak dapat ditemukan dalam ${GPS_TIMEOUT_MS / 1000} detik. Pindah ke area terbuka lalu coba lagi.")
        class PoorAccuracy(accuracyM: Float) : GpsException("Akurasi GPS terlalu rendah (${"%.0f".format(accuracyM)} m). Pindah ke area terbuka lalu coba lagi.")
        class MockLocation : GpsException("Terdeteksi aplikasi GPS palsu. Nonaktifkan mock location lalu coba lagi.")
        class PermissionDenied : GpsException("Izin lokasi belum diberikan.")
    }

    @SuppressLint("MissingPermission")
    suspend fun execute(): GpsCapture {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)

        val location: Location = withTimeoutOrNull(GPS_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val request = com.google.android.gms.location.CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(5_000) // tolak lokasi yang lebih dari 5 detik
                    .build()

                val task = fusedClient.getCurrentLocation(request, null)
                task.addOnSuccessListener { loc -> continuation.resume(loc) }
                task.addOnFailureListener { e -> continuation.resumeWithException(e) }

                continuation.invokeOnCancellation {
                    // tidak ada cara cancel task GMS secara eksplisit, cukup abaikan hasilnya
                }
            }
        } ?: throw GpsException.Timeout()

        // Cek mock location (Android API 31+: isMock, sebelumnya: isFromMockProvider)
        val isMock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        if (isMock) throw GpsException.MockLocation()

        // Cek akurasi
        if (location.accuracy > GPS_ACCURACY_THRESHOLD_M) {
            throw GpsException.PoorAccuracy(location.accuracy)
        }

        return GpsCapture(
            latitude     = location.latitude,
            longitude    = location.longitude,
            accuracyMeters = location.accuracy,
            capturedAt   = Clock.System.now(),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Use case: kompres foto sebelum upload
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Kompres foto dari [inputFile] ke JPEG dengan:
 * - Max sisi terpanjang: 1280 px
 * - Target ukuran ≤ 500 KB
 * - Output disimpan ke [outputFile]
 */
fun compressPhoto(inputFile: File, outputFile: File): File {
    val TARGET_MAX_SIDE_PX = 1280
    val TARGET_MAX_SIZE_KB = 500

    val original = BitmapFactory.decodeFile(inputFile.absolutePath)
        ?: throw IllegalArgumentException("Foto tidak dapat dibaca atau rusak: ${inputFile.name}")

    val scaled = if (original.width > TARGET_MAX_SIDE_PX || original.height > TARGET_MAX_SIDE_PX) {
        val ratio = minOf(
            TARGET_MAX_SIDE_PX.toFloat() / original.width,
            TARGET_MAX_SIDE_PX.toFloat() / original.height,
        )
        Bitmap.createScaledBitmap(
            original,
            (original.width * ratio).toInt(),
            (original.height * ratio).toInt(),
            true,
        )
    } else {
        original
    }

    // Binary search untuk kualitas JPEG yang menghasilkan ≤ 500 KB
    var quality = 85
    var bytes: ByteArray
    do {
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bytes = bos.toByteArray()
        quality -= 10
    } while (bytes.size > TARGET_MAX_SIZE_KB * 1024 && quality > 30)

    outputFile.writeBytes(bytes)

    if (scaled != original) scaled.recycle()
    original.recycle()

    return outputFile
}
