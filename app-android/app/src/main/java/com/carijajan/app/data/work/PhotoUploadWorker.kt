package com.carijajan.app.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.carijajan.app.data.remote.InsertPhotoRequest
import com.carijajan.app.data.remote.ListingApi
import com.carijajan.app.data.remote.SupabaseClientProvider
import io.github.jan.supabase.storage.storage
import kotlinx.datetime.Instant
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager Worker untuk upload foto lapak ke Supabase Storage.
 *
 * Fitur:
 * - Retry otomatis (exponential backoff) jika gagal karena jaringan
 * - Hanya berjalan saat ada koneksi internet
 * - Setelah upload storage berhasil, insert record ke listing_photos
 * - Trigger sync_listing_location via DB trigger (otomatis update current_location & is_open)
 */
class PhotoUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_LISTING_ID    = "listing_id"
        const val KEY_LOCAL_PATH    = "local_path"
        const val KEY_LATITUDE      = "latitude"
        const val KEY_LONGITUDE     = "longitude"
        const val KEY_GPS_ACCURACY  = "gps_accuracy_m"
        const val KEY_CAPTURED_AT   = "captured_at_epoch"
        const val KEY_IS_PRIMARY    = "is_primary"

        private const val STORAGE_BUCKET = "listing-photos"

        /**
         * Buat dan enqueue upload request.
         * Panggil dari ViewModel setelah foto berhasil diambil & dikompresi.
         */
        fun enqueue(
            context: Context,
            listingId: String,
            localFilePath: String,
            latitude: Double,
            longitude: Double,
            gpsAccuracyM: Float,
            capturedAtEpoch: Long,
            isPrimary: Boolean = true,
        ) {
            val data = Data.Builder()
                .putString(KEY_LISTING_ID, listingId)
                .putString(KEY_LOCAL_PATH, localFilePath)
                .putDouble(KEY_LATITUDE, latitude)
                .putDouble(KEY_LONGITUDE, longitude)
                .putFloat(KEY_GPS_ACCURACY, gpsAccuracyM)
                .putLong(KEY_CAPTURED_AT, capturedAtEpoch)
                .putBoolean(KEY_IS_PRIMARY, isPrimary)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,   // mulai retry dari 30 detik, max ~18 jam
                )
                .addTag("photo_upload_$listingId")
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun doWork(): Result {
        val listingId    = inputData.getString(KEY_LISTING_ID) ?: return Result.failure()
        val localPath    = inputData.getString(KEY_LOCAL_PATH) ?: return Result.failure()
        val latitude     = inputData.getDouble(KEY_LATITUDE, 0.0)
        val longitude    = inputData.getDouble(KEY_LONGITUDE, 0.0)
        val gpsAccuracy  = inputData.getFloat(KEY_GPS_ACCURACY, 0f)
        val capturedAt   = inputData.getLong(KEY_CAPTURED_AT, 0L)
        val isPrimary    = inputData.getBoolean(KEY_IS_PRIMARY, true)

        val localFile = File(localPath)
        if (!localFile.exists()) {
            // File lokal sudah tidak ada — gagal permanen, jangan retry
            return Result.failure()
        }

        return runCatching {
            val storage = SupabaseClientProvider.client.storage

            // 1. Tentukan path di Storage bucket
            val timestamp = capturedAt
            val storagePath = "listings/$listingId/${timestamp}_${localFile.name}"

            // 2. Upload ke Supabase Storage
            storage[STORAGE_BUCKET].upload(storagePath, localFile.readBytes()) {
                upsert = true
            }

            // 3. Dapatkan public URL
            val photoUrl      = storage[STORAGE_BUCKET].publicUrl(storagePath)
            val thumbnailUrl  = null // TODO: buat thumbnail via Edge Function/transform

            // 4. Insert record ke listing_photos
            // DB trigger sync_listing_location akan otomatis update current_location & is_open
            val api = ListingApi()
            api.insertPhoto(
                InsertPhotoRequest(
                    listingId    = listingId,
                    photoUrl     = photoUrl,
                    thumbnailUrl = thumbnailUrl,
                    latitude     = latitude,
                    longitude    = longitude,
                    gpsAccuracyM = gpsAccuracy,
                    capturedAt   = Instant.fromEpochSeconds(capturedAt),
                    isPrimary    = isPrimary,
                )
            )

            // 5. Hapus file lokal setelah berhasil upload
            localFile.delete()

            Result.success()
        }.getOrElse { error ->
            android.util.Log.e("PhotoUploadWorker", "Upload gagal: ${error.message}", error)
            // Retry (WorkManager akan exponential backoff)
            Result.retry()
        }
    }
}
