package com.example.viewmodel

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.PcmAudioEngine
import com.example.data.SettingsRepository
import com.example.model.CallConnectionState
import com.example.model.GeminiModelInfo
import com.example.model.HudOverlayPayload
import com.example.model.LiveSettings
import com.example.model.SystemPreset
import com.example.network.GeminiLiveWebSocketClient
import com.example.network.GeminiModelScanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class LiveCallViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private var wakeLock: PowerManager.WakeLock? = null

    private val _settings = MutableStateFlow(settingsRepo.loadSettings())
    val settings: StateFlow<LiveSettings> = _settings.asStateFlow()

    private val _availableModels = MutableStateFlow(GeminiModelScanner.defaultLiveModels)
    val availableModels: StateFlow<List<GeminiModelInfo>> = _availableModels.asStateFlow()

    private val _isScanningModels = MutableStateFlow(false)
    val isScanningModels: StateFlow<Boolean> = _isScanningModels.asStateFlow()

    private val _scanStatusMessage = MutableStateFlow<String?>(null)
    val scanStatusMessage: StateFlow<String?> = _scanStatusMessage.asStateFlow()

    private val _callState = MutableStateFlow(CallConnectionState.DISCONNECTED)
    val callState: StateFlow<CallConnectionState> = _callState.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    private val _liveCaption = MutableStateFlow("")
    val liveCaption: StateFlow<String> = _liveCaption.asStateFlow()

    private val _hudPayload = MutableStateFlow<HudOverlayPayload?>(null)
    val hudPayload: StateFlow<HudOverlayPayload?> = _hudPayload.asStateFlow()

    private val _isHudProcessing = MutableStateFlow(false)
    val isHudProcessing: StateFlow<Boolean> = _isHudProcessing.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isPttMode = MutableStateFlow(false)
    val isPttMode: StateFlow<Boolean> = _isPttMode.asStateFlow()

    private val _isPttPressed = MutableStateFlow(false)
    val isPttPressed: StateFlow<Boolean> = _isPttPressed.asStateFlow()

    private val _isCameraStreaming = MutableStateFlow(false)
    val isCameraStreaming: StateFlow<Boolean> = _isCameraStreaming.asStateFlow()

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    private var userInitiatedHangup = false
    private var reconnectJob: Job? = null
    private var timerJob: Job? = null
    private var lastCameraFrameSentAt = 0L

    @Volatile
    private var latestCameraFrame: Bitmap? = null

    lateinit var wsClient: GeminiLiveWebSocketClient
        private set

    val audioEngine: PcmAudioEngine = PcmAudioEngine(
        context = application.applicationContext,
        scope = viewModelScope,
        onPcmChunkCaptured = { pcm16k ->
            if (::wsClient.isInitialized) {
                wsClient.sendAudioChunkPcm16k(pcm16k)
            }
        },
        onLocalBargeInDetected = {
            if (_callState.value == CallConnectionState.MODEL_SPEAKING) {
                _callState.value = CallConnectionState.BARGE_IN
            }
        }
    )

    init {
        wsClient = GeminiLiveWebSocketClient(
            onStateChanged = { newState, errMessage ->
                _callState.value = newState
                if (!errMessage.isNullOrBlank()) {
                    _errorBanner.value = errMessage
                }
                if ((newState == CallConnectionState.ERROR || newState == CallConnectionState.DISCONNECTED) &&
                    !userInitiatedHangup &&
                    _settings.value.autoReconnect
                ) {
                    scheduleAutoReconnect()
                }
            },
            onAudioPcm24kReceived = { pcm24k ->
                audioEngine.enqueuePlaybackPcm24k(pcm24k)
            },
            onServerInterrupted = {
                audioEngine.flushPlayback()
            },
            onLiveCaptionUpdate = { captionText, _ ->
                _liveCaption.value = captionText
            },
            onHudPayloadReceived = { payload ->
                _hudPayload.value = payload
            },
            onModelRejected1007 = { _ ->
                val fallbackModel = _availableModels.value.firstOrNull()?.name
                    ?: "models/gemini-2.5-flash-native-audio-preview-12-2025"
                if (_settings.value.selectedModel != fallbackModel) {
                    updateSettings { it.copy(selectedModel = fallbackModel) }
                }
                fallbackModel
            }
        )
        syncEngineWithSettings(_settings.value)
        val initialKey = settingsRepo.resolveEffectiveApiKey(_settings.value)
        if (initialKey.isNotEmpty()) {
            triggerModelAutoScan()
        }
    }

    fun updateSettings(transform: (LiveSettings) -> LiveSettings) {
        _settings.update { current ->
            val updated = transform(current)
            settingsRepo.saveSettings(updated)
            syncEngineWithSettings(updated)
            updated
        }
    }

    fun applySystemPreset(preset: SystemPreset) {
        updateSettings {
            it.copy(
                activePreset = preset,
                systemPrompt = preset.prompt
            )
        }
    }

    private fun syncEngineWithSettings(s: LiveSettings) {
        audioEngine.vadThreshold = s.vadThreshold
        updateWakeLock(s.wakeLock && isCallActive())
    }

    fun triggerModelAutoScan() {
        val current = _settings.value
        val key = settingsRepo.resolveEffectiveApiKey(current)
        if (key.isBlank()) {
            _scanStatusMessage.value = "Введите API-ключ для онлайн-сканирования моделей"
            return
        }

        viewModelScope.launch {
            _isScanningModels.value = true
            _scanStatusMessage.value = "GET /${current.apiVersion}/models …"
            val result = GeminiModelScanner.fetchAvailableModels(key, current.apiVersion)
            _isScanningModels.value = false
            result.onSuccess { list ->
                _availableModels.value = list
                _scanStatusMessage.value = "Найдено Live-моделей: ${list.size}"
                val firstDiscovered = list.firstOrNull()
                if (firstDiscovered != null &&
                    (_settings.value.selectedModel == "models/gemini-3.8-live" ||
                        list.none { it.name == _settings.value.selectedModel })
                ) {
                    updateSettings { it.copy(selectedModel = firstDiscovered.name) }
                }
            }.onFailure { err ->
                _scanStatusMessage.value = "Ошибка автоскана: ${err.localizedMessage}"
            }
        }
    }

    fun toggleCall() {
        if (isCallActive()) {
            endCall()
        } else {
            startCall()
        }
    }

    fun startCall() {
        userInitiatedHangup = false
        reconnectJob?.cancel()
        _errorBanner.value = null

        val s = _settings.value
        val key = settingsRepo.resolveEffectiveApiKey(s)
        if (key.isBlank()) {
            _callState.value = CallConnectionState.ERROR
            _errorBanner.value = "API-ключ не задан! Откройте Настройки (⚙) или укажите GEMINI_API_KEY в Secrets."
            return
        }

        updateWakeLock(s.wakeLock)
        audioEngine.vadThreshold = s.vadThreshold
        audioEngine.isMuted = _isMuted.value
        audioEngine.isPttMode = _isPttMode.value
        audioEngine.isPttPressed = _isPttPressed.value
        audioEngine.start()

        wsClient.connect(s, key)
        startCallTimer()
    }

    fun endCall() {
        userInitiatedHangup = true
        reconnectJob?.cancel()
        timerJob?.cancel()
        _callDurationSeconds.value = 0L
        _isCameraStreaming.value = false
        latestCameraFrame = null
        audioEngine.stop()
        wsClient.disconnect()
        updateWakeLock(false)
        _callState.value = CallConnectionState.DISCONNECTED
    }

    private fun scheduleAutoReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            _callState.value = CallConnectionState.RECONNECTING
            delay(2500L)
            if (!userInitiatedHangup && _settings.value.autoReconnect) {
                val s = _settings.value
                val key = settingsRepo.resolveEffectiveApiKey(s)
                if (key.isNotBlank()) {
                    audioEngine.start()
                    wsClient.connect(s, key)
                }
            }
        }
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        _callDurationSeconds.value = 0L
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                if (isCallActive()) {
                    _callDurationSeconds.update { it + 1 }
                }
            }
        }
    }

    fun toggleMute() {
        val next = !_isMuted.value
        _isMuted.value = next
        audioEngine.isMuted = next
    }

    fun togglePttMode() {
        val next = !_isPttMode.value
        _isPttMode.value = next
        audioEngine.isPttMode = next
        if (!next) {
            _isPttPressed.value = false
            audioEngine.isPttPressed = false
        }
    }

    fun setPttPressed(pressed: Boolean) {
        val wasPressed = _isPttPressed.value
        _isPttPressed.value = pressed
        audioEngine.isPttPressed = pressed
        if (pressed) {
            audioEngine.flushPlayback()
        } else if (wasPressed) {
            // Send trailing silence frames on PTT release so Server VAD commits user turn immediately
            audioEngine.sendTrailingSilenceTail()
        }
    }

    fun toggleCameraStreaming() {
        val next = !_isCameraStreaming.value
        _isCameraStreaming.value = next
        if (!next) {
            latestCameraFrame = null
        }
    }

    /**
     * Called by CameraX frame analyzer to stream periodic frames according to cameraFps setting.
     */
    fun onLiveCameraFrame(bitmap: Bitmap) {
        latestCameraFrame = bitmap
        if (!_isCameraStreaming.value || !isCallActive()) return
        val now = System.currentTimeMillis()
        val intervalMs = (1000f / _settings.value.cameraFps.coerceIn(0.2f, 5.0f)).toLong()
        if (now - lastCameraFrameSentAt < intervalMs) return
        lastCameraFrameSentAt = now

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, out)
            wsClient.sendImageFrameJpeg(out.toByteArray(), isHdSnapshot = false)
        }
    }

    /**
     * Sends a high-definition JPEG snapshot immediately over the active WebSocket.
     * Uses the latest real camera frame if available, or the fallback snapshot provided.
     */
    fun sendHdSnapshot(fallbackBitmap: Bitmap) {
        val targetBitmap = latestCameraFrame ?: fallbackBitmap
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val out = ByteArrayOutputStream()
            targetBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            wsClient.sendImageFrameJpeg(out.toByteArray(), isHdSnapshot = true)
            _liveCaption.value = "📸 HD-снимок отправлен (${out.size() / 1024} КБ)"
        }
    }

    /**
     * Triggers High-Thinking (gemini-4-argon with ThinkingLevel.HIGH) on the current voice context
     * and projects the synthesized code/commands onto the Floating HUD Overlay.
     */
    fun runHighThinkingHudAnalysis() {
        val key = settingsRepo.resolveEffectiveApiKey(_settings.value)
        val contextTopic = _liveCaption.value.ifBlank {
            "Разработай оптимальный bash/kotlin diagnostic script для мониторинга аудио-потока и WebSocket BidiGenerateContent"
        }
        viewModelScope.launch {
            _isHudProcessing.value = true
            val (code, thought) = GeminiModelScanner.executeHighThinkingCode(key, contextTopic)
            _isHudProcessing.value = false
            _hudPayload.value = HudOverlayPayload(
                title = "GEMINI-4-ARGON • HIGH THINKING HUD",
                language = "kotlin/bash",
                codeOrCommand = code,
                thinkingSummary = thought
            )
        }
    }

    /**
     * Triggers Search Grounding (gemini-3.8-flash with googleSearch) on the current voice context
     * and displays verified facts & URLs on the Floating HUD Overlay.
     */
    fun runGroundedSearchHud() {
        val key = settingsRepo.resolveEffectiveApiKey(_settings.value)
        val query = _liveCaption.value.ifBlank {
            "Latest Google Gemini Multimodal Live API WebSocket BidiGenerateContent specifications"
        }
        viewModelScope.launch {
            _isHudProcessing.value = true
            val (summary, urls) = GeminiModelScanner.executeGroundedSearch(key, query)
            _isHudProcessing.value = false
            _hudPayload.value = HudOverlayPayload(
                title = "GEMINI-3.8-FLASH • GOOGLE SEARCH HUD",
                language = "grounding",
                codeOrCommand = summary,
                groundingSources = urls
            )
        }
    }

    /**
     * Generates a 1K, 2K, or 4K visual diagram in the HUD using gemini-3-pro-image-preview.
     */
    fun generateVisualHudArtifact(size: String = _settings.value.preferredImageSize) {
        updateSettings { it.copy(preferredImageSize = size) }
        val key = settingsRepo.resolveEffectiveApiKey(_settings.value)
        val prompt = _liveCaption.value.ifBlank {
            "Futuristic dark OLED cybernetic HUD schematic of a neural voice synthesizer sphere, high contrast cyan and violet telemetry"
        }
        viewModelScope.launch {
            _isHudProcessing.value = true
            val bmp = GeminiModelScanner.generateHighResHudImage(key, prompt, size)
            _isHudProcessing.value = false
            _hudPayload.value = HudOverlayPayload(
                title = "GEMINI-3-PRO-IMAGE • $size STUDIO HUD",
                language = "image/$size",
                codeOrCommand = if (bmp == null) "// Не удалось сгенерировать изображение ($size). Проверьте API-ключ." else "Схема сгенерирована в разрешении $size",
                generatedBitmap = bmp,
                imageResolution = size
            )
        }
    }

    fun dismissHud() {
        _hudPayload.value = null
    }

    fun copyHudCodeToClipboard(context: Context): Boolean {
        val text = _hudPayload.value?.codeOrCommand.orEmpty()
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Gemini Live HUD", text))
        return true
    }

    fun isCallActive(): Boolean {
        val st = _callState.value
        return st == CallConnectionState.CONNECTING ||
            st == CallConnectionState.LISTENING ||
            st == CallConnectionState.MODEL_SPEAKING ||
            st == CallConnectionState.BARGE_IN ||
            st == CallConnectionState.RECONNECTING
    }

    private fun updateWakeLock(enable: Boolean) {
        try {
            if (enable) {
                if (wakeLock == null) {
                    val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "GeminiLive::VoiceCallWakeLock"
                    ).apply { setReferenceCounted(false) }
                }
                if (wakeLock?.isHeld == false) {
                    wakeLock?.acquire(60 * 60 * 1000L)
                }
            } else {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        endCall()
    }
}
