package com.carijajan.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase

// ─────────────────────────────────────────────────────────────────────────────
// Entity — cache lapak terdekat terakhir
// ─────────────────────────────────────────────────────────────────────────────

@Entity(tableName = "cached_listings")
data class CachedListingEntity(
    @PrimaryKey val id: String,
    val sellerId: String,
    val sellerName: String,
    val sellerAvatarUrl: String?,
    val name: String,
    val category: String,
    val description: String?,
    val priceMin: Int?,
    val priceMax: Int?,
    val isOpen: Boolean,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double?,
    val lastPhotoAtEpoch: Long?,   // Instant.epochSeconds
    val primaryPhotoUrl: String?,
    val primaryThumbnailUrl: String?,
    val avgRating: Float?,
    val reviewCount: Int,
    val viewCount: Int,
    val cachedAtEpoch: Long,        // kapan cache ini disimpan
)

// ─────────────────────────────────────────────────────────────────────────────
// DAO
// ─────────────────────────────────────────────────────────────────────────────

@androidx.room.Dao
interface CachedListingDao {

    @androidx.room.Query("SELECT * FROM cached_listings ORDER BY distanceKm ASC")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<CachedListingEntity>>

    @androidx.room.Query("SELECT * FROM cached_listings ORDER BY distanceKm ASC")
    suspend fun getAll(): List<CachedListingEntity>

    @androidx.room.Query("SELECT * FROM cached_listings WHERE category = :category ORDER BY distanceKm ASC")
    fun getByCategoryFlow(category: String): kotlinx.coroutines.flow.Flow<List<CachedListingEntity>>

    @androidx.room.Query("SELECT * FROM cached_listings WHERE category = :category ORDER BY distanceKm ASC")
    suspend fun getByCategory(category: String): List<CachedListingEntity>

    @androidx.room.Query("SELECT * FROM cached_listings WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CachedListingEntity?

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(listings: List<CachedListingEntity>)

    @androidx.room.Query("DELETE FROM cached_listings")
    suspend fun clearAll()

    /** Hapus cache yang sudah lebih dari 10 menit (600 detik) */
    @androidx.room.Query(
        "DELETE FROM cached_listings WHERE cachedAtEpoch < :cutoffEpoch"
    )
    suspend fun clearStale(cutoffEpoch: Long)
}

// ─────────────────────────────────────────────────────────────────────────────
// Database
// ─────────────────────────────────────────────────────────────────────────────

@Database(
    entities = [CachedListingEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cachedListingDao(): CachedListingDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "carijajan.db",
                ).fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
