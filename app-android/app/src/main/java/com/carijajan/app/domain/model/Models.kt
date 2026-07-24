package com.carijajan.app.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ─────────────────────────────────────────────────────────────────────────────
// Kategori dagangan
// ─────────────────────────────────────────────────────────────────────────────

enum class Category(val displayName: String, val emoji: String) {
    CILOK("Cilok", "🍡"),
    BATAGOR("Batagor", "🥟"),
    SIOMAY("Siomay", "🥢"),
    GORENGAN("Gorengan", "🍟"),
    MINUMAN("Minuman", "🥤"),
    MAKANAN_BERAT("Makanan Berat", "🍱"),
    DESSERT("Dessert", "🍨"),
    LAINNYA("Lainnya", "🛒");

    companion object {
        fun fromSlug(slug: String): Category =
            entries.firstOrNull { it.name.lowercase() == slug.lowercase() } ?: LAINNYA
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Foto lapak
// ─────────────────────────────────────────────────────────────────────────────

@Serializable
data class ListingPhoto(
    val id: String,
    @SerialName("listing_id") val listingId: String,
    @SerialName("photo_url") val photoUrl: String,
    @SerialName("thumbnail_url") val thumbnailUrl: String?,
    val latitude: Double,
    val longitude: Double,
    @SerialName("gps_accuracy_m") val gpsAccuracyM: Float?,
    @SerialName("captured_at") val capturedAt: Instant,
    @SerialName("is_primary") val isPrimary: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// Lapak (listing) — digunakan untuk tampil di peta & list pembeli
// ─────────────────────────────────────────────────────────────────────────────

data class Listing(
    val id: String,
    val sellerId: String,
    val sellerName: String,
    val sellerAvatarUrl: String?,
    val name: String,
    val category: Category,
    val description: String?,
    val priceMin: Int?,
    val priceMax: Int?,
    val isOpen: Boolean,
    val latitude: Double,
    val longitude: Double,
    val distanceKm: Double?,          // null jika posisi pembeli belum diketahui
    val lastPhotoAt: Instant?,
    val primaryPhotoUrl: String?,
    val primaryThumbnailUrl: String?,
    val avgRating: Float?,
    val reviewCount: Int,
    val viewCount: Int,
    val photos: List<ListingPhoto> = emptyList(), // diisi di halaman detail
) {
    /** Harga dalam format "Rp X.000 – Rp Y.000" atau "Rp X.000" atau "–" */
    val priceLabel: String
        get() = when {
            priceMin != null && priceMax != null && priceMin != priceMax ->
                "Rp ${priceMin.formatRupiah()} – Rp ${priceMax.formatRupiah()}"
            priceMin != null -> "Rp ${priceMin.formatRupiah()}"
            priceMax != null -> "Rp ${priceMax.formatRupiah()}"
            else -> "–"
        }

    /** Format jarak "500 m" atau "1,2 km" */
    val distanceLabel: String
        get() = when {
            distanceKm == null -> ""
            distanceKm < 1.0 -> "${(distanceKm * 1000).toInt()} m"
            else -> "${"%.1f".format(distanceKm)} km"
        }
}

private fun Int.formatRupiah(): String =
    "%,d".format(this).replace(",", ".")

// ─────────────────────────────────────────────────────────────────────────────
// User (penjual atau pembeli)
// ─────────────────────────────────────────────────────────────────────────────

enum class UserRole { SELLER, BUYER }

data class User(
    val id: String,
    val role: UserRole,
    val fullName: String,
    val phoneNumber: String?,
    val avatarUrl: String?,
    val isVerified: Boolean,
)

// ─────────────────────────────────────────────────────────────────────────────
// Hasil GPS capture (dipakai di UploadPhotoUseCase)
// ─────────────────────────────────────────────────────────────────────────────

data class GpsCapture(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAt: Instant,
)

// ─────────────────────────────────────────────────────────────────────────────
// Filter pencarian pembeli
// ─────────────────────────────────────────────────────────────────────────────

data class ListingFilter(
    val radiusKm: Float = 1f,          // default 1 km
    val category: Category? = null,
)

// ─────────────────────────────────────────────────────────────────────────────
// Review / ulasan
// ─────────────────────────────────────────────────────────────────────────────

data class Review(
    val id: String,
    val listingId: String,
    val reviewerId: String,
    val reviewerName: String,
    val rating: Int,
    val comment: String?,
    val createdAt: Instant,
)

// ─────────────────────────────────────────────────────────────────────────────
// Alasan laporan
// ─────────────────────────────────────────────────────────────────────────────

enum class ReportReason(val displayName: String, val slug: String) {
    LOKASI_SALAH("Lokasi salah / tidak akurat", "lokasi_salah"),
    KONTEN_TIDAK_PANTAS("Konten tidak pantas", "konten_tidak_pantas"),
    LAPAK_PALSU("Lapak palsu / spam", "lapak_palsu"),
    SUDAH_TUTUP("Lapak sudah tutup tapi masih tayang", "sudah_tutup"),
    LAINNYA("Lainnya", "lainnya"),
}
