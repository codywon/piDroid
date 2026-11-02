package com.pi.app.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手机端内置 PDF 文档阅读器。
 *
 * 核心特性：
 * 1. 左右滑动手势切页（左右滑动翻到上一页/下一页）；
 * 2. 双指手势平滑缩放（Pinch-to-zoom 0.8x ~ 5x）与缩放后拖动平移；
 * 3. 双击手势（在 1x 与 2.5x 之间快速切换）；
 * 4. 底部常驻悬浮页码栏与切页按钮。
 */
@Composable
internal fun PdfDocumentView(
    file: File,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val density = LocalDensity.current

    var currentPage by remember(file.path) { mutableIntStateOf(0) }
    var pageCount by remember(file.path) { mutableIntStateOf(0) }
    var currentBitmap by remember(file.path, currentPage) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(file.path, currentPage) { mutableStateOf(true) }
    var errorMessage by remember(file.path) { mutableStateOf<String?>(null) }

    // 缩放与平移状态（每次切页复位为 1x 与原点）
    var scale by remember(currentPage) { mutableFloatStateOf(1f) }
    var offset by remember(currentPage) { mutableStateOf(Offset.Zero) }
    var horizontalDragAmount by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(file.path, currentPage) {
        isLoading = true
        errorMessage = null
        val result = withContext(Dispatchers.IO) {
            try {
                if (!file.exists()) return@withContext null
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val count = renderer.pageCount
                        if (count <= 0) return@withContext null
                        val safeIdx = currentPage.coerceIn(0, count - 1)
                        renderer.openPage(safeIdx).use { page ->
                            val w = (page.width * 2.2f).toInt().coerceIn(600, 2400)
                            val h = (page.height * 2.2f).toInt().coerceIn(600, 3200)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp.asImageBitmap() to count
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        if (result != null) {
            currentBitmap = result.first
            pageCount = result.second
        } else if (!file.exists()) {
            errorMessage = "PDF 文件不存在或已被清理"
        } else {
            errorMessage = "PDF 解析失败（可能受密码保护或损坏）"
        }
        isLoading = false
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(theme.surfaceContainerLow),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading && currentBitmap == null -> {
                CircularProgressIndicator(
                    color = theme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }
            errorMessage != null -> {
                Text(
                    text = errorMessage.orEmpty(),
                    color = theme.textMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
            currentBitmap != null -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(currentPage) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                            offset = Offset.Zero
                                        }
                                    }
                                )
                            }
                            .pointerInput(currentPage, scale) {
                                if (scale > 1.05f) {
                                    // 放大状态：双指/单指拖动平移与缩放
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        val newScale = (scale * zoom).coerceIn(0.75f, 5f)
                                        scale = newScale
                                        offset = if (newScale > 1.05f) {
                                            Offset(offset.x + pan.x, offset.y + pan.y)
                                        } else {
                                            Offset.Zero
                                        }
                                    }
                                } else {
                                    // 原始比例：单指左右拖动快速翻页
                                    detectHorizontalDragGestures(
                                        onDragStart = { horizontalDragAmount = 0f },
                                        onDragEnd = {
                                            val thresholdPx = with(density) { 45.dp.toPx() }
                                            if (horizontalDragAmount < -thresholdPx && currentPage < pageCount - 1) {
                                                currentPage++
                                            } else if (horizontalDragAmount > thresholdPx && currentPage > 0) {
                                                currentPage--
                                            }
                                            horizontalDragAmount = 0f
                                        },
                                        onDragCancel = { horizontalDragAmount = 0f },
                                        onHorizontalDrag = { change, dragAmount ->
                                            change.consume()
                                            horizontalDragAmount += dragAmount
                                        }
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = currentBitmap!!,
                            contentDescription = "第 ${currentPage + 1} 页",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                                .padding(8.dp)
                        )
                    }

                    if (pageCount > 1) {
                        Surface(
                            color = theme.surfaceContainer.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.4f)),
                            modifier = Modifier.padding(bottom = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                IconButton(
                                    onClick = { if (currentPage > 0) currentPage-- },
                                    enabled = currentPage > 0,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "上一页",
                                        tint = if (currentPage > 0) theme.textPrimary else theme.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                Text(
                                    text = "${currentPage + 1} / $pageCount",
                                    fontSize = 12.sp,
                                    color = theme.textPrimary
                                )

                                IconButton(
                                    onClick = { if (currentPage < pageCount - 1) currentPage++ },
                                    enabled = currentPage < pageCount - 1,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = "下一页",
                                        tint = if (currentPage < pageCount - 1) theme.textPrimary else theme.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
