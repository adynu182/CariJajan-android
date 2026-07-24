package com.carijajan.app.ui.buyer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carijajan.app.data.repository.ListingRepository
import com.carijajan.app.domain.model.Category
import com.carijajan.app.domain.model.Listing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private val _radiusKm = MutableStateFlow(1.0f) // Default 1 km
    val radiusKm: StateFlow<Float> = _radiusKm.asStateFlow()

    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory: StateFlow<Category?> = _selectedCategory.asStateFlow()

    fun updateLocation(lat: Double, lng: Double) {
        _userLat.value = lat
        _userLng.value = lng
        fetchListings()
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
