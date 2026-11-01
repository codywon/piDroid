package com.pi.app.ui.components

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 手机端内置 PDF 文档阅读器。
 *
 * 使用 Android 原生 [PdfRenderer] 逐页渲染为位图，支持多页切换与防 OOM 分辨率限制。
 */
@Composable
internal fun PdfDocumentView(
    file: File,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    var currentPage by remember(file.path) { mutableIntStateOf(0) }
    var pageCount by remember(file.path) { mutableIntStateOf(0) }
    var currentBitmap by remember(file.path, currentPage) { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember(file.path, currentPage) { mutableStateOf(true) }
    var errorMessage by remember(file.path) { mutableStateOf<String?>(null) }

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
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = currentBitmap!!,
                            contentDescription = "第 ${currentPage + 1} 页",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp)
                        )
                    }

                    if (pageCount > 1) {
                        Surface(
                            color = theme.surfaceContainer.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(20.dp),
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
