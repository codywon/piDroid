package com.pi.app.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手机端内置 PDF 文档阅读器。
 *
 * 核心特性（对标 ZoomableImageCanvas 流畅交互）：
 * 1. 双指手势平滑缩放（0.75x ~ 6x）与视口边界平移，手势协程平稳不重置；
 * 2. 绘制层基于 graphicsLayer Lambda 实现，零重组极速渲染；
 * 3. 双击手势在 1x 与 2.5x 之间快速切换；
 * 4. 放大时右下角显示「xx% · 点击复位」胶囊；
 * 5. 底部常驻悬浮页码栏与上一页/下一页控制按钮。
 */
@Composable
internal fun PdfDocumentView(
    file: File,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current

    var currentPage by remember(file.path) { mutableIntStateOf(0) }
    var pageCount by remember(file.path) { mutableIntStateOf(0) }
    // 仅以 file.path 为 key，翻页解码期间保留上一页图像防止白屏闪烁
    var currentBitmap by remember(file.path) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(file.path, currentPage) { mutableStateOf(true) }
    var errorMessage by remember(file.path) { mutableStateOf<String?>(null) }

    // 缩放与平移状态（每次切页复位为 1x 与原点）
    var scale by remember(currentPage) { mutableFloatStateOf(1f) }
    var offset by remember(currentPage) { mutableStateOf(Offset.Zero) }

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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
                                    onDoubleTap = { tapOffset ->
                                        if (scale > 1.05f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            scale = 2.5f
                                            val center = Offset(size.width / 2f, size.height / 2f)
                                            val maxOffsetX = (size.width * (2.5f - 1f) / 2f).coerceAtLeast(0f)
                                            val maxOffsetY = (size.height * (2.5f - 1f) / 2f).coerceAtLeast(0f)
                                            offset = Offset(
                                                ((center.x - tapOffset.x) * 1.5f).coerceIn(-maxOffsetX, maxOffsetX),
                                                ((center.y - tapOffset.y) * 1.5f).coerceIn(-maxOffsetY, maxOffsetY)
                                            )
                                        }
                                    }
                                )
                            }
                            .pointerInput(currentPage) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val newScale = (scale * zoom).coerceIn(0.75f, 6f)
                                    scale = newScale
                                    if (newScale > 1.05f) {
                                        val maxOffsetX = (size.width * (newScale - 1f) / 2f).coerceAtLeast(0f)
                                        val maxOffsetY = (size.height * (newScale - 1f) / 2f).coerceAtLeast(0f)
                                        offset = Offset(
                                            (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                            (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                                        )
                                    } else {
                                        offset = Offset.Zero
                                    }
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
                                .padding(8.dp)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )

                        // 放大时右下角显示复位胶囊（与 ZoomableImageCanvas 1:1 对齐）
                        if (scale > 1.15f) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = theme.surfaceContainer.copy(alpha = 0.88f),
                                border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                                    .clickable {
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                            ) {
                                Text(
                                    text = "${(scale * 100).toInt()}% · 点击复位",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = theme.textPrimary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (pageCount > 1) {
                        Surface(
                            color = theme.surfaceContainer.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.4f)),
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
