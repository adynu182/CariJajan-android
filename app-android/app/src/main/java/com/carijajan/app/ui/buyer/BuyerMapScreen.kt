package com.carijajan.app.ui.buyer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.carijajan.app.BuildConfig
import com.carijajan.app.domain.model.Category
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Aktifkan MapLibre LocationComponent (titik biru "lokasi saya") di atas style
 * yang sudah selesai dimuat. Dipanggil hanya setelah izin lokasi dipastikan ada
 * (lihat pemanggilnya) — makanya @SuppressLint di sini aman.
 */
@SuppressLint("MissingPermission")
private fun enableUserLocationDot(map: MapLibreMap, style: Style, context: android.content.Context) {
    val activationOptions = LocationComponentActivationOptions.builder(context, style).build()
    with(map.locationComponent) {
        activateLocationComponent(activationOptions)
        isLocationComponentEnabled = true
        cameraMode = CameraMode.NONE
        renderMode = RenderMode.COMPASS
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuyerMapScreen(
    viewModel: BuyerViewModel,
    onNavigateToList: () -> Unit,
    onSelectListing: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val userLat by viewModel.userLat.collectAsState()
    val userLng by viewModel.userLng.collectAsState()
    val cameraMoveTick by viewModel.cameraMoveTick.collectAsState()
    val hasRealLocation by viewModel.hasRealLocation.collectAsState()
    val radiusKm by viewModel.radiusKm.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasLocationPermission = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    DisposableEffect(lifecycleOwner, mapViewInstance) {
        val mapView = mapViewInstance ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val mapTilerKey = BuildConfig.MAPTILER_API_KEY.trim()
    val styleUrl = if (mapTilerKey.isNotBlank() && mapTilerKey != "YOUR_MAPTILER_API_KEY") {
        "https://api.maptiler.com/maps/streets-v2/style.json?key=$mapTilerKey"
    } else {
        "https://demotiles.maplibre.org/style.json"
    }

    LaunchedEffect(Unit) {
        viewModel.fetchListings()
    }

    // Minta izin lokasi kalau belum ada, lalu ambil lokasi device asli begitu
    // izinnya tersedia (baik dari awal, atau setelah user baru saja menyetujui
    // dialog izin). Sebelumnya tidak ada kode ini sama sekali.
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            viewModel.fetchDeviceLocation()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    // Pindahkan kamera setiap kali lokasi user berubah (baik saat map pertama kali
    // siap, maupun setelah GPS asli berhasil didapat menyusul fallback Jakarta).
    // SEBELUMNYA posisi kamera cuma di-set SEKALI di dalam factory{} saat MapView
    // dibuat — pakai userLat/userLng yang saat itu MASIH fallback Jakarta, karena
    // fetchDeviceLocation() belum selesai. Makanya peta tidak pernah pindah ke
    // lokasi asli walau GPS-nya berhasil didapat beberapa saat kemudian.
    // cameraMoveTick sengaja ikut jadi key: StateFlow userLat/userLng tidak akan
    // ganti value (makanya efek ini tidak jalan ulang) kalau lokasi baru yang
    // didapat sama persis dengan yang lama — padahal tombol "Lokasi Saya" harus
    // tetap memindahkan kamera setiap kali dipencet meski koordinatnya tidak
    // berubah. Lihat komentar di BuyerViewModel.cameraMoveTick.
    LaunchedEffect(userLat, userLng, cameraMoveTick, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(userLat, userLng))
                    .zoom(14.0)
                    .build()
            )
        )
    }

    // Aktifkan titik "lokasi saya" di peta begitu style selesai dimuat DAN izin ada.
    LaunchedEffect(hasLocationPermission, loadedStyle) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val style = loadedStyle ?: return@LaunchedEffect
        if (!hasLocationPermission) return@LaunchedEffect
        runCatching { enableUserLocationDot(map, style, context) }
    }

    // Update markers when listings change.
    // PENTING: loadedStyle wajib jadi key & guard di sini. mapLibreMap sudah
    // ter-set begitu getMapAsync{} callback jalan, TAPI style-nya (setStyle())
    // baru selesai dimuat belakangan secara async (fetch network). Kalau
    // listing (uiState = Success) datang duluan sebelum style siap — yang
    // gampang terjadi karena cache Room di ListingRepository bisa emit hampir
    // instan — map.addMarker() dipanggil sebelum style ready, gagal diam-diam
    // (ketutup runCatching), dan efek ini TIDAK PERNAH jalan ulang karena
    // loadedStyle sebelumnya bukan bagian dari key-nya. Hasilnya: vendor/toko
    // tidak pernah muncul di peta walau datanya sudah berhasil di-fetch (dan
    // kelihatan normal di BuyerListScreen, yang tidak bergantung pada style
    // peta sama sekali). Pola guard loadedStyle ini sama seperti yang dipakai
    // enableUserLocationDot() di atas.
    LaunchedEffect(uiState, mapLibreMap, loadedStyle) {
        val map = mapLibreMap ?: return@LaunchedEffect
        loadedStyle ?: return@LaunchedEffect
        runCatching {
            map.clear()

            if (uiState is BuyerUiState.Success) {
                val listings = (uiState as BuyerUiState.Success).listings
                listings.forEach { listing ->
                    val markerOptions = MarkerOptions()
                        .position(LatLng(listing.latitude, listing.longitude))
                        .title("${listing.category.emoji} ${listing.name}")
                        .snippet(listing.priceLabel)

                    map.addMarker(markerOptions)
                }
                map.setOnMarkerClickListener { marker ->
                    val clicked = listings.find {
                        it.name == marker.title?.removePrefix("${it.category.emoji} ")
                    }
                    clicked?.let { onSelectListing(it.id) }
                    true
                }
            }
        }.onFailure { error ->
            android.util.Log.e("BuyerMapScreen", "Gagal menampilkan marker vendor di peta", error)
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(
                    onClick = {
                        if (hasLocationPermission) {
                            viewModel.fetchDeviceLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Lokasi Saya")
                }

                ExtendedFloatingActionButton(
                    onClick = onNavigateToList,
                    icon = { Icon(Icons.Default.FormatListBulleted, contentDescription = null) },
                    text = { Text("Daftar Lapak") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Map View
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).apply {
                        onCreate(Bundle())
                        mapViewInstance = this
                        getMapAsync { map ->
                            mapLibreMap = map
                            runCatching {
                                map.setStyle(styleUrl) { style ->
                                    loadedStyle = style
                                }
                            }
                        }
                    }
                }
            )

            // Top Filter Panel (Category chips & Radius Slider)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Radius: ${"%.1f".format(radiusKm)} km",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = radiusKm,
                        onValueChange = { viewModel.updateRadius(it) },
                        valueRange = 1.0f..5.0f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = { viewModel.selectCategory(null) },
                            label = { Text("Semua 🛒") }
                        )
                        Category.entries.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat,
                                onClick = { viewModel.selectCategory(cat) },
                                label = { Text("${cat.displayName} ${cat.emoji}") }
                            )
                        }
                    }
                }
            }

            // Loading / Error / Permission hint / Empty overlay
            if (uiState is BuyerUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState is BuyerUiState.Error) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = 80.dp)
                        .align(Alignment.BottomCenter),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = (uiState as BuyerUiState.Error).message,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        TextButton(onClick = { viewModel.fetchListings() }) {
                            Text("Coba Lagi", fontSize = 13.sp)
                        }
                    }
                }
            } else if (!hasLocationPermission) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = 80.dp)
                        .align(Alignment.BottomCenter),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Izin lokasi belum aktif",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        TextButton(onClick = {
                            locationPermissionLauncher.launch(
                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            )
                        }) {
                            Text("Aktifkan", fontSize = 13.sp)
                        }
                    }
                }
            } else if (!hasRealLocation) {
                // PENTING: sebelumnya hasRealLocation cuma disimpan di ViewModel tapi
                // TIDAK PERNAH dibaca di UI manapun. Kalau fetchDeviceLocation() gagal
                // diam-diam (GPS mati/timeout/emulator tanpa location di-set) padahal
                // izin lokasi SUDAH diberikan, pencarian tetap jalan dari koordinat
                // fallback Jakarta — dan lapak yang lokasinya jauh dari Jakarta (mis.
                // di luar radius 1-5 km dari situ) akan selalu terlihat "kosong" tanpa
                // ada petunjuk bahwa penyebabnya adalah lokasi device, bukan datanya.
                Surface(
                    modifier = Modifier
                        .padding(bottom = 80.dp)
                        .align(Alignment.BottomCenter),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Belum dapat GPS asli, masih pakai lokasi default Jakarta",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        TextButton(onClick = { viewModel.fetchDeviceLocation() }) {
                            Text("Coba Lagi", fontSize = 13.sp)
                        }
                    }
                }
            } else if (uiState is BuyerUiState.Success && (uiState as BuyerUiState.Success).listings.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .padding(bottom = 80.dp)
                        .align(Alignment.BottomCenter),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = "Belum ada pedagang di radius ${"%.1f".format(radiusKm)} km",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
