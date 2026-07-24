package com.carijajan.app.ui.seller

import android.Manifest
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.carijajan.app.data.work.PhotoUploadWorker
import com.carijajan.app.domain.usecase.CaptureGpsUseCase
import com.carijajan.app.domain.usecase.compressPhoto
import kotlinx.coroutines.launch
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

    var isCapturing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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

            // Shutter Button & Overlay
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Foto lapakmu sekarang & kunci lokasi GPS",
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
                                        runCatching {
                                            // Step 1: Capture High Accuracy GPS
                                            val gpsUseCase = CaptureGpsUseCase(context)
                                            val gpsResult = gpsUseCase.execute()

                                            // Step 2: Compress Photo
                                            val compressedFile = File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
                                            compressPhoto(photoFile, compressedFile)
                                            photoFile.delete()

                                            // Step 3: Enqueue WorkManager for Background Upload
                                            PhotoUploadWorker.enqueue(
                                                context = context,
                                                listingId = listingId,
                                                localFilePath = compressedFile.absolutePath,
                                                latitude = gpsResult.latitude,
                                                longitude = gpsResult.longitude,
                                                gpsAccuracyM = gpsResult.accuracyMeters,
                                                capturedAtEpoch = gpsResult.capturedAt.epochSeconds,
                                                isPrimary = true
                                            )
                                        }.onSuccess {
                                            isCapturing = false
                                            onSuccess()
                                        }.onFailure { error ->
                                            isCapturing = false
                                            errorMessage = error.localizedMessage ?: "Gagal mengambil lokasi GPS"
                                        }
                                    }
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    isCapturing = false
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
        }
    }
}
