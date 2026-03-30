package com.mrboombastic.buwudzik.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
import com.mrboombastic.buwudzik.data.DeviceProfile
import com.mrboombastic.buwudzik.data.DeviceShareData
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.data.normalizedBluetoothMac
import com.mrboombastic.buwudzik.ui.components.ContentCard
import com.mrboombastic.buwudzik.ui.components.InstructionCard
import com.mrboombastic.buwudzik.ui.components.StandardTopBar
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.viewmodels.MainViewModel

private val qrReader = MultiFormatReader().apply {
    setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceImportScreen(
    navController: NavController, viewModel: MainViewModel
) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(true) }
    val importSuccessMsg = stringResource(R.string.import_success)
    val importErrorMsg = stringResource(R.string.import_error)

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            hasCameraPermission = it
        }

    LaunchedEffect(Unit) {
        hasCameraPermission = context.checkSelfPermission(
            Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            StandardTopBar(
                title = stringResource(R.string.import_device_title), navController = navController
            )
        }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when {
                !hasCameraPermission -> {
                    Box(
                        modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
                    ) {
                        ContentCard(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    text = stringResource(R.string.camera_permission_required),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                isScanning -> {
                    // Header card with instructions
                    InstructionCard(
                        icon = Icons.Default.QrCodeScanner,
                        title = stringResource(R.string.import_qr_instruction)
                    ) {
                        // No additional content is needed for this instruction card.
                    }

                    // Camera preview card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(12.dp)
                                    ), shape = RoundedCornerShape(12.dp)
                            ) {
                                QrScannerView { content ->
                                    isScanning = false
                                    val shareData = DeviceShareData.fromQrContent(content)
                                    if (shareData != null) {
                                        // Add an imported device as a new profile
                                        val tokenStorage = TokenStorage(context)
                                        val profile = DeviceProfile(
                                            mac = shareData.mac.normalizedBluetoothMac(),
                                            alias = shareData.mac, // user can rename it later
                                            batteryType = shareData.batteryType
                                        )
                                        val alarmTitleRepository =
                                            AlarmTitleRepository(context, profile.mac)
                                        viewModel.addDevice(profile)

                                        // Store token for the imported device
                                        tokenStorage.storeToken(
                                            profile.mac, tokenStorage.hexToBytes(shareData.token)
                                        )

                                        // Import alarm titles (per-device)
                                        shareData.alarmTitles.forEach { (id, title) ->
                                            alarmTitleRepository.setTitle(id, title)
                                        }

                                        viewModel.setActiveDevice(profile.mac)
                                        viewModel.checkPairingStatus()

                                        Toast.makeText(
                                            context, importSuccessMsg, Toast.LENGTH_SHORT
                                        ).show()
                                        navController.navigate("home") {
                                            popUpTo(navController.graph.id) { inclusive = true }
                                        }
                                    } else {
                                        Toast.makeText(context, importErrorMsg, Toast.LENGTH_SHORT)
                                            .show()
                                        isScanning = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QrScannerView(
    onQrCodeScanned: (String) -> Unit
) {
    LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var scanned by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    try {
                        val provider = cameraProviderFuture.get()
                        // Defer bind until PreviewView is attached; post is queued until then if needed.
                        previewView.post {
                            if (!previewView.isAttachedToWindow) return@post
                            try {
                                cameraProvider = provider

                                val preview = Preview.Builder().build().apply {
                                    surfaceProvider = previewView.surfaceProvider
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(ctx.mainExecutor) { imageProxy ->
                                            if (!scanned) {
                                                processImageProxy(imageProxy) {
                                                    scanned = true
                                                    onQrCodeScanned(it)
                                                }
                                            } else {
                                                imageProxy.close()
                                            }
                                        }
                                    }

                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    imageAnalysis
                                )
                            } catch (e: Exception) {
                                AppLogger.e("DeviceImportScreen", "Camera setup failed", e)
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e("DeviceImportScreen", "Camera provider failed", e)
                    }
                },
                mainExecutor
            )
            previewView
        }
    )
}


@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy, onBarcodeDetected: (String) -> Unit
) {
    val image = imageProxy.image ?: run {
        imageProxy.close()
        return
    }

    try {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val source = PlanarYUVLuminanceSource(
            bytes, image.width, image.height, 0, 0, image.width, image.height, false
        )

        val bitmap = BinaryBitmap(HybridBinarizer(source))

        val result = qrReader.decodeWithState(bitmap)
        onBarcodeDetected(result.text)
    } catch (_: NotFoundException) {
        // no QR in this frame
    } catch (e: Exception) {
        AppLogger.v("DeviceImportScreen", "Error processing image", e)
    } finally {
        imageProxy.close()
        qrReader.reset()
    }
}
