package com.carijajan.app.data.repository

import android.content.Context
import com.carijajan.app.data.local.AppDatabase
import com.carijajan.app.data.local.CachedListingEntity
import com.carijajan.app.data.remote.CreateListingRequest
import com.carijajan.app.data.remote.ListingApi
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

        if (cachedEntities.isNotEmpty()) {
            emit(cachedEntities.map { it.toDomain() })
        }

        // Fetch from Remote API
        runCatching {
            val remoteDtos = api.getNearby(lat, lng, radiusKm, category)
            val nowEpoch = Clock.System.now().epochSeconds

            val newEntities = remoteDtos.map { dto ->
                CachedListingEntity(
                    id = dto.id,
                    sellerId = "",
                    sellerName = dto.sellerName,
                    sellerAvatarUrl = dto.sellerAvatarUrl,
                    name = dto.name,
                    category = dto.category,
                    description = dto.description,
                    priceMin = dto.priceMin,
                    priceMax = dto.priceMax,
                    isOpen = dto.isOpen,
                    latitude = dto.latitude,
                    longitude = dto.longitude,
                    distanceKm = dto.distanceKm,
                    lastPhotoAtEpoch = dto.lastPhotoAt?.epochSeconds,
                    primaryPhotoUrl = dto.primaryPhotoUrl,
                    primaryThumbnailUrl = dto.primaryThumbnailUrl,
                    avgRating = dto.avgRating,
                    reviewCount = dto.reviewCount,
                    viewCount = dto.viewCount,
                    cachedAtEpoch = nowEpoch
                )
            }

            dao.clearStale(nowEpoch - 600) // clear cache > 10 mins
            dao.insertAll(newEntities)

            val domainListings = remoteDtos.map { dto ->
                Listing(
                    id = dto.id,
                    sellerId = "",
                    sellerName = dto.sellerName,
                    sellerAvatarUrl = dto.sellerAvatarUrl,
                    name = dto.name,
                    category = Category.fromSlug(dto.category),
                    description = dto.description,
                    priceMin = dto.priceMin,
                    priceMax = dto.priceMax,
                    isOpen = dto.isOpen,
                    latitude = dto.latitude,
                    longitude = dto.longitude,
                    distanceKm = dto.distanceKm,
                    lastPhotoAt = dto.lastPhotoAt,
                    primaryPhotoUrl = dto.primaryPhotoUrl,
                    primaryThumbnailUrl = dto.primaryThumbnailUrl,
                    avgRating = dto.avgRating,
                    reviewCount = dto.reviewCount,
                    viewCount = dto.viewCount
                )
            }

            emit(domainListings)
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
}
