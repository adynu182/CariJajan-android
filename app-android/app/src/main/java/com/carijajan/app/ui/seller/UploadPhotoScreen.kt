package com.carijajan.app.ui.seller

import android.Manifest
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import coil3.compose.AsyncImage
import com.carijajan.app.data.remote.ListingApi
import com.carijajan.app.data.work.PhotoUploadWorker
import com.carijajan.app.domain.model.ListingPhoto
import com.carijajan.app.domain.usecase.CaptureGpsUseCase
import com.carijajan.app.domain.usecase.compressPhoto
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadPhotoScreen(
    listingId: String,
    onBack: () -> Unit,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val listingApi = remember { ListingApi() }

    var isCapturing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var existingPhotos by remember { mutableStateOf<List<ListingPhoto>>(emptyList()) }
    var isLoadingPhotos by remember { mutableStateOf(true) }
    var photoPendingDelete by remember { mutableStateOf<ListingPhoto?>(null) }
    var isDeletingPhoto by remember { mutableStateOf(false) }

    suspend fun refreshPhotos() {
        existingPhotos = runCatching { listingApi.getPhotos(listingId) }.getOrDefault(existingPhotos)
    }

    LaunchedEffect(listingId) {
        isLoadingPhotos = true
        refreshPhotos()
        isLoadingPhotos = false
    }

    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val imageCapture = remember { ImageCapture.Builder().build() }

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ambil Foto Lapak") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // CameraX Preview
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                cameraSelector,
                                preview,
                                imageCapture
                            )
                        } catch (e: Exception) {
                            android.util.Log.e("UploadPhotoScreen", "Use case binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )

            // Galeri foto yang sudah tersimpan — ditampilkan waktu mau nambah foto
            // baru, supaya penjual bisa lihat & hapus foto lama sebelum/tanpa perlu
            // ambil foto baru.
            if (isLoadingPhotos || existingPhotos.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = if (isLoadingPhotos) "Memuat foto sebelumnya..." else "Foto sebelumnya — ketuk untuk hapus",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    if (isLoadingPhotos) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            items(existingPhotos, key = { it.id }) { photo ->
                                Box(modifier = Modifier.size(64.dp)) {
                                    AsyncImage(
                                        model = photo.thumbnailUrl ?: photo.photoUrl,
                                        contentDescription = "Foto lapak",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { photoPendingDelete = photo }
                                    )
                                    if (photo.isPrimary) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = "Foto utama",
                                            tint = Color(0xFFFFC107),
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(2.dp)
                                                .size(16.dp)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(2.dp)
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.6f))
                                            .clickable { photoPendingDelete = photo },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Hapus foto",
                                            tint = Color.White,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Shutter Button & Overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusText ?: "Foto lapakmu sekarang & kunci lokasi GPS",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                FloatingActionButton(
                    onClick = {
                        if (isCapturing) return@FloatingActionButton
                        isCapturing = true

                        val photoFile = File(context.cacheDir, "captured_${System.currentTimeMillis()}.jpg")
                        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                        imageCapture.takePicture(
                            outputOptions,
                            cameraExecutor,
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        try {
                                            // Step 1: Capture High Accuracy GPS
                                            statusText = "Mengunci lokasi GPS..."
                                            val gpsUseCase = CaptureGpsUseCase(context)
                                            val gpsResult = gpsUseCase.execute()

                                            // Step 2: Compress Photo (potong 1:1 & luruskan
                                            // rotasi EXIF) + buat thumbnail
                                            statusText = "Mengompres foto..."
                                            val compressedFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
                                            val thumbnailFile = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
                                            compressPhoto(photoFile, compressedFile, thumbnailFile)
                                            photoFile.delete()

                                            // Step 3: Enqueue WorkManager for Background Upload
                                            statusText = "Mengunggah foto..."
                                            val workId = PhotoUploadWorker.enqueue(
                                                context = context,
                                                listingId = listingId,
                                                localFilePath = compressedFile.absolutePath,
                                                thumbnailFilePath = thumbnailFile.absolutePath,
                                                latitude = gpsResult.latitude,
                                                longitude = gpsResult.longitude,
                                                gpsAccuracyM = gpsResult.accuracyMeters,
                                                capturedAtEpoch = gpsResult.capturedAt.epochSeconds,
                                                isPrimary = true
                                            )

                                            // Step 4: Tunggu upload BENAR-BENAR selesai (bukan cuma
                                            // ke-enqueue) sebelum menutup layar ini. Sebelumnya layar
                                            // langsung ditutup setelah enqueue, jadi kalau upload gagal
                                            // di background, user tidak pernah tahu — foto terlihat
                                            // "hilang" begitu saja.
                                            val finalInfo = withTimeoutOrNull(30_000L) {
                                                WorkManager.getInstance(context)
                                                    .getWorkInfoByIdFlow(workId)
                                                    .filterNotNull()
                                                    .first { it.state.isFinished }
                                            }

                                            isCapturing = false
                                            statusText = null

                                            when (finalInfo?.state) {
                                                WorkInfo.State.SUCCEEDED -> onSuccess()
                                                WorkInfo.State.FAILED -> {
                                                    errorMessage = "Gagal mengunggah foto. Pastikan " +
                                                        "koneksi internet stabil lalu coba lagi."
                                                }
                                                else -> {
                                                    // Belum selesai dalam 30 detik (mis. sinyal lemah).
                                                    // WorkManager tetap lanjut mencoba di background
                                                    // walau layar ini ditutup, jadi ini bukan kegagalan.
                                                    errorMessage = "Koneksi lambat — foto masih diunggah " +
                                                        "di latar belakang. Kamu bisa tutup halaman ini."
                                                }
                                            }
                                        } catch (error: Exception) {
                                            isCapturing = false
                                            statusText = null
                                            errorMessage = error.localizedMessage ?: "Gagal mengambil lokasi GPS"
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isCapturing = false
                                    statusText = null
                                    errorMessage = "Gagal mengambil foto: ${exception.message}"
                                }
                            }
                        )
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo", modifier = Modifier.size(36.dp))
                    }
                }
            }

            // Error Dialog
            if (errorMessage != null) {
                AlertDialog(
                    onDismissRequest = { errorMessage = null },
                    title = { Text("Peringatan GPS / Foto") },
                    text = { Text(errorMessage!!) },
                    confirmButton = {
                        Button(onClick = { errorMessage = null }) {
                            Text("Coba Lagi")
                        }
                    }
                )
            }

            // Delete Photo Confirmation Dialog
            photoPendingDelete?.let { photo ->
                AlertDialog(
                    onDismissRequest = { if (!isDeletingPhoto) photoPendingDelete = null },
                    title = { Text("Hapus foto ini?") },
                    text = { Text("Foto akan dihapus permanen dari lapak kamu. Tindakan ini tidak bisa dibatalkan.") },
                    confirmButton = {
                        Button(
                            enabled = !isDeletingPhoto,
                            onClick = {
                                isDeletingPhoto = true
                                scope.launch {
                                    try {
                                        listingApi.deletePhoto(photo)
                                        refreshPhotos()
                                        photoPendingDelete = null
                                    } catch (error: Exception) {
                                        errorMessage = error.localizedMessage ?: "Gagal menghapus foto"
                                    } finally {
                                        isDeletingPhoto = false
                                    }
                                }
                            }
                        ) {
                            if (isDeletingPhoto) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Hapus")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(
                            enabled = !isDeletingPhoto,
                            onClick = { photoPendingDelete = null }
                        ) {
                            Text("Batal")
                        }
                    }
                )
            }
        }
    }
}
