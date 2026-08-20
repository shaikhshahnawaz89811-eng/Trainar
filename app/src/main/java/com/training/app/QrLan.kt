package com.training.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.Color
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.ExperimentalGetImage

data class LanQrPayload(val host: String, val port: Int, val code: String, val maxDevices: Int)

fun makeLanQrPayload(host: String, port: Int, code: String, maxDevices: Int): String =
    "training-lan-v1|host=${host.trim()}|port=$port|code=${code.trim()}|max=$maxDevices"

fun parseLanQrPayload(value: String): LanQrPayload {
    val parts = value.split('|').drop(1).associate {
        val pair = it.split('=', limit = 2)
        pair.first() to pair.getOrElse(1) { "" }
    }
    require(value.startsWith("training-lan-v1|")) { "This QR is not a Training LAN pairing code" }
    return LanQrPayload(
        parts["host"].orEmpty().also { require(it.isNotBlank()) { "QR has no host" } },
        parts["port"]?.toIntOrNull() ?: error("QR has invalid port"),
        parts["code"].orEmpty().also { require(it.length >= 8) { "QR has invalid pairing code" } },
        parts["max"]?.toIntOrNull()?.coerceIn(1, 8) ?: error("QR has invalid device limit")
    )
}

fun createQrBitmap(value: String, size: Int = 720): Bitmap {
    val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
}

@Composable
fun LanQrCode(value: String, modifier: Modifier = Modifier) {
    val bitmap = remember(value) { createQrBitmap(value) }
    Image(bitmap.asImageBitmap(), "LAN pairing QR code", modifier.size(280.dp))
}

@Composable
fun LanQrScanner(onDecoded: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it; denied = !it
    }
    LaunchedEffect(Unit) { if (!permission) request.launch(Manifest.permission.CAMERA) }
    if (!permission) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (denied) "Camera permission is required to scan the pairing QR." else "Requesting camera permission…", color = Color.White)
            if (denied) Button({ request.launch(Manifest.permission.CAMERA) }) { Text("ALLOW CAMERA") }
            OutlinedButton(onClick = onClose) { Text("CANCEL") }
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        AndroidView(
            factory = { PreviewView(it) },
            modifier = Modifier.fillMaxWidth().height(360.dp),
            update = { previewView ->
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val camera = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    val executor = Executors.newSingleThreadExecutor()
                    analysis.setAnalyzer(executor, QrAnalyzer { text ->
                        camera.unbindAll()
                        ContextCompat.getMainExecutor(context).execute { onDecoded(text) }
                    })
                    runCatching {
                        camera.unbindAll()
                        camera.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("CANCEL SCAN") }
    }
}

private class QrAnalyzer(private val result: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val delivered = AtomicBoolean(false)
    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: androidx.camera.core.ImageProxy) {
        val media = image.image
        if (media == null) { image.close(); return }
        val plane = media.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(media.width * media.height)
        if (plane.pixelStride == 1 && plane.rowStride >= media.width) {
            for (row in 0 until media.height) {
                val sourcePosition = row * plane.rowStride
                if (sourcePosition + media.width <= buffer.limit()) {
                    buffer.position(sourcePosition)
                    buffer.get(bytes, row * media.width, media.width)
                }
            }
            buffer.rewind()
        } else {
            buffer.rewind()
            buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))
        }
        val source = PlanarYUVLuminanceSource(
            bytes, media.width, media.height,
            0, 0, media.width, media.height, false
        )
        runCatching {
            MultiFormatReader().apply {
                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
            }.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        }.onSuccess { text -> if (delivered.compareAndSet(false, true)) result(text) }
        image.close()
    }
}
