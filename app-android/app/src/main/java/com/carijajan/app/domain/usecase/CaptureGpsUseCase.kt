package com.carijajan.app.domain.usecase

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import androidx.exifinterface.media.ExifInterface
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

/** Hasil kompresi: file foto utama + file thumbnail, keduanya persegi (1:1). */
data class CompressedPhoto(val fullFile: File, val thumbnailFile: File)

private const val FULL_MAX_SIDE_PX = 1280
private const val FULL_MAX_SIZE_KB = 500
private const val THUMBNAIL_MAX_SIDE_PX = 400
private const val THUMBNAIL_MAX_SIZE_KB = 100

/**
 * Proses foto dari [inputFile] jadi 2 file JPEG persegi (1:1): foto utama
 * ([outputFile], maks 1280x1280 / 500 KB) & thumbnail ([thumbnailFile], maks
 * 400x400 / 100 KB).
 *
 * BUG LAMA (foto selalu landscape): kamera (CameraX) menyimpan pixel JPEG
 * dalam orientasi NATIVE sensor (hampir selalu landscape) dan cuma menandai
 * cara menampilkannya lewat tag EXIF "Orientation" — pixel-nya sendiri TIDAK
 * ikut diputar. Kode lama langsung BitmapFactory.decodeFile() lalu
 * Bitmap.compress() tanpa pernah membaca tag EXIF itu, dan Bitmap.compress()
 * TIDAK menyalin metadata EXIF ke file output — jadi info rotasinya hilang
 * permanen dan hasil akhirnya selalu landscape di manapun dibuka (galeri lain,
 * browser, dst), tidak peduli device dipegang potrait sekalipun.
 *
 * Fix: baca tag EXIF dari file ASLI (sebelum di-decode ulang), putar bitmap-nya
 * secara eksplisit sesuai tag itu, baru setelah itu crop ke tengah jadi persegi
 * dan dikompres — supaya hasil akhirnya selalu tegak & 1:1 apapun orientasi
 * device saat difoto.
 */
fun compressPhoto(inputFile: File, outputFile: File, thumbnailFile: File): CompressedPhoto {
    val orientation = runCatching {
        ExifInterface(inputFile.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val original = BitmapFactory.decodeFile(inputFile.absolutePath)
        ?: throw IllegalArgumentException("Foto tidak dapat dibaca atau rusak: ${inputFile.name}")

    val upright = applyExifRotation(original, orientation)
    val squared = cropToSquare(upright)

    writeJpeg(squared, outputFile, FULL_MAX_SIDE_PX, FULL_MAX_SIZE_KB)
    writeJpeg(squared, thumbnailFile, THUMBNAIL_MAX_SIDE_PX, THUMBNAIL_MAX_SIZE_KB)

    if (upright != original) original.recycle()
    if (squared != upright) upright.recycle()
    squared.recycle()

    return CompressedPhoto(outputFile, thumbnailFile)
}

/** Putar/cerminkan [bitmap] sesuai tag EXIF Orientation. No-op kalau sudah normal. */
private fun applyExifRotation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap // ORIENTATION_NORMAL / UNDEFINED — tidak perlu diputar
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

/** Crop tengah [bitmap] jadi persegi (1:1), sisi terpendeknya jadi patokan. */
private fun cropToSquare(bitmap: Bitmap): Bitmap {
    val size = minOf(bitmap.width, bitmap.height)
    if (bitmap.width == bitmap.height) return bitmap
    val x = (bitmap.width - size) / 2
    val y = (bitmap.height - size) / 2
    return Bitmap.createBitmap(bitmap, x, y, size, size)
}

/** Skalakan (kalau perlu) & kompres [bitmap] persegi ke JPEG ≤ [maxSizeKb], ditulis ke [outputFile]. */
private fun writeJpeg(bitmap: Bitmap, outputFile: File, maxSidePx: Int, maxSizeKb: Int) {
    val scaled = if (bitmap.width > maxSidePx) {
        Bitmap.createScaledBitmap(bitmap, maxSidePx, maxSidePx, true)
    } else {
        bitmap
    }

    // Binary search sederhana (turun 10 tiap percobaan) untuk kualitas JPEG
    // yang menghasilkan ukuran ≤ target.
    var quality = 85
    var bytes: ByteArray
    do {
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bytes = bos.toByteArray()
        quality -= 10
    } while (bytes.size > maxSizeKb * 1024 && quality > 30)

    outputFile.writeBytes(bytes)
    if (scaled != bitmap) scaled.recycle()
}
