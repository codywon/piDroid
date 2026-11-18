package com.pi.app.ui.chat

import com.pi.app.data.model.FilePlaybackSource
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 针对 [ChatScreen] 中抽出的纯 JVM 核心辅助函数的回归测试：
 * 1. [readBoundedStreamBytes] / [drainBounded]：空文件、云盘虚高/偏小 declared length、零字节 chunk 与 OOM/取消边界；
 * 2. [sanitizePlaybackSourceForStreaming]：媒体流式播放同源校验与跨源 Authorization 头剥离。
 */
class ChatScreenHelpersTest {

    @Test
    fun `readBoundedStreamBytes supports zero-declared empty file`() {
        val result = readBoundedStreamBytes(declared = 0L, maxBytes = 1024) {
            ByteArrayInputStream(ByteArray(0))
        }
        assertArrayEquals(ByteArray(0), result)
    }

    @Test
    fun `readBoundedStreamBytes reads full payload when declared is zero or unknown`() {
        val payload = "hello pi".toByteArray(Charsets.UTF_8)
        val fromZero = readBoundedStreamBytes(declared = 0L, maxBytes = 1024) {
            ByteArrayInputStream(payload)
        }
        assertArrayEquals(payload, fromZero)

        val fromUnknown = readBoundedStreamBytes(declared = -1L, maxBytes = 1024) {
            ByteArrayInputStream(payload)
        }
        assertArrayEquals(payload, fromUnknown)
    }

    @Test
    fun `readBoundedStreamBytes trims when declared length exceeds actual stream eof`() {
        val actual = byteArrayOf(1, 2, 3, 4, 5)
        val result = readBoundedStreamBytes(declared = 20L, maxBytes = 1024) {
            ByteArrayInputStream(actual)
        }
        assertArrayEquals(actual, result)
    }

    @Test
    fun `readBoundedStreamBytes drains remainder when stream exceeds declared length within limit`() {
        val actual = byteArrayOf(10, 20, 30, 40, 50, 60)
        val result = readBoundedStreamBytes(declared = 3L, maxBytes = 10) {
            ByteArrayInputStream(actual)
        }
        assertArrayEquals(actual, result)
    }

    @Test
    fun `readBoundedStreamBytes rejects payload exceeding maxBytes by even one byte`() {
        val actual = byteArrayOf(1, 2, 3, 4, 5)
        // declared == maxBytes，但实际流多出 1 字节
        val overByOne = readBoundedStreamBytes(declared = 4L, maxBytes = 4) {
            ByteArrayInputStream(actual)
        }
        assertNull(overByOne)

        // unknown size 超限
        val overUnknown = readBoundedStreamBytes(declared = -1L, maxBytes = 4) {
            ByteArrayInputStream(actual)
        }
        assertNull(overUnknown)
    }

    @Test
    fun `readBoundedStreamBytes and drainBounded continue when stream read returns zero`() {
        val payload = byteArrayOf(7, 8, 9)
        class ZeroReadFirstStream(private val data: ByteArray) : InputStream() {
            private var pos = 0
            private var emittedZero = false

            override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!emittedZero) {
                    emittedZero = true
                    return 0
                }
                if (pos >= data.size) return -1
                val count = minOf(len, data.size - pos)
                System.arraycopy(data, pos, b, off, count)
                pos += count
                return count
            }
        }

        val streamResult = readBoundedStreamBytes(declared = 3L, maxBytes = 16) {
            ZeroReadFirstStream(payload)
        }
        assertArrayEquals(payload, streamResult)

        val out = ByteArrayOutputStream()
        assertTrue(drainBounded(ZeroReadFirstStream(payload), out, alreadyRead = 0, maxBytes = 16))
        assertArrayEquals(payload, out.toByteArray())
        assertFalse(drainBounded(ZeroReadFirstStream(payload), ByteArrayOutputStream(), alreadyRead = 15, maxBytes = 16))
    }

    @Test
    fun `readBoundedStreamBytes catches OutOfMemoryError and rethrows CancellationException`() {
        val oomResult = readBoundedStreamBytes(declared = -1L, maxBytes = 1024) {
            object : InputStream() {
                override fun read(): Int = throw OutOfMemoryError("simulated oom")
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    throw OutOfMemoryError("simulated oom")
            }
        }
        assertNull(oomResult)

        var caughtCancellation = false
        try {
            readBoundedStreamBytes(declared = -1L, maxBytes = 1024) {
                throw CancellationException("cancelled")
            }
        } catch (_: CancellationException) {
            caughtCancellation = true
        }
        assertTrue(caughtCancellation)
    }

    @Test
    fun `sanitizePlaybackSourceForStreaming strips auth headers for cross-origin or blank gateway`() {
        val headers = mapOf(
            "Authorization" to "Bearer secret-token",
            "Proxy-Authorization" to "Basic abc",
            "X-Custom" to "keep"
        )
        val sameOrigin = sanitizePlaybackSourceForStreaming(
            source = FilePlaybackSource("https://gw.example.com/api/files/a.mp3?type=read", headers),
            gatewayServerUrl = "https://gw.example.com"
        )
        assertEquals(headers, sameOrigin?.headers)

        val crossOrigin = sanitizePlaybackSourceForStreaming(
            source = FilePlaybackSource("https://evil.example.com/a.mp3", headers),
            gatewayServerUrl = "https://gw.example.com"
        )
        assertEquals(mapOf("X-Custom" to "keep"), crossOrigin?.headers)

        val blankGateway = sanitizePlaybackSourceForStreaming(
            source = FilePlaybackSource("https://gw.example.com/a.mp3", headers),
            gatewayServerUrl = ""
        )
        assertEquals(mapOf("X-Custom" to "keep"), blankGateway?.headers)

        assertNull(sanitizePlaybackSourceForStreaming(null, "https://gw.example.com"))
    }
}
