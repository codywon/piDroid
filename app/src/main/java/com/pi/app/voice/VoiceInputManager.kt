package com.pi.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

sealed class VoiceInputState {
    object Idle : VoiceInputState()
    object Listening : VoiceInputState()
    data class PartialResult(val text: String) : VoiceInputState()
    data class FinalResult(val text: String) : VoiceInputState()
    data class Error(val message: String) : VoiceInputState()
}

class VoiceInputManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val _state = MutableStateFlow<VoiceInputState>(VoiceInputState.Idle)
    val state: StateFlow<VoiceInputState> = _state

    private var currentPartialText = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var silenceTimeoutRunnable: Runnable? = null

    /**
     * `onEndOfSpeech` 派发的延迟提交任务。
     *
     * 它以前是个匿名 Runnable，外部拿不到也取消不了：用户在 1 秒内取消并重新开始识别时，
     * 旧任务会把**新一轮**的部分文字当成上一轮结果提交。现在保存引用，并配合
     * [recognitionGeneration] 双重防护。
     */
    private var endOfSpeechCommitRunnable: Runnable? = null

    /** 识别轮次：旧轮次派发的延迟任务一律作废。 */
    private var recognitionGeneration = 0

    init {
        initRecognizer()
    }

    private fun initRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _state.value = VoiceInputState.Listening
                        resetSilenceTimer()
                    }

                    override fun onBeginningOfSpeech() {
                        resetSilenceTimer()
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        if (rmsdB > 2.0f) {
                            resetSilenceTimer()
                        }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        // 语音说话停顿，主动在 1 秒后自动提交
                        cancelEndOfSpeechCommit()
                        val generation = recognitionGeneration
                        val commit = Runnable {
                            endOfSpeechCommitRunnable = null
                            if (generation != recognitionGeneration) return@Runnable
                            if (currentPartialText.isNotBlank()) {
                                _state.value = VoiceInputState.FinalResult(currentPartialText)
                            }
                        }
                        endOfSpeechCommitRunnable = commit
                        mainHandler.postDelayed(commit, 1000)
                    }

                    override fun onError(error: Int) {
                        cancelSilenceTimer()
                        val message = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> {
                                if (currentPartialText.isNotBlank()) {
                                    _state.value = VoiceInputState.FinalResult(currentPartialText)
                                    return
                                }
                                "未检测到清晰语音"
                            }
                            SpeechRecognizer.ERROR_NETWORK -> "网络连接异常"
                            SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风录音权限"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                                if (currentPartialText.isNotBlank()) {
                                    _state.value = VoiceInputState.FinalResult(currentPartialText)
                                    return
                                }
                                "倾听超时"
                            }
                            else -> "语音识别提示 ($error)"
                        }
                        _state.value = VoiceInputState.Error(message)
                    }

                    override fun onResults(results: Bundle?) {
                        cancelSilenceTimer()
                        cancelEndOfSpeechCommit()
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: currentPartialText
                        currentPartialText = ""
                        _state.value = VoiceInputState.FinalResult(text)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull() ?: ""
                        if (text.isNotEmpty()) {
                            currentPartialText = text
                            _state.value = VoiceInputState.PartialResult(text)
                            resetSilenceTimer()
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun resetSilenceTimer() {
        silenceTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        silenceTimeoutRunnable = Runnable {
            if (currentPartialText.isNotBlank()) {
                stopListening()
            }
        }
        mainHandler.postDelayed(silenceTimeoutRunnable!!, 3000)
    }

    private fun cancelSilenceTimer() {
        silenceTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            silenceTimeoutRunnable = null
        }
    }

    private fun cancelEndOfSpeechCommit() {
        endOfSpeechCommitRunnable?.let {
            mainHandler.removeCallbacks(it)
            endOfSpeechCommitRunnable = null
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = VoiceInputState.Error("当前设备未内置系统语音识别服务")
            return
        }
        currentPartialText = ""
        cancelEndOfSpeechCommit()
        // 新一轮识别：旧轮次的延迟提交即使已入队也不能再生效
        recognitionGeneration += 1
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
            _state.value = VoiceInputState.Listening
            resetSilenceTimer()
        } catch (e: Exception) {
            _state.value = VoiceInputState.Error("启动语音输入失败: ${e.message}")
        }
    }

    /**
     * 用户再次点击语音按钮时：立即强行提交已有文字并结束倾听！
     */
    fun stopListening() {
        cancelSilenceTimer()
        cancelEndOfSpeechCommit()
        recognitionGeneration += 1
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}

        if (currentPartialText.isNotBlank()) {
            val textToSubmit = currentPartialText
            currentPartialText = ""
            _state.value = VoiceInputState.FinalResult(textToSubmit)
        } else {
            _state.value = VoiceInputState.Idle
        }
    }

    fun cancel() {
        cancelSilenceTimer()
        cancelEndOfSpeechCommit()
        recognitionGeneration += 1
        currentPartialText = ""
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        _state.value = VoiceInputState.Idle
    }

    fun destroy() {
        cancelSilenceTimer()
        cancelEndOfSpeechCommit()
        recognitionGeneration += 1
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }
}
