package com.example

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.example.data.SettingsRepository
import com.example.model.CallConnectionState
import com.example.model.HudOverlayPayload
import com.example.model.LiveSettings
import com.example.network.GeminiLiveWebSocketClient
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `verify app_name is Signal Bad`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Signal [Bad]", appName)
    }

    @Test
    fun `verify BidiGenerateContentSetup JSON has no invalid Protobuf fields that cause 1007`() {
        val client = GeminiLiveWebSocketClient(
            onStateChanged = { _, _ -> },
            onAudioPcm24kReceived = {},
            onServerInterrupted = {},
            onLiveCaptionUpdate = { _, _ -> },
            onHudPayloadReceived = {}
        )

        val settings = LiveSettings(
            selectedModel = "models/gemini-2.5-flash-native-audio-preview-12-2025",
            voiceName = "Aoede",
            blockNone = true,
            googleSearch = true,
            outputAudioTranscription = true
        )

        val payload = client.buildSetupPayloadJson(settings, minimalMode = false)
        val setupObj = payload["setup"]?.jsonObject
        assertNotNull("setup object must exist", setupObj)

        // Ensure `safetySettings` is NOT in `setup` (causes WebSocket 1007 in BidiGenerateContentSetup)
        assertFalse(
            "safetySettings is not a valid field in BidiGenerateContentSetup and causes 1007",
            setupObj!!.containsKey("safetySettings")
        )

        assertEquals(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            setupObj["model"]?.jsonPrimitive?.content
        )

        val genConfig = setupObj["generationConfig"]?.jsonObject
        assertNotNull(genConfig)
        val modalities = genConfig!!["responseModalities"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(listOf("AUDIO"), modalities)

        // Verify minimal fallback mode strips optional tools/transcription cleanly
        val minimalPayload = client.buildSetupPayloadJson(settings, minimalMode = true)
        val minimalSetup = minimalPayload["setup"]?.jsonObject!!
        assertFalse(minimalSetup.containsKey("tools"))
        assertFalse(minimalSetup.containsKey("outputAudioTranscription"))
    }

    @Test
    fun `verify WebSocket serverContent lifecycle audio decoding barge-in and HUD extraction`() {
        var latestState = CallConnectionState.DISCONNECTED
        var receivedAudioBytes: ByteArray? = null
        var interruptedCount = 0
        var latestCaption = ""
        var latestHud: HudOverlayPayload? = null

        val client = GeminiLiveWebSocketClient(
            onStateChanged = { st, _ -> latestState = st },
            onAudioPcm24kReceived = { bytes -> receivedAudioBytes = bytes },
            onServerInterrupted = { interruptedCount++ },
            onLiveCaptionUpdate = { text, _ -> latestCaption = text },
            onHudPayloadReceived = { hud -> latestHud = hud }
        )

        // 1. setupComplete transitions to LISTENING
        client.handleIncomingServerJson("""{"setupComplete": {}}""")
        assertTrue(client.isSetupCompleted)
        assertEquals(CallConnectionState.LISTENING, latestState)

        // 2. Incoming 24kHz PCM audio + code block in transcript projects to HUD and updates Live Captions
        val samplePcm = byteArrayOf(10, 20, 30, 40)
        val b64Pcm = Base64.encodeToString(samplePcm, Base64.NO_WRAP)
        val serverTurnJson = """
            {
              "serverContent": {
                "outputTranscription": {
                  "text": "Вот команда проверки: ```bash\nadb devices -l\n```"
                },
                "modelTurn": {
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "$b64Pcm"
                      }
                    }
                  ]
                },
                "turnComplete": true
              }
            }
        """.trimIndent()

        client.handleIncomingServerJson(serverTurnJson)
        assertNotNull(receivedAudioBytes)
        assertEquals(4, receivedAudioBytes!!.size)
        assertTrue(latestCaption.contains("Вот команда проверки"))
        assertNotNull(latestHud)
        assertEquals("adb devices -l", latestHud!!.codeOrCommand)
        assertEquals(CallConnectionState.LISTENING, latestState)

        // 3. Server interrupted flag triggers instant Barge-In
        client.handleIncomingServerJson("""{"serverContent": {"interrupted": true}}""")
        assertEquals(1, interruptedCount)
        assertEquals(CallConnectionState.BARGE_IN, latestState)
    }

    @Test
    fun `verify SettingsRepository migrates legacy model and persists settings`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SettingsRepository(context)
        repo.saveSettings(
            LiveSettings(
                apiKey = "test_key_123",
                selectedModel = "models/gemini-3.8-live",
                isDarkTheme = false
            )
        )
        val loaded = repo.loadSettings()
        assertEquals("test_key_123", loaded.apiKey)
        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", loaded.selectedModel)
        assertEquals(false, loaded.isDarkTheme)
    }
}
