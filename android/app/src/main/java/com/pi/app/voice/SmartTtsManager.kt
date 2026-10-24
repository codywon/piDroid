package com.pi.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class SmartTtsManager(context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 如果中文包缺失则回退默认
                tts?.language = Locale.getDefault()
            }
            isInitialized = true
        } else {
            Log.e("SmartTtsManager", "TTS initialization failed.")
        }
    }

    /**
     * 智能精炼播报：过滤代码块、JSON、终端命令和 Markdown 复杂标记，
     * 只提取人话结论进行朗读，避免长篇冗长代码轰炸耳朵。
     */
    fun speakSummary(rawText: String) {
        if (!isInitialized || tts == null) return

        val cleanSummary = extractSpokenSummary(rawText)
        if (cleanSummary.isNotBlank()) {
            tts?.stop()
            tts?.speak(cleanSummary, TextToSpeech.QUEUE_FLUSH, null, "pi_tts_${System.currentTimeMillis()}")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    /**
     * 过滤逻辑
     */
    private fun extractSpokenSummary(text: String): String {
        var processed = text

        // 1. 移除 ```...``` 代码块
        processed = processed.replace(Regex("```[\\s\\S]*?```"), " [代码块已省略] ")

        // 2. 移除 `inline code`
        processed = processed.replace(Regex("`[^`]+`"), "")

        // 3. 移除 Markdown 链接 [text](url) -> text
        processed = processed.replace(Regex("\\[([^\\]]+)\\]\\([^\\)]+\\)"), "$1")

        // 4. 移除 Markdown 标题符 (#, ##, *)
        processed = processed.replace(Regex("[#*_>~]"), "")

        // 5. 压缩多余空白
        processed = processed.replace(Regex("\\s+"), " ").trim()

        // 6. 如果文本过长（超过 300 字），提取前两句和末尾关键句
        if (processed.length > 300) {
            val sentences = processed.split(Regex("[。！？\n]")).filter { it.isNotBlank() }
            if (sentences.size > 3) {
                return "${sentences.take(2).joinToString("。")}。总结：${sentences.last()}。"
            }
        }

        return processed
    }
}
