package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.model.LiveSettings
import com.example.model.SystemPreset

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("gemini_live_prefs_2026", Context.MODE_PRIVATE)

    fun loadSettings(): LiveSettings {
        val savedKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val presetName = prefs.getString(KEY_PRESET, SystemPreset.DEFAULT.name) ?: SystemPreset.DEFAULT.name
        val preset = runCatching { SystemPreset.valueOf(presetName) }.getOrDefault(SystemPreset.DEFAULT)

        val rawSavedModel = prefs.getString(
            KEY_SELECTED_MODEL,
            "models/gemini-2.5-flash-native-audio-preview-12-2025"
        ) ?: "models/gemini-2.5-flash-native-audio-preview-12-2025"
        val resolvedModel = if (rawSavedModel == "models/gemini-3.8-live" || rawSavedModel.isBlank()) {
            "models/gemini-2.5-flash-native-audio-preview-12-2025"
        } else {
            rawSavedModel
        }

        return LiveSettings(
            apiKey = savedKey,
            apiVersion = prefs.getString(KEY_API_VERSION, "v1beta") ?: "v1beta",
            selectedModel = resolvedModel,
            voiceName = prefs.getString(KEY_VOICE_NAME, "Aoede") ?: "Aoede",
            systemPrompt = prefs.getString(KEY_SYSTEM_PROMPT, preset.prompt) ?: preset.prompt,
            activePreset = preset,
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.7f),
            topP = prefs.getFloat(KEY_TOP_P, 0.95f),
            vadThreshold = prefs.getFloat(KEY_VAD_THRESHOLD, 0.025f),
            cameraFps = prefs.getFloat(KEY_CAMERA_FPS, 1.0f),
            outputAudioTranscription = prefs.getBoolean(KEY_OUTPUT_TRANSCRIPTION, true),
            affectiveDialog = prefs.getBoolean(KEY_AFFECTIVE_DIALOG, true),
            blockNone = prefs.getBoolean(KEY_BLOCK_NONE, true),
            googleSearch = prefs.getBoolean(KEY_GOOGLE_SEARCH, true),
            autoReconnect = prefs.getBoolean(KEY_AUTO_RECONNECT, true),
            wakeLock = prefs.getBoolean(KEY_WAKE_LOCK, true),
            highThinkingMode = prefs.getBoolean(KEY_HIGH_THINKING, true),
            preferredImageSize = prefs.getString(KEY_IMAGE_SIZE, "2K") ?: "2K",
            isDarkTheme = prefs.getBoolean(KEY_DARK_THEME, true)
        )
    }

    fun saveSettings(settings: LiveSettings) {
        prefs.edit().apply {
            putString(KEY_API_KEY, settings.apiKey.trim())
            putString(KEY_API_VERSION, settings.apiVersion)
            putString(KEY_SELECTED_MODEL, settings.selectedModel)
            putString(KEY_VOICE_NAME, settings.voiceName)
            putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
            putString(KEY_PRESET, settings.activePreset.name)
            putFloat(KEY_TEMPERATURE, settings.temperature)
            putFloat(KEY_TOP_P, settings.topP)
            putFloat(KEY_VAD_THRESHOLD, settings.vadThreshold)
            putFloat(KEY_CAMERA_FPS, settings.cameraFps)
            putBoolean(KEY_OUTPUT_TRANSCRIPTION, settings.outputAudioTranscription)
            putBoolean(KEY_AFFECTIVE_DIALOG, settings.affectiveDialog)
            putBoolean(KEY_BLOCK_NONE, settings.blockNone)
            putBoolean(KEY_GOOGLE_SEARCH, settings.googleSearch)
            putBoolean(KEY_AUTO_RECONNECT, settings.autoReconnect)
            putBoolean(KEY_WAKE_LOCK, settings.wakeLock)
            putBoolean(KEY_HIGH_THINKING, settings.highThinkingMode)
            putString(KEY_IMAGE_SIZE, settings.preferredImageSize)
            putBoolean(KEY_DARK_THEME, settings.isDarkTheme)
            apply()
        }
    }

    fun resolveEffectiveApiKey(settings: LiveSettings): String {
        val userKey = settings.apiKey.trim()
        return if (userKey.isNotEmpty() && userKey != "MY_GEMINI_API_KEY") userKey else ""
    }

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_VERSION = "api_version"
        private const val KEY_SELECTED_MODEL = "selected_model"
        private const val KEY_VOICE_NAME = "voice_name"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_PRESET = "active_preset"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_TOP_P = "top_p"
        private const val KEY_VAD_THRESHOLD = "vad_threshold"
        private const val KEY_CAMERA_FPS = "camera_fps"
        private const val KEY_OUTPUT_TRANSCRIPTION = "output_audio_transcription"
        private const val KEY_AFFECTIVE_DIALOG = "affective_dialog"
        private const val KEY_BLOCK_NONE = "block_none"
        private const val KEY_GOOGLE_SEARCH = "google_search"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_WAKE_LOCK = "wake_lock"
        private const val KEY_HIGH_THINKING = "high_thinking"
        private const val KEY_IMAGE_SIZE = "preferred_image_size"
        private const val KEY_DARK_THEME = "is_dark_theme"
    }
}
