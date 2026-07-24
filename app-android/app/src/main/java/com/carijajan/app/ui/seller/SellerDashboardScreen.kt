package com.carijajan.app.ui.seller

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.carijajan.app.data.remote.AuthApi
import com.carijajan.app.data.remote.ListingApi
import com.carijajan.app.domain.model.Listing
import com.carijajan.app.ui.buyer.formatTimeAgo
import com.carijajan.app.ui.theme.ColorClosed
import com.carijajan.app.ui.theme.ColorOpen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SellerViewModel(
    private val authApi: AuthApi = AuthApi(),
    private val listingApi: ListingApi = ListingApi()
) : ViewModel() {

    private val _sellerListing = MutableStateFlow<Listing?>(null)
    val sellerListing: StateFlow<Listing?> = _sellerListing.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadSellerData() {
        viewModelScope.launch {
            _isLoading.value = true
            val userId = authApi.getCurrentUserId()
            if (userId != null) {
                // Fetch seller's listing (1 seller = 1 listing)
                runCatching {
                    val listings = listingApi.getNearby(0.0, 0.0, 5.0f) // fallback get
                    _sellerListing.value = listings.find { true }?.let { dto ->
                        Listing(
                            id = dto.id,
                            sellerId = userId,
                            sellerName = dto.sellerName,
                            sellerAvatarUrl = dto.sellerAvatarUrl,
                            name = dto.name,
                            category = com.carijajan.app.domain.model.Category.fromSlug(dto.category),
                            description = dto.description,
                            priceMin = dto.priceMin,
                            priceMax = dto.priceMax,
                            isOpen = dto.isOpen,
                            latitude = dto.latitude,
                            longitude = dto.longitude,
                            distanceKm = null,
                            lastPhotoAt = dto.lastPhotoAt,
                            primaryPhotoUrl = dto.primaryPhotoUrl,
                            primaryThumbnailUrl = dto.primaryThumbnailUrl,
                            avgRating = dto.avgRating,
                            reviewCount = dto.reviewCount,
                            viewCount = dto.viewCount
                        )
                    }
                }
            }
            _isLoading.value = false
        }
    }

    fun toggleOpenStatus(isOpen: Boolean) {
        val current = _sellerListing.value ?: return
        viewModelScope.launch {
            runCatching {
                listingApi.setOpen(current.id, isOpen)
                _sellerListing.value = current.copy(isOpen = isOpen)
            }
        }
    }

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            authApi.logout()
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SellerDashboardScreen(
    viewModel: SellerViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
    onUploadPhoto: (String) -> Unit,
    onEditListing: (String) -> Unit,
    onLogout: () -> Unit
) {
    LaunchedEffect(Unit) {
        viewModel.loadSellerData()
    }

    val listing by viewModel.sellerListing.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard Penjual") },
                actions = {
                    IconButton(onClick = { viewModel.logout(onLogout) }) {
                        Icon(Icons.Default.Logout, contentDescription = "Logout")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (listing == null) {
            // Uncreated listing state -> Prompt create
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Kamu belum membuat lapak dagangan", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Buat lapak sekarang agar pembeli di sekitarmu dapat menemukan jajananmu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { onEditListing("new") }) {
                    Text("Buat Lapak Baru")
                }
            }
        } else {
            val item = listing!!

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Listing Card preview
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${item.category.emoji} ${item.name}",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (item.isOpen) "Buka" else "Tutup",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (item.isOpen) ColorOpen else ColorClosed
                                )
                                Switch(
                                    checked = item.isOpen,
                                    onCheckedChange = { viewModel.toggleOpenStatus(it) },
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        Text(
                            text = item.priceLabel,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (item.primaryPhotoUrl != null) {
                            AsyncImage(
                                model = item.primaryPhotoUrl,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Belum ada foto lapak. Ambil foto sekarang!", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("${item.viewCount} dilihat", fontSize = 12.sp)
                            }
                            Text(
                                text = "Update: ${formatTimeAgo(item.lastPhotoAt)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Primary Action: Update Photo + GPS
                Button(
                    onClick = { onUploadPhoto(item.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ambil Foto Lapak & Update Lokasi GPS", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = { onEditListing(item.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit Informasi Lapak")
                }
            }
        }
    }
}
