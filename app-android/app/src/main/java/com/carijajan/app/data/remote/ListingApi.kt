package com.carijajan.app.data.remote

import com.carijajan.app.domain.model.Category
import com.carijajan.app.domain.model.Listing
import com.carijajan.app.domain.model.ReportReason
import com.carijajan.app.domain.model.Review
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.ktor.client.statement.bodyAsText
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

// ─────────────────────────────────────────────────────────────────────────────
// DTOs (Data Transfer Objects) — struktur response dari Supabase
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class NearbyListingDto(
    val id: String,
    val name: String,
    val category: String,
    val description: String? = null,
    @SerialName("price_min") val priceMin: Int? = null,
    @SerialName("price_max") val priceMax: Int? = null,
    @SerialName("is_open") val isOpen: Boolean,
    @SerialName("last_photo_at") val lastPhotoAt: Instant? = null,
    @SerialName("view_count") val viewCount: Int = 0,
    val latitude: Double,
    val longitude: Double,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("primary_photo_url") val primaryPhotoUrl: String? = null,
    @SerialName("primary_thumbnail_url") val primaryThumbnailUrl: String? = null,
    @SerialName("seller_name") val sellerName: String,
    @SerialName("seller_avatar_url") val sellerAvatarUrl: String? = null,
    @SerialName("avg_rating") val avgRating: Float? = null,
    @SerialName("review_count") val reviewCount: Int = 0,
)

@Serializable
data class ListingDetailDto(
    val id: String,
    @SerialName("seller_id") val sellerId: String,
    val name: String,
    val category: String,
    val description: String? = null,
    @SerialName("price_min") val priceMin: Int? = null,
    @SerialName("price_max") val priceMax: Int? = null,
    @SerialName("is_open") val isOpen: Boolean,
    @SerialName("last_photo_at") val lastPhotoAt: Instant? = null,
    @SerialName("view_count") val viewCount: Int = 0,
    @SerialName("current_location") val currentLocation: String? = null, // WKT "POINT(lng lat)"
)

@Serializable
data class CreateListingRequest(
    @SerialName("seller_id") val sellerId: String,
    val name: String,
    val category: String,
    val description: String? = null,
    @SerialName("price_min") val priceMin: Int? = null,
    @SerialName("price_max") val priceMax: Int? = null,
    @SerialName("is_open") val isOpen: Boolean = false,
)

@Serializable
data class UpdateListingRequest(
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    @SerialName("price_min") val priceMin: Int? = null,
    @SerialName("price_max") val priceMax: Int? = null,
)

@Serializable
data class InsertPhotoRequest(
    @SerialName("listing_id") val listingId: String,
    @SerialName("photo_url") val photoUrl: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    val latitude: Double,
    val longitude: Double,
    @SerialName("gps_accuracy_m") val gpsAccuracyM: Float? = null,
    @SerialName("captured_at") val capturedAt: Instant,
    @SerialName("is_primary") val isPrimary: Boolean = true,
)

@Serializable
data class ReportRequest(
    @SerialName("listing_id") val listingId: String,
    val reason: String,
    val detail: String? = null,
    @SerialName("reporter_device_id") val reporterDeviceId: String? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// ListingApi — satu-satunya sumber data remote untuk listings
// ─────────────────────────────────────────────────────────────────────────────

class ListingApi {

    private val client = SupabaseClientProvider.client

    /** Ambil lapak terdekat via Edge Function /nearby */
    suspend fun getNearby(
        lat: Double,
        lng: Double,
        radiusKm: Float,
        category: Category? = null,
        page: Int = 1,
    ): List<NearbyListingDto> {
        val params = buildJsonObject {
            put("lat", lat)
            put("lng", lng)
            put("radius_km", radiusKm)
            put("page", page)
            if (category != null) put("category", category.name.lowercase())
        }

        val response = client.functions.invoke("nearby", body = params)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val dataArray = json["data"]?.jsonArray ?: return emptyList()
        return Json.decodeFromJsonElement(dataArray)
    }

    /** Ambil detail satu lapak (termasuk foto & ulasan) */
    suspend fun getDetail(listingId: String): ListingDetailDto? {
        return client.postgrest["listings"]
            .select(Columns.raw("*")) {
                filter { eq("id", listingId) }
                limit(1)
            }
            .decodeSingleOrNull<ListingDetailDto>()
    }

    /** Buat lapak baru untuk penjual yang sudah login */
    suspend fun createListing(request: CreateListingRequest): String {
        val result = client.postgrest["listings"]
            .insert(request) { select(Columns.raw("id")) }
            .decodeSingle<JsonObject>()
        return result["id"]!!.jsonPrimitive.content
    }

    /** Update info lapak */
    suspend fun updateListing(listingId: String, request: UpdateListingRequest) {
        client.postgrest["listings"]
            .update(request) { filter { eq("id", listingId) } }
    }

    /** Toggle status buka/tutup */
    suspend fun setOpen(listingId: String, isOpen: Boolean) {
        client.postgrest["listings"]
            .update(buildJsonObject { put("is_open", isOpen) }) {
                filter { eq("id", listingId) }
            }
    }

    /** Insert record foto setelah upload storage berhasil */
    suspend fun insertPhoto(request: InsertPhotoRequest) {
        // Reset primary flag untuk foto lama sebelum insert yang baru
        if (request.isPrimary) {
            client.postgrest["listing_photos"]
                .update(buildJsonObject { put("is_primary", false) }) {
                    filter { eq("listing_id", request.listingId) }
                }
        }
        client.postgrest["listing_photos"].insert(request)
    }

    /** Ambil semua foto milik sebuah lapak */
    suspend fun getPhotos(listingId: String): List<com.carijajan.app.domain.model.ListingPhoto> {
        return client.postgrest["listing_photos"]
            .select {
                filter { eq("listing_id", listingId) }
                order("captured_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList()
    }

    /** Laporkan lapak (tanpa autentikasi) */
    suspend fun reportListing(request: ReportRequest) {
        client.postgrest["reports"].insert(request)
    }

    /** Ambil ulasan sebuah lapak */
    suspend fun getReviews(listingId: String): List<Review> {
        // TODO: join dengan users untuk nama reviewer
        return emptyList() // placeholder — implement saat Fase 2 rating
    }

    /** Increment view count (fire-and-forget, tidak blokir UI) */
    suspend fun incrementViewCount(listingId: String) {
        runCatching {
            client.postgrest.rpc("increment_view_count", buildJsonObject {
                put("listing_uuid", listingId)
            })
        }
    }
}
