package com.carijajan.app.ui.buyer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.carijajan.app.data.repository.ListingRepository
import com.carijajan.app.domain.model.Listing
import com.carijajan.app.domain.model.ReportReason
import com.carijajan.app.ui.theme.ColorClosed
import com.carijajan.app.ui.theme.ColorOpen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ListingDetailViewModel(
    private val repository: ListingRepository
) : ViewModel() {

    private val _listing = MutableStateFlow<Listing?>(null)
    val listing: StateFlow<Listing?> = _listing.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun loadDetail(listingId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _listing.value = repository.getListingDetail(listingId)
            _isLoading.value = false
        }
    }

    fun submitReport(listingId: String, reason: ReportReason, detail: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.reportListing(listingId, reason, detail, null)
            }
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingDetailScreen(
    listingId: String,
    repository: ListingRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { ListingDetailViewModel(repository) }

    LaunchedEffect(listingId) {
        viewModel.loadDetail(listingId)
    }

    val listing by viewModel.listing.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var showReportDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listing?.name ?: "Detail Lapak") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = { showReportDialog = true }) {
                        Icon(Icons.Default.ReportProblem, contentDescription = "Laporkan", tint = MaterialTheme.colorScheme.error)
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
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Lapak tidak ditemukan")
            }
        } else {
            val item = listing!!

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Photo Gallery
                if (item.photos.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        item.photos.forEach { photo ->
                            AsyncImage(
                                model = photo.photoUrl,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(320.dp)
                            )
                        }
                    }
                } else if (item.primaryPhotoUrl != null) {
                    AsyncImage(
                        model = item.primaryPhotoUrl,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )
                }

                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${item.category.emoji} ${item.name}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = if (item.isOpen) ColorOpen else ColorClosed,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (item.isOpen) "BUKA" else "TUTUP",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = item.priceLabel,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Update terakhir: ${formatTimeAgo(item.lastPhotoAt)}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                    )

                    if (!item.description.isNullOrBlank()) {
                        Text(
                            text = "Deskripsi",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.description,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )
                    }

                    Divider()

                    Spacer(modifier = Modifier.height(16.dp))

                    // Deep Link Google Maps / Waze Button
                    Button(
                        onClick = {
                            val uri = Uri.parse("geo:${item.latitude},${item.longitude}?q=${item.latitude},${item.longitude}(${Uri.encode(item.name)})")
                            val mapIntent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(mapIntent)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Arahkan ke Sini (Google Maps)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // WhatsApp Share Button
                    OutlinedButton(
                        onClick = {
                            val shareText = "Yuk jajan di ${item.name} (${item.category.displayName})! Jarak ${item.distanceLabel}. Buka CariJajan untuk lokasi terakurat."
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Bagikan Lapak"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Bagikan via WhatsApp")
                    }
                }
            }
        }
    }

    if (showReportDialog && listing != null) {
        var selectedReason by remember { mutableStateOf(ReportReason.LOKASI_SALAH) }
        var detailText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Laporkan Lapak Ini") },
            text = {
                Column {
                    Text("Pilih alasan laporan:")
                    Spacer(modifier = Modifier.height(8.dp))

                    ReportReason.entries.forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = selectedReason == reason,
                                onClick = { selectedReason = reason }
                            )
                            Text(reason.displayName, fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = detailText,
                        onValueChange = { detailText = it },
                        label = { Text("Keterangan tambahan (opsional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.submitReport(listing!!.id, selectedReason, detailText) {
                            showReportDialog = false
                        }
                    }
                ) {
                    Text("Kirim Laporan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Batal")
                }
            }
        )
    }
}
