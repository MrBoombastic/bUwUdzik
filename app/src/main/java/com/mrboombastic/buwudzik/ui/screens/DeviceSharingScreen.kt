package com.mrboombastic.buwudzik.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
import com.mrboombastic.buwudzik.data.DeviceShareData
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.ui.components.BackNavigationButton
import com.mrboombastic.buwudzik.utils.AppLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSharingScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val tokenStorage = remember { TokenStorage(context) }
    val alarmTitleRepository = remember { AlarmTitleRepository(context) }

    val mac = settingsRepo.targetMacAddress
    val tokenHex = tokenStorage.getTokenHex(mac)
    val batteryType = settingsRepo.batteryType
    val alarmTitles = remember { alarmTitleRepository.getAllTitles() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_device_title)) },
                navigationIcon = { BackNavigationButton(navController) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (tokenHex == null) {
                // No device paired
                Text(
                    text = stringResource(R.string.no_device_to_share),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                // Generate QR code
                val shareData = DeviceShareData(
                    mac = mac,
                    token = tokenHex,
                    batteryType = batteryType,
                    alarmTitles = alarmTitles
                )
                val qrContent = shareData.toQrContent()
                val qrBitmap = remember(qrContent) { generateQrCode(qrContent) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (qrBitmap != null) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.share_qr_instruction),
                                modifier = Modifier.size(280.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.share_qr_instruction),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = mac,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun generateQrCode(content: String): Bitmap? {
    val size = 512
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap[x, y] =
                    if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
        }
        bitmap
    } catch (e: Exception) {
        AppLogger.d("QR", "Error generating QR code: ${e.message}")
        null
    }
}
