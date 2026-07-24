package com.carijajan.app.ui.buyer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
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
import coil3.compose.AsyncImage
import com.carijajan.app.domain.model.Category
import com.carijajan.app.domain.model.Listing
import com.carijajan.app.ui.theme.ColorClosed
import com.carijajan.app.ui.theme.ColorOpen
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuyerListScreen(
    viewModel: BuyerViewModel,
    onNavigateToMap: () -> Unit,
    onSelectListing: (String) -> Unit
) {
    val radiusKm by viewModel.radiusKm.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lapak Terdekat") },
                actions = {
                    IconButton(onClick = onNavigateToMap) {
                        Icon(Icons.Default.Map, contentDescription = "Tampilan Peta")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Jangkauan Jarak",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${"%.1f".format(radiusKm)} km",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

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

            Divider()

            // Listing LazyColumn
            when (uiState) {
                is BuyerUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is BuyerUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = (uiState as BuyerUiState.Error).message,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                is BuyerUiState.Success -> {
                    val listings = (uiState as BuyerUiState.Success).listings
                    if (listings.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Belum ada pedagang di radius ${"%.1f".format(radiusKm)} km",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(listings, key = { it.id }) { listing ->
                                ListingCard(
                                    listing = listing,
                                    onClick = { onSelectListing(listing.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ListingCard(
    listing: Listing,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            // Thumbnail image
            AsyncImage(
                model = listing.primaryThumbnailUrl ?: listing.primaryPhotoUrl,
                contentDescription = listing.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(90.dp)
                    .clip(RoundedCornerShape(8.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${listing.category.emoji} ${listing.name}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Surface(
                        color = if (listing.isOpen) ColorOpen else ColorClosed,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (listing.isOpen) "BUKA" else "TUTUP",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = listing.priceLabel,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = listing.distanceLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = formatTimeAgo(listing.lastPhotoAt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun formatTimeAgo(lastPhotoAt: Instant?): String {
    if (lastPhotoAt == null) return "Belum ada foto"
    val diffSeconds = Clock.System.now().epochSeconds - lastPhotoAt.epochSeconds
    val minutes = diffSeconds / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "baru saja"
        minutes < 60 -> "$minutes mnt lalu"
        hours < 24 -> "$hours jam lalu"
        else -> "${hours / 24} hr lalu"
    }
}
