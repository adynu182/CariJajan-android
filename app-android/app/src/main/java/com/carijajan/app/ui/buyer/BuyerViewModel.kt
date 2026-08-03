package com.carijajan.app.ui.buyer

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carijajan.app.data.repository.ListingRepository
import com.carijajan.app.domain.model.Category
import com.carijajan.app.domain.model.Listing
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class BuyerUiState {
    object Loading : BuyerUiState()
    data class Success(val listings: List<Listing>) : BuyerUiState()
    data class Error(val message: String) : BuyerUiState()
}

class BuyerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ListingRepository(application)

    private val _uiState = MutableStateFlow<BuyerUiState>(BuyerUiState.Loading)
    val uiState: StateFlow<BuyerUiState> = _uiState.asStateFlow()

    private val _userLat = MutableStateFlow(-6.2088) // Default Jakarta center fallback
    val userLat: StateFlow<Double> = _userLat.asStateFlow()

    private val _userLng = MutableStateFlow(106.8456)
    val userLng: StateFlow<Double> = _userLng.asStateFlow()

    /** True setelah lokasi device asli berhasil didapat (bukan lagi fallback Jakarta). */
    private val _hasRealLocation = MutableStateFlow(false)
    val hasRealLocation: StateFlow<Boolean> = _hasRealLocation.asStateFlow()

    private val _radiusKm = MutableStateFlow(1.0f) // Default 1 km
    val radiusKm: StateFlow<Float> = _radiusKm.asStateFlow()

    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory: StateFlow<Category?> = _selectedCategory.asStateFlow()

    /**
     * Naik setiap kali ada permintaan untuk memindahkan kamera ke lokasi user —
     * sengaja dipisah dari nilai lat/lng itu sendiri.
     *
     * PENTING: MutableStateFlow tidak mengirim update baru kalau value barunya
     * SAMA PERSIS dengan value lama (pakai equals(), mirip distinctUntilChanged()).
     * Tombol "Lokasi Saya" memanggil fetchDeviceLocation() -> updateLocation(),
     * tapi kalau device tidak banyak bergerak (atau fused location provider
     * mengembalikan fix yang di-cache, lihat setMaxUpdateAgeMillis di bawah),
     * lat/lng yang didapat persis sama dengan sebelumnya. Akibatnya userLat/
     * userLng di StateFlow tidak berubah, layar peta tidak recompose, dan
     * LaunchedEffect yang menggerakkan kamera tidak pernah jalan ulang — tombol
     * terlihat seperti tidak berfungsi. Counter ini dipakai sebagai key
     * tambahan di LaunchedEffect supaya kamera tetap dipaksa bergerak ulang
     * setiap kali tombol dipencet, terlepas dari apakah koordinatnya berubah.
     */
    private val _cameraMoveTick = MutableStateFlow(0)
    val cameraMoveTick: StateFlow<Int> = _cameraMoveTick.asStateFlow()

    fun updateLocation(lat: Double, lng: Double) {
        _userLat.value = lat
        _userLng.value = lng
        _hasRealLocation.value = true
        _cameraMoveTick.value += 1
        fetchListings()
    }

    /**
     * Ambil lokasi GPS device saat ini dan panggil [updateLocation].
     *
     * PENTING: sebelumnya tidak ada satupun kode yang memanggil ini atau
     * FusedLocationProviderClient di layar pembeli — userLat/userLng selalu
     * diam di fallback Jakarta di atas, jadi peta & radius pencarian tidak
     * pernah memakai lokasi asli pengguna. Ini penyebab "lokasi saya tidak
     * muncul di peta".
     *
     * Aman dipanggil berkali-kali (mis. tiap kali izin lokasi baru diberikan);
     * cukup no-op kalau izin belum ada, tidak melempar exception.
     */
    fun fetchDeviceLocation() {
        val context = getApplication<Application>()
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) return

        viewModelScope.launch {
            runCatching { requestCurrentLocation(context) }
                .onSuccess { location ->
                    if (location != null) {
                        updateLocation(location.latitude, location.longitude)
                    }
                }
            // Kegagalan (GPS mati, timeout, dsb) sengaja dibiarkan diam — peta
            // tetap jalan dengan fallback Jakarta, tidak perlu memblokir UI.
        }
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun requestCurrentLocation(context: Application): Location? {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(60_000) // boleh pakai fix yang agak lama, cukup untuk browsing peta
            .build()

        return suspendCancellableCoroutine { continuation ->
            val task = fusedClient.getCurrentLocation(request, null)
            task.addOnSuccessListener { location -> continuation.resume(location) }
            task.addOnFailureListener { e -> continuation.resumeWithException(e) }
        }
    }

    fun updateRadius(radius: Float) {
        _radiusKm.value = radius
        fetchListings()
    }

    fun selectCategory(category: Category?) {
        _selectedCategory.value = if (_selectedCategory.value == category) null else category
        fetchListings()
    }

    fun fetchListings() {
        viewModelScope.launch {
            _uiState.value = BuyerUiState.Loading
            runCatching {
                repository.getNearbyListingsFlow(
                    lat = _userLat.value,
                    lng = _userLng.value,
                    radiusKm = _radiusKm.value,
                    category = _selectedCategory.value
                ).collect { listings ->
                    _uiState.value = BuyerUiState.Success(listings)
                }
            }.onFailure { error ->
                _uiState.value = BuyerUiState.Error(error.localizedMessage ?: "Gagal memuat lapak")
            }
        }
    }
}
