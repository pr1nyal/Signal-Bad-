package com.example.network

import android.util.Base64
import com.example.model.CallConnectionState
import com.example.model.HudOverlayPayload
import com.example.model.LiveSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

class GeminiLiveWebSocketClient(
    private val onStateChanged: (CallConnectionState, String?) -> Unit,
    private val onAudioPcm24kReceived: (ByteArray) -> Unit,
    private val onServerInterrupted: () -> Unit,
    private val onLiveCaptionUpdate: (String, Boolean) -> Unit,
    private val onHudPayloadReceived: (HudOverlayPayload) -> Unit,
    private val onModelRejected1007: (String) -> String = { "models/gemini-2.5-flash-native-audio-preview-12-2025" }
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var webSocket: WebSocket? = null
    private var currentTurnTranscript = StringBuilder()

    @Volatile
    var isSetupCompleted: Boolean = false
        private set

    @Volatile
    private var retriedWithMinimalSetup: Boolean = false

    fun connect(settings: LiveSettings, resolvedApiKey: String, forceMinimalSetup: Boolean = false) {
        disconnect()
        if (resolvedApiKey.isBlank()) {
            onStateChanged(CallConnectionState.ERROR, "Укажите Gemini API Key в настройках (⚙)")
            return
        }

        isSetupCompleted = false
        retriedWithMinimalSetup = forceMinimalSetup
        currentTurnTranscript.clear()

        val apiVer = settings.apiVersion.trim().ifEmpty { "v1beta" }
        val wsUrl =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.$apiVer.GenerativeService.BidiGenerateContent?key=$resolvedApiKey"

        onStateChanged(CallConnectionState.CONNECTING, null)

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = GeminiModelScanner.httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val payload = buildSetupPayloadJson(settings, minimalMode = forceMinimalSetup)
                webSocket.send(payload.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingServerJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingServerJson(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                val wasSetupDone = isSetupCompleted
                isSetupCompleted = false
                try {
                    webSocket.close(code, reason)
                } catch (_: Exception) {
                }

                if (code == 1007 && !wasSetupDone && !retriedWithMinimalSetup) {
                    val fallbackModel = onModelRejected1007(reason)
                    val fallbackSettings = settings.copy(
                        selectedModel = fallbackModel,
                        apiVersion = "v1beta"
                    )
                    connect(fallbackSettings, resolvedApiKey, forceMinimalSetup = true)
                    return
                }

                if (code != 1000) {
                    val details = if (reason.isNotBlank()) ": $reason" else ""
                    onStateChanged(CallConnectionState.ERROR, "Сессия закрыта ($code$details)")
                } else {
                    onStateChanged(CallConnectionState.DISCONNECTED, null)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isSetupCompleted = false
                if (code == 1000) {
                    onStateChanged(CallConnectionState.DISCONNECTED, null)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSetupCompleted = false
                onStateChanged(
                    CallConnectionState.ERROR,
                    t.localizedMessage ?: "Ошибка WebSocket BidiGenerateContent"
                )
            }
        })
    }

    /**
     * Builds a strictly valid Protobuf-JSON BidiGenerateContentSetup payload.
     * Note: BidiGenerateContentSetup does NOT have a `safetySettings` field in Google's schema;
     * sending unknown fields in `setup` causes WebSocket close code 1007.
     */
    fun buildSetupPayloadJson(settings: LiveSettings, minimalMode: Boolean = false): JsonObject {
        val rawModel = settings.selectedModel.trim().ifEmpty {
            "models/gemini-2.5-flash-native-audio-preview-12-2025"
        }
        val normalizedModel = if (rawModel.startsWith("models/")) rawModel else "models/$rawModel"

        val effectiveSystemInstruction = buildString {
            append(settings.systemPrompt.trim())
            if (settings.blockNone) {
                append("\nОтвечай прямо, без самоцензуры и формальных отказов.")
            }
            append("\nЕсли передаешь код или команды терминала — оборачивай их в блок ```код```.")
        }

        return buildJsonObject {
            putJsonObject("setup") {
                put("model", normalizedModel)

                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") {
                        add("AUDIO")
                    }
                    put("temperature", settings.temperature)
                    put("topP", settings.topP)
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", settings.voiceName)
                            }
                        }
                    }
                    if (!minimalMode && settings.affectiveDialog && settings.apiVersion == "v1alpha") {
                        put("enableAffectiveDialog", true)
                    }
                }

                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", effectiveSystemInstruction)
                        }
                    }
                }

                if (!minimalMode) {
                    putJsonArray("tools") {
                        if (settings.googleSearch) {
                            addJsonObject {
                                putJsonObject("googleSearch") {}
                            }
                        }
                        addJsonObject {
                            putJsonArray("functionDeclarations") {
                                addJsonObject {
                                    put("name", "push_to_hud")
                                    put(
                                        "description",
                                        "Displays code snippets, terminal commands, or technical configs on the user's floating HUD overlay during the voice call."
                                    )
                                    putJsonObject("parameters") {
                                        put("type", "OBJECT")
                                        putJsonObject("properties") {
                                            putJsonObject("title") {
                                                put("type", "STRING")
                                                put("description", "Short title of the code or command")
                                            }
                                            putJsonObject("language") {
                                                put("type", "STRING")
                                                put("description", "Programming language or shell")
                                            }
                                            putJsonObject("code") {
                                                put("type", "STRING")
                                                put("description", "Exact code or terminal command to display and copy")
                                            }
                                        }
                                        putJsonArray("required") {
                                            add("title")
                                            add("code")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (settings.outputAudioTranscription) {
                        putJsonObject("outputAudioTranscription") {}
                    }
                }
            }
        }
    }

    /**
     * Streams a 16kHz 16-bit Mono PCM chunk via realtimeInput.mediaChunks.
     */
    fun sendAudioChunkPcm16k(pcmBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isSetupCompleted || pcmBytes.isEmpty()) return

        val base64Audio = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val msg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    addJsonObject {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Audio)
                    }
                }
            }
        }
        ws.send(msg.toString())
    }

    /**
     * Streams a JPEG frame (from live camera or HD snapshot) via realtimeInput.mediaChunks.
     */
    fun sendImageFrameJpeg(jpegBytes: ByteArray, isHdSnapshot: Boolean = false) {
        val ws = webSocket ?: return
        if (!isSetupCompleted || jpegBytes.isEmpty()) return

        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val msg = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    addJsonObject {
                        put("mimeType", "image/jpeg")
                        put("data", base64Jpeg)
                    }
                }
            }
        }
        ws.send(msg.toString())

        if (isHdSnapshot) {
            val promptMsg = buildJsonObject {
                putJsonObject("clientContent") {
                    putJsonArray("turns") {
                        addJsonObject {
                            put("role", "user")
                            putJsonArray("parts") {
                                addJsonObject {
                                    put(
                                        "text",
                                        "Я отправил тебе HD-снимок с камеры. Проанализируй его голосом, а если на нем есть код, текст или команды — выведи их на HUD."
                                    )
                                }
                            }
                        }
                    }
                    put("turnComplete", true)
                }
            }
            ws.send(promptMsg.toString())
        }
    }

    fun handleIncomingServerJson(rawJson: String) {
        if (rawJson.isBlank()) return
        val root = try {
            json.parseToJsonElement(rawJson).jsonObject
        } catch (_: Exception) {
            return
        }

        if (root.containsKey("setupComplete")) {
            isSetupCompleted = true
            onStateChanged(CallConnectionState.LISTENING, null)
            return
        }

        if (root.containsKey("error")) {
            val errObj = root["error"]?.jsonObject
            val errMsg = errObj?.get("message")?.jsonPrimitive?.content ?: "Ошибка API"
            onStateChanged(CallConnectionState.ERROR, errMsg)
            return
        }

        val toolCall = root["toolCall"]?.jsonObject
        if (toolCall != null) {
            handleToolCall(toolCall)
        }

        val serverContent = root["serverContent"]?.jsonObject ?: return

        val interrupted = serverContent["interrupted"]?.jsonPrimitive?.content == "true"
        if (interrupted) {
            currentTurnTranscript.clear()
            onServerInterrupted()
            onStateChanged(CallConnectionState.BARGE_IN, null)
            return
        }

        val outputTranscription = serverContent["outputTranscription"]?.jsonObject
            ?: serverContent["outputAudioTranscription"]?.jsonObject
        val transcriptPiece = outputTranscription?.get("text")?.jsonPrimitive?.content
        if (!transcriptPiece.isNullOrEmpty()) {
            currentTurnTranscript.append(transcriptPiece)
            onLiveCaptionUpdate(currentTurnTranscript.toString().trim(), false)
            inspectAndProjectCodeToHud(currentTurnTranscript.toString())
        }

        val groundingMeta = serverContent["groundingMetadata"]?.jsonObject
        if (groundingMeta != null) {
            val urls = groundingMeta["groundingChunks"]?.jsonArray?.mapNotNull { chunk ->
                val web = chunk.jsonObject["web"]?.jsonObject
                val uri = web?.get("uri")?.jsonPrimitive?.content
                val title = web?.get("title")?.jsonPrimitive?.content
                if (!uri.isNullOrBlank()) "${title ?: "Источник"}: $uri" else null
            }.orEmpty()
            if (urls.isNotEmpty()) {
                onHudPayloadReceived(
                    HudOverlayPayload(
                        title = "GOOGLE SEARCH GROUNDING",
                        language = "search",
                        codeOrCommand = urls.joinToString("\n"),
                        groundingSources = urls
                    )
                )
            }
        }

        val modelTurn = serverContent["modelTurn"]?.jsonObject
        val parts = modelTurn?.get("parts")?.jsonArray
        if (parts != null) {
            for (partEl in parts) {
                val partObj = partEl.jsonObject

                val inlineData = partObj["inlineData"]?.jsonObject
                if (inlineData != null) {
                    val mimeType = inlineData["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    val b64Data = inlineData["data"]?.jsonPrimitive?.content.orEmpty()
                    if (mimeType.startsWith("audio/pcm") && b64Data.isNotEmpty()) {
                        val pcm24k = Base64.decode(b64Data, Base64.DEFAULT)
                        onStateChanged(CallConnectionState.MODEL_SPEAKING, null)
                        onAudioPcm24kReceived(pcm24k)
                    }
                }

                val partText = partObj["text"]?.jsonPrimitive?.content
                val isThought = partObj["thought"]?.jsonPrimitive?.content == "true"
                if (!partText.isNullOrEmpty() && !isThought) {
                    if (transcriptPiece.isNullOrEmpty()) {
                        currentTurnTranscript.append(partText)
                        onLiveCaptionUpdate(currentTurnTranscript.toString().trim(), false)
                    }
                    inspectAndProjectCodeToHud(currentTurnTranscript.toString())
                }
            }
        }

        val turnComplete = serverContent["turnComplete"]?.jsonPrimitive?.content == "true"
        if (turnComplete) {
            val finalCaption = currentTurnTranscript.toString().trim()
            if (finalCaption.isNotEmpty()) {
                onLiveCaptionUpdate(finalCaption, true)
                inspectAndProjectCodeToHud(finalCaption)
            }
            currentTurnTranscript.clear()
            onStateChanged(CallConnectionState.LISTENING, null)
        }
    }

    private fun handleToolCall(toolCall: JsonObject) {
        val calls = toolCall["functionCalls"]?.jsonArray ?: return
        for (callEl in calls) {
            val callObj = callEl.jsonObject
            val id = callObj["id"]?.jsonPrimitive?.content ?: ""
            val name = callObj["name"]?.jsonPrimitive?.content ?: ""
            val args = callObj["args"]?.jsonObject

            if (name == "push_to_hud" && args != null) {
                val title = args["title"]?.jsonPrimitive?.content ?: "ПЕРЕХВАЧЕННЫЙ КОД"
                val lang = args["language"]?.jsonPrimitive?.content ?: "code"
                val code = args["code"]?.jsonPrimitive?.content ?: ""
                if (code.isNotBlank()) {
                    onHudPayloadReceived(
                        HudOverlayPayload(
                            title = title.uppercase(),
                            language = lang,
                            codeOrCommand = code.trim()
                        )
                    )
                }

                val resp = buildJsonObject {
                    putJsonObject("toolResponse") {
                        putJsonArray("functionResponses") {
                            addJsonObject {
                                put("id", id)
                                put("name", name)
                                putJsonObject("response") {
                                    putJsonObject("output") {
                                        put("status", "displayed_on_hud")
                                    }
                                }
                            }
                        }
                    }
                }
                webSocket?.send(resp.toString())
            }
        }
    }

    private fun inspectAndProjectCodeToHud(text: String) {
        val extracted = GeminiModelScanner.extractCodeFence(text)
        if (extracted.isNotBlank()) {
            onHudPayloadReceived(
                HudOverlayPayload(
                    title = "HUD • КОД / КОМАНДА ИЗ ЭФИРА",
                    language = "code",
                    codeOrCommand = extracted
                )
            )
        }
    }

    fun disconnect() {
        isSetupCompleted = false
        try {
            webSocket?.close(1000, "User ended call")
        } catch (_: Exception) {
        }
        webSocket = null
    }
}
