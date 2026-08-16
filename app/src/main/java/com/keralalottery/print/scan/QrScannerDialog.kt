package com.keralalottery.print.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Full-screen live camera scanner for a lottery ticket's own barcode/QR code. ML Kit's default
 * scanner detects both 1D barcodes and QR codes with no extra configuration, so this covers
 * whichever the ticket actually has. Decodes whatever the ticket printer put in the code (this
 * app doesn't control that format) and hands the raw scanned text straight back to the caller.
 */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onResultState = rememberUpdatedState(onResult)
    var handled by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val scanner = BarcodeScanning.getClient()
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage == null || handled) {
                                imageProxy.close()
                            } else {
                                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes: List<Barcode> ->
                                        val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                                        if (value != null && !handled) {
                                            handled = true
                                            onResultState.value(value)
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            }
                        }
                        runCatching {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                }
            )

            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(240.dp)
                    .border(BorderStroke(3.dp, Color.White))
            )

            Text(
                "ടിക്കറ്റിലെ ബാർകോഡ്/QR കോഡ് ക്യാമറയ്ക്ക് നേരെ പിടിക്കുക",
                color = Color.White,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "അടയ്ക്കുക", tint = Color.White)
            }
        }
    }
}

/** Takes just the last 4 digits out of whatever the scanned code decoded to. The government
 * ticket's own QR turns out to encode an opaque verification hash, not the printed ticket
 * number, in any form this app can reverse - so this deliberately does not try to be clever
 * about the format (a letters+digits ticket-number shape, a URL, etc.); "last 4 digits" is a
 * blunt, honest heuristic that may or may not land on the real number depending on what the
 * scanned code actually contains. Returns null if there aren't even 4 digits to take. */
fun extractLastFourDigits(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    if (digits.length < 4) return null
    return digits.takeLast(4)
}
