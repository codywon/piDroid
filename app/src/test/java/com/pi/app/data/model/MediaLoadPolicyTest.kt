package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片加载三条纯逻辑的回归测试。
 *
 * 守护的线上现象：会话里图片一多，进会话/往上翻都能看到「正在加载图像 xxx.png…」长时间转圈，
 * 且低端机滚动时频繁 GC。根因是三条：全尺寸解码、图片字节无磁盘缓存、延迟图 URL 拼不出来。
 */
class MediaLoadPolicyTest {

    // ── 解码降采样 ──────────────────────────────────────────────

    @Test
    fun `原图不大于目标时不降采样`() {
        assertEquals(1, MediaLoadPolicy.sampleSizeFor(1200, 800, 2160))
        assertEquals(1, MediaLoadPolicy.sampleSizeFor(2160, 2160, 2160))
    }

    @Test
    fun `4000宽的超清图降一档到2000`() {
        assertEquals(2, MediaLoadPolicy.sampleSizeFor(4000, 3000, 2160))
    }

    @Test
    fun `超大图逐档降到目标以内`() {
        val sample = MediaLoadPolicy.sampleSizeFor(10000, 7000, 2160)
        assertEquals(8, sample)
        assertTrue("解码后长边必须落进目标", 10000 / sample <= 2160)
    }

    @Test
    fun `尺寸未知时退回原尺寸`() {
        assertEquals(1, MediaLoadPolicy.sampleSizeFor(0, 0, 2160))
        assertEquals(1, MediaLoadPolicy.sampleSizeFor(-1, 300, 2160))
    }

    @Test
    fun `inSampleSize 永远是2的幂`() {
        for (width in intArrayOf(1000, 1920, 3000, 4321, 8000, 9999)) {
            val sample = MediaLoadPolicy.sampleSizeFor(width, width, 1080)
            assertTrue("$width -> $sample", sample > 0 && (sample and (sample - 1)) == 0)
        }
    }

    // ── 磁盘缓存 key ────────────────────────────────────────────

    @Test
    fun `同一文件的正反斜杠路径得到同一个磁盘key`() {
        val a = MediaLoadPolicy.diskKey(null, """C:\Users\me\a.png""", null, null)
        val b = MediaLoadPolicy.diskKey(null, "C:/Users/me/a.png", null, null)
        assertEquals(a, b)
        assertEquals(40, a.length) // SHA-1 十六进制
    }

    @Test
    fun `路径优先于URL`() {
        val byPath = MediaLoadPolicy.diskKey(null, "/w/a.png", "/api/sessions/s/entries/e/tool-result-image?blockIndex=0", null)
        assertEquals(MediaLoadPolicy.diskKey(null, "/w/a.png", null, null), byPath)
    }

    @Test
    fun `工具附件按 toolCallId 分版本_同名文件重写后换缓存`() {
        val first = MediaLoadPolicy.toolIdPrefix("call_1")!!
        val second = MediaLoadPolicy.toolIdPrefix("call_2")!!
        val k1 = MediaLoadPolicy.diskKey("$first#img0", "/w/zoom_plan1.png", null, null)
        val k1Again = MediaLoadPolicy.diskKey("$first#img0", "/w/zoom_plan1.png", null, null)
        val k2 = MediaLoadPolicy.diskKey("$second#img0", "/w/zoom_plan1.png", null, null)
        assertEquals("同一次工具调用必须稳定命中", k1, k1Again)
        assertTrue("文件被重写后再读必须换 key", k1 != k2)
    }

    @Test
    fun `非工具附件的随机 id 不影响磁盘key`() {
        val randomA = MediaLoadPolicy.diskKey(java.util.UUID.randomUUID().toString(), "/w/a.png", null, null)
        val randomB = MediaLoadPolicy.diskKey(java.util.UUID.randomUUID().toString(), "/w/a.png", null, null)
        assertEquals("随机 id 不能让缓存失效", randomA, randomB)
    }

    @Test
    fun `内联图按内容寻址`() {
        val b64 = "iVBORw0KGgo" + "A".repeat(200)
        val byB64 = MediaLoadPolicy.diskKey(null, null, null, b64)
        assertEquals(
            "同一张内联图必须稳定命中",
            byB64,
            MediaLoadPolicy.diskKey(java.util.UUID.randomUUID().toString(), null, null, b64)
        )
        assertTrue(byB64.isNotBlank())
        // 内容不同 → key 不同（同长度、靠前的前缀差异）
        assertTrue(byB64 != MediaLoadPolicy.diskKey(null, null, null, "X" + b64.drop(1)))
    }

    @Test
    fun `没有任何定位信息时不落盘`() {
        assertEquals("", MediaLoadPolicy.diskKey(null, null, null, null))
        assertEquals("", MediaLoadPolicy.diskKey(null, "   ", "", "  "))
        assertEquals("", MediaLoadPolicy.diskKey(null, "data:image/png;base64,AAAA", null, null))
    }

    // ── 延迟图 URL ──────────────────────────────────────────────

    @Test
    fun `相对延迟图URL拼上网关地址`() {
        val url = "/api/sessions/s1/entries/e1/tool-result-image?blockIndex=0"
        assertEquals(
            "http://192.168.1.5:3080/api/sessions/s1/entries/e1/tool-result-image?blockIndex=0",
            MediaLoadPolicy.absoluteUrl("http://192.168.1.5:3080/", url)
        )
    }

    @Test
    fun `绝对URL原样返回`() {
        assertEquals(
            "https://gw.example.com/api/files/raw?path=x",
            MediaLoadPolicy.absoluteUrl("http://192.168.1.5:3080", "https://gw.example.com/api/files/raw?path=x")
        )
    }

    @Test
    fun `网关地址为空或入参为空时不臆造URL`() {
        assertNull(MediaLoadPolicy.absoluteUrl("", "/api/x"))
        assertNull(MediaLoadPolicy.absoluteUrl("   ", "/api/x"))
        assertNull(MediaLoadPolicy.absoluteUrl("http://h:1", "  "))
        assertNotNull(MediaLoadPolicy.absoluteUrl("http://h:1", "api/x"))
        assertEquals("http://h:1/api/x", MediaLoadPolicy.absoluteUrl("http://h:1", "api/x"))
    }
}
