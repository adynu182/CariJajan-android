package com.carijajan.app.data.repository

import android.content.Context
import com.carijajan.app.data.local.AppDatabase
import com.carijajan.app.data.local.CachedListingEntity
import com.carijajan.app.data.remote.CreateListingRequest
import com.carijajan.app.data.remote.ListingApi
import com.carijajan.app.data.remote.NearbyListingDto
import com.carijajan.app.data.remote.ReportRequest
import com.carijajan.app.data.remote.UpdateListingRequest
import com.carijajan.app.domain.model.Category
import com.carijajan.app.domain.model.Listing
import com.carijajan.app.domain.model.ReportReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class ListingRepository(
    context: Context,
    private val api: ListingApi = ListingApi(),
    private val db: AppDatabase = AppDatabase.getInstance(context)
) {
    private val dao = db.cachedListingDao()

    fun getNearbyListingsFlow(
        lat: Double,
        lng: Double,
        radiusKm: Float,
        category: Category? = null
    ): Flow<List<Listing>> = flow {
        // Emit from Room cache first if available
        val cachedEntities = runCatching {
            if (category != null) {
                dao.getByCategory(category.name)
            } else {
                dao.getAll()
            }
        }.getOrDefault(emptyList())

        var emittedCache = false
        if (cachedEntities.isNotEmpty()) {
            emit(cachedEntities.map { it.toDomain() })
            emittedCache = true
        }

        // Fetch from Remote API
        val remoteResult = runCatching { api.getNearby(lat, lng, radiusKm, category) }

        remoteResult.onSuccess { remoteDtos ->
            // Emit dulu begitu remote sukses — JANGAN ditunda oleh caching di bawah.
            emit(remoteDtos.map { it.toDomainListing() })

            // Simpan ke cache Room secara best-effort SETELAH data di-emit.
            // PENTING: sebelumnya insert cache ini dilakukan di dalam runCatching
            // YANG SAMA dengan fetch remote-nya, SEBELUM emit() dipanggil sama
            // sekali. Kalau dao.clearStale()/insertAll() gagal (mis. disk penuh,
            // constraint error) — walau api.getNearby() di atas SUKSES dapat data
            // — seluruh blok runCatching ikut dianggap gagal, listing yang sudah
            // sukses diambil dari server ikut terbuang, dan (kalau tidak ada cache
            // lama) UI cuma dapat state Error padahal datanya sebenarnya sudah ada.
            // Sekarang caching gagal pun tidak menghalangi listing yang sudah
            // didapat untuk tetap sampai & ditampilkan di UI.
            runCatching {
                val nowEpoch = Clock.System.now().epochSeconds
                dao.clearStale(nowEpoch - 600) // clear cache > 10 mins
                dao.insertAll(remoteDtos.map { it.toCachedEntity(nowEpoch) })
            }
        }.onFailure { error ->
            // PENTING: sebelumnya kalau panggilan remote gagal (mis. edge function
            // error) DAN tidak ada cache, flow ini selesai tanpa emit() sama sekali.
            // Akibatnya BuyerViewModel.fetchListings() tidak pernah keluar dari state
            // Loading — peta & daftar lapak terlihat "muter terus" tanpa data maupun
            // pesan error. Sekarang errornya dilempar ulang (kalau belum ada cache yang
            // sempat ditampilkan) supaya caller bisa menangkap & menampilkan pesan error.
            if (!emittedCache) {
                throw error
            }
        }
    }

    suspend fun getListingDetail(listingId: String): Listing? {
        val dto = api.getDetail(listingId) ?: return null
        val photos = api.getPhotos(listingId)
        api.incrementViewCount(listingId)

        val primaryPhoto = photos.find { it.isPrimary } ?: photos.firstOrNull()

        return Listing(
            id = dto.id,
            sellerId = dto.sellerId,
            sellerName = "Penjual",
            sellerAvatarUrl = null,
            name = dto.name,
            category = Category.fromSlug(dto.category),
            description = dto.description,
            priceMin = dto.priceMin,
            priceMax = dto.priceMax,
            isOpen = dto.isOpen,
            latitude = primaryPhoto?.latitude ?: 0.0,
            longitude = primaryPhoto?.longitude ?: 0.0,
            distanceKm = null,
            lastPhotoAt = dto.lastPhotoAt,
            primaryPhotoUrl = primaryPhoto?.photoUrl,
            primaryThumbnailUrl = primaryPhoto?.thumbnailUrl,
            avgRating = null,
            reviewCount = 0,
            viewCount = dto.viewCount,
            photos = photos
        )
    }

    suspend fun createListing(sellerId: String, name: String, category: Category, priceMin: Int?, priceMax: Int?, description: String?): String {
        return api.createListing(
            CreateListingRequest(
                sellerId = sellerId,
                name = name,
                category = category.name.lowercase(),
                description = description,
                priceMin = priceMin,
                priceMax = priceMax,
                isOpen = false
            )
        )
    }

    suspend fun updateListing(listingId: String, name: String, category: Category, priceMin: Int?, priceMax: Int?, description: String?) {
        api.updateListing(
            listingId,
            UpdateListingRequest(
                name = name,
                category = category.name.lowercase(),
                description = description,
                priceMin = priceMin,
                priceMax = priceMax
            )
        )
    }

    suspend fun setOpen(listingId: String, isOpen: Boolean) {
        api.setOpen(listingId, isOpen)
    }

    suspend fun reportListing(listingId: String, reason: ReportReason, detail: String?, deviceId: String?) {
        api.reportListing(
            ReportRequest(
                listingId = listingId,
                reason = reason.slug,
                detail = detail,
                reporterDeviceId = deviceId
            )
        )
    }

    private fun CachedListingEntity.toDomain() = Listing(
        id = id,
        sellerId = sellerId,
        sellerName = sellerName,
        sellerAvatarUrl = sellerAvatarUrl,
        name = name,
        category = Category.fromSlug(category),
        description = description,
        priceMin = priceMin,
        priceMax = priceMax,
        isOpen = isOpen,
        latitude = latitude,
        longitude = longitude,
        distanceKm = distanceKm,
        lastPhotoAt = lastPhotoAtEpoch?.let { Instant.fromEpochSeconds(it) },
        primaryPhotoUrl = primaryPhotoUrl,
        primaryThumbnailUrl = primaryThumbnailUrl,
        avgRating = avgRating,
        reviewCount = reviewCount,
        viewCount = viewCount
    )

    private fun NearbyListingDto.toDomainListing() = Listing(
        id = id,
        sellerId = "",
        sellerName = sellerName,
        sellerAvatarUrl = sellerAvatarUrl,
        name = name,
        category = Category.fromSlug(category),
        description = description,
        priceMin = priceMin,
        priceMax = priceMax,
        isOpen = isOpen,
        latitude = latitude,
        longitude = longitude,
        distanceKm = distanceKm,
        lastPhotoAt = lastPhotoAt,
        primaryPhotoUrl = primaryPhotoUrl,
        primaryThumbnailUrl = primaryThumbnailUrl,
        avgRating = avgRating,
        reviewCount = reviewCount,
        viewCount = viewCount
    )

    private fun NearbyListingDto.toCachedEntity(cachedAtEpoch: Long) = CachedListingEntity(
        id = id,
        sellerId = "",
        sellerName = sellerName,
        sellerAvatarUrl = sellerAvatarUrl,
        name = name,
        category = category,
        description = description,
        priceMin = priceMin,
        priceMax = priceMax,
        isOpen = isOpen,
        latitude = latitude,
        longitude = longitude,
        distanceKm = distanceKm,
        lastPhotoAtEpoch = lastPhotoAt?.epochSeconds,
        primaryPhotoUrl = primaryPhotoUrl,
        primaryThumbnailUrl = primaryThumbnailUrl,
        avgRating = avgRating,
        reviewCount = reviewCount,
        viewCount = viewCount,
        cachedAtEpoch = cachedAtEpoch
    )
}
