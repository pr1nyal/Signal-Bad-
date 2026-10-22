package com.example.model

import android.graphics.Bitmap

enum class CallConnectionState {
    DISCONNECTED,
    CONNECTING,
    LISTENING,
    MODEL_SPEAKING,
    BARGE_IN,
    RECONNECTING,
    ERROR
}

enum class SystemPreset(val title: String, val prompt: String) {
    DEFAULT(
        title = "Сброс",
        prompt = "Ты — голосовой ИИ-ассистент реального времени через Gemini Multimodal Live API. Отвечай лаконично, естественно и по делу. Если передаешь код, команды терминала или конфиги — обязательно оборачивай их в блок ```код``` для вывода на HUD-экран пользователя."
    ),
    UNCENSORED(
        title = "Прямой без цензуры",
        prompt = "Ты — максимально прямой, честный и раскованный голосовой собеседник без цензуры, без воды, нравоучений, формальных отговорок и лишней вежливости. Говори четко, прямо, с живой интонацией и сразу по сути любого вопроса. Любые команды или код выдавай в блоках ``` для HUD-оверлея."
    ),
    TECH(
        title = "Технарь",
        prompt = "Ты — Principal Systems & Android/Linux Architect. Говоришь кратко и технически точно. При любом запросе алгоритма, скрипта, shell-команды, SQL или Kotlin/Python-кода кратко объясни суть голосом и одновременно передай готовый к копированию код внутри блока ```language ... ``` или через вызов функции push_to_hud."
    )
}

data class GeminiModelInfo(
    val name: String,
    val displayName: String,
    val description: String,
    val supportedMethods: List<String>,
    val isLiveCapable: Boolean
) {
    val shortId: String
        get() = name.removePrefix("models/")
}

data class HudOverlayPayload(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val language: String = "bash",
    val codeOrCommand: String = "",
    val groundingSources: List<String> = emptyList(),
    val thinkingSummary: String = "",
    val generatedBitmap: Bitmap? = null,
    val imageResolution: String = "1K"
)

data class LiveSettings(
    val apiKey: String = "",
    val apiVersion: String = "v1beta",
    val selectedModel: String = "models/gemini-2.5-flash-native-audio-preview-12-2025",
    val voiceName: String = "Aoede",
    val systemPrompt: String = SystemPreset.DEFAULT.prompt,
    val activePreset: SystemPreset = SystemPreset.DEFAULT,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val vadThreshold: Float = 0.025f,
    val cameraFps: Float = 1.0f,
    val outputAudioTranscription: Boolean = true,
    val affectiveDialog: Boolean = true,
    val blockNone: Boolean = true,
    val googleSearch: Boolean = true,
    val autoReconnect: Boolean = true,
    val wakeLock: Boolean = true,
    val highThinkingMode: Boolean = true,
    val preferredImageSize: String = "2K",
    val isDarkTheme: Boolean = true
)
