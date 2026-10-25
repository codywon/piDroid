package com.pi.app.ui.scanner

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.pi.app.ui.theme.LocalAppTheme
import java.util.concurrent.Executors

@Composable
fun QrScannerScreen(
    onScanSuccess: (url: String, token: String, name: String) -> Unit,
    onManualInputClick: () -> Unit = {},
    onClose: () -> Unit
) {
    BackHandler { onClose() }
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isProcessing by remember { mutableStateOf(false) }

    fun parseAndHandleQrCode(rawValue: String): Boolean {
        val trimmed = rawValue.trim()
        if (trimmed.startsWith("pidroid://connect")) {
            try {
                val uri = Uri.parse(trimmed)
                val url = uri.getQueryParameter("url") ?: ""
                val token = uri.getQueryParameter("token") ?: ""
                val name = uri.getQueryParameter("name") ?: "My PC"
                if (url.isNotEmpty() && token.isNotEmpty()) {
                    isProcessing = true
                    onScanSuccess(url, token, name)
                    return true
                }
            } catch (e: Exception) {
                Log.e("QrScanner", "Parse pidroid QR failed", e)
            }
        } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val uri = Uri.parse(trimmed)
                val token = uri.getQueryParameter("token") ?: ""
                val name = uri.getQueryParameter("name") ?: (uri.host ?: "My PC")
                val baseUrl = if (token.isNotEmpty()) {
                    "${uri.scheme}://${uri.authority}"
                } else {
                    trimmed
                }
                if (token.isNotEmpty()) {
                    isProcessing = true
                    onScanSuccess(baseUrl, token, name)
                    return true
                }
            } catch (e: Exception) {
                Log.e("QrScanner", "Parse http QR failed", e)
            }
        }
        return false
    }

    // 零权限系统相册图片选择器 (符合 Google Play 规范，无需存储权限)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val image = InputImage.fromFilePath(context, uri)
            val scanner = BarcodeScanning.getClient()
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var matched = false
                    for (barcode in barcodes) {
                        val rawValue = barcode.rawValue ?: continue
                        if (parseAndHandleQrCode(rawValue)) {
                            matched = true
                            break
                        }
                    }
                    if (!matched) {
                        val msg = if (barcodes.isEmpty()) {
                            "未在所选图片中识别到二维码"
                        } else {
                            "该二维码不是有效的 piDroid 配对码"
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "解析图片失败: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(context, "读取图片异常: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val cameraExecutor = Executors.newSingleThreadExecutor()
                val barcodeScanner = BarcodeScanning.getClient()

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { analysis ->
                            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null && !isProcessing) {
                                    val image = InputImage.fromMediaImage(
                                        mediaImage,
                                        imageProxy.imageInfo.rotationDegrees
                                    )
                                    barcodeScanner.process(image)
                                        .addOnSuccessListener { barcodes ->
                                            for (barcode in barcodes) {
                                                val rawValue = barcode.rawValue ?: continue
                                                if (parseAndHandleQrCode(rawValue)) {
                                                    break
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } else {
                                    imageProxy.close()
                                }
                            }
                        }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Log.e("QrScanner", "Camera bind failed", e)
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            }
        )

        // 取景器与操作交互层
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "扫码配对连接",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color.White)
                }
            }

            // 中央取景扫描框
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    modifier = Modifier.size(240.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    border = BorderStroke(2.dp, theme.primary)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = theme.primary.copy(alpha = 0.45f),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }

                Surface(
                    color = Color(0xFF0D1117).copy(alpha = 0.72f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                ) {
                    Text(
                        text = "对准 PC 终端二维码 · 或从下方相册选图 / 手动填写",
                        color = Color(0xFFE6EDF3),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // 底部双快捷操作胶囊（相册选码 + 手动添加）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = Color(0xFF161B22).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = "相册选码",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "相册选码",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    color = theme.primary.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onManualInputClick() }
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "手动添加",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "手动输入连接",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
