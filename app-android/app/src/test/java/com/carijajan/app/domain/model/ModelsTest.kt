package com.carijajan.app.domain.model

import kotlinx.datetime.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelsTest {

    @Test
    fun category_fromSlug_returnsCorrectCategory() {
        assertEquals(Category.CILOK, Category.fromSlug("cilok"))
        assertEquals(Category.BATAGOR, Category.fromSlug("BATAGOR"))
        assertEquals(Category.LAINNYA, Category.fromSlug("unknown_category"))
    }

    @Test
    fun listing_priceLabel_formatsCorrectly() {
        val listingWithRange = createDummyListing(priceMin = 5000, priceMax = 15000)
        assertEquals("Rp 5.000 – Rp 15.000", listingWithRange.priceLabel)

        val listingSinglePrice = createDummyListing(priceMin = 10000, priceMax = 10000)
        assertEquals("Rp 10.000", listingSinglePrice.priceLabel)

        val listingNoPrice = createDummyListing(priceMin = null, priceMax = null)
        assertEquals("–", listingNoPrice.priceLabel)
    }

    @Test
    fun listing_distanceLabel_formatsCorrectly() {
        val listingNear = createDummyListing(distanceKm = 0.45)
        assertEquals("450 m", listingNear.distanceLabel)

        val listingFar = createDummyListing(distanceKm = 2.34)
        assertEquals("2.3 km", listingFar.distanceLabel)

        val listingUnknownDist = createDummyListing(distanceKm = null)
        assertEquals("", listingUnknownDist.distanceLabel)
    }

    private fun createDummyListing(
        priceMin: Int? = null,
        priceMax: Int? = null,
        distanceKm: Double? = null,
    ) = Listing(
        id = "test-1",
        sellerId = "seller-1",
        sellerName = "Mang Ujang",
        sellerAvatarUrl = null,
        name = "Cilok Kuah",
        category = Category.CILOK,
        description = "Enak gurih",
        priceMin = priceMin,
        priceMax = priceMax,
        isOpen = true,
        latitude = -6.200000,
        longitude = 106.816666,
        distanceKm = distanceKm,
        lastPhotoAt = Clock.System.now(),
        primaryPhotoUrl = null,
        primaryThumbnailUrl = null,
        avgRating = 4.8f,
        reviewCount = 12,
        viewCount = 105,
    )
}
