package com.mrboombastic.buwudzik.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.mrboombastic.buwudzik.MainViewModel
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
import com.mrboombastic.buwudzik.data.DeviceShareData
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.ui.components.BackNavigationButton
import java.util.concurrent.Executors

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceImportScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(true) }

    val importSuccessMsg = stringResource(R.string.import_success)
    val importErrorMsg = stringResource(R.string.import_error)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) {
            hasCameraPermission = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_device_title)) },
                navigationIcon = { BackNavigationButton(navController) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!hasCameraPermission) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.camera_permission_required),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
            } else if (isScanning) {
                Text(
                    text = stringResource(R.string.import_qr_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    QrScannerView(
                        onQrCodeScanned = { content ->
                            isScanning = false
                            val shareData = DeviceShareData.fromQrContent(content)
                            if (shareData != null) {
                                // Import the device
                                val settingsRepo = SettingsRepository(context)
                                val tokenStorage = TokenStorage(context)
                                val alarmTitleRepository = AlarmTitleRepository(context)

                                settingsRepo.targetMacAddress = shareData.mac
                                settingsRepo.batteryType = shareData.batteryType
                                settingsRepo.isSetupCompleted = true
                                tokenStorage.storeToken(
                                    shareData.mac,
                                    tokenStorage.hexToBytes(shareData.token)
                                )

                                // Import alarm titles
                                shareData.alarmTitles.forEach { (id, title) ->
                                    alarmTitleRepository.setTitle(id, title)
                                }

                                viewModel.checkPairingStatus()

                                Toast.makeText(context, importSuccessMsg, Toast.LENGTH_SHORT).show()
                                navController.navigate("home") {
                                    popUpTo("home") { inclusive = true }
                                }
                            } else {
                                Toast.makeText(context, importErrorMsg, Toast.LENGTH_SHORT).show()
                                isScanning = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun QrScannerView(onQrCodeScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = Executors.newSingleThreadExecutor()
            val barcodeScanner = BarcodeScanning.getClient()

            cameraProviderFuture.addListener({
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(executor) { imageProxy ->
                            processImageProxy(imageProxy, barcodeScanner) { barcode ->
                                barcode.rawValue?.let { value ->
                                    onQrCodeScanned(value)
                                }
                            }
                        }
                    }

                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@OptIn(ExperimentalGetImage::class)
private fun processImageProxy(
    imageProxy: ImageProxy,
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    onBarcodeDetected: (Barcode) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage != null) {
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstOrNull()?.let { barcode ->
                    onBarcodeDetected(barcode)
                }
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    } else {
        imageProxy.close()
    }
}
