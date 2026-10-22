package com.example.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.model.GeminiModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GeminiModelScanner {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    val defaultLiveModels = listOf(
        GeminiModelInfo(
            name = "models/gemini-2.5-flash-native-audio-preview-12-2025",
            displayName = "Gemini 2.5 Flash Native Audio (Live)",
            description = "Native 24kHz PCM audio streaming & affective dialog",
            supportedMethods = listOf("bidiGenerateContent"),
            isLiveCapable = true
        ),
        GeminiModelInfo(
            name = "models/gemini-3.8-live",
            displayName = "Gemini 3.8 Live (Bidi Audio/Video)",
            description = "Ultra-low latency Multimodal Live API model",
            supportedMethods = listOf("bidiGenerateContent", "generateContent"),
            isLiveCapable = true
        ),
        GeminiModelInfo(
            name = "models/gemini-3.8-flash",
            displayName = "Gemini 3.8 Flash (Live + Search Grounding)",
            description = "Fast multimodal model with Google Search grounding",
            supportedMethods = listOf("bidiGenerateContent", "generateContent"),
            isLiveCapable = true
        ),
        GeminiModelInfo(
            name = "models/gemini-4-argon",
            displayName = "Gemini 4 Argon (High Thinking)",
            description = "Flagship reasoning engine with ThinkingLevel.HIGH",
            supportedMethods = listOf("bidiGenerateContent", "generateContent"),
            isLiveCapable = true
        )
    )

    /**
     * Dynamically scans available models from Google Generative Language API
     * and filters them by support for Live / bidiGenerateContent.
     */
    suspend fun fetchAvailableModels(
        apiKey: String,
        apiVersion: String = "v1beta"
    ): Result<List<GeminiModelInfo>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("API-ключ пуст. Укажите ключ в настройках или в панели Secrets."))
        }

        val cleanVersion = apiVersion.trim().ifEmpty { "v1beta" }
        val url = "https://generativelanguage.googleapis.com/$cleanVersion/models?pageSize=100&key=$apiKey"

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("HTTP ${response.code}: ${bodyStr.take(180)}")
                    )
                }

                val root = json.parseToJsonElement(bodyStr).jsonObject
                val modelsArray = root["models"]?.jsonArray ?: return@withContext Result.success(defaultLiveModels)

                val discovered = mutableListOf<GeminiModelInfo>()
                for (element in modelsArray) {
                    val obj = element.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val displayName = obj["displayName"]?.jsonPrimitive?.content ?: name.removePrefix("models/")
                    val description = obj["description"]?.jsonPrimitive?.content ?: ""
                    val methods = obj["supportedGenerationMethods"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()

                    val lowerName = name.lowercase()
                    val supportsBidi = methods.any {
                        it.equals("bidiGenerateContent", ignoreCase = true)
                    } || lowerName.contains("live") || lowerName.contains("native-audio")

                    if (supportsBidi) {
                        discovered.add(
                            GeminiModelInfo(
                                name = name,
                                displayName = displayName,
                                description = description,
                                supportedMethods = methods,
                                isLiveCapable = true
                            )
                        )
                    }
                }

                // Put real server-discovered bidiGenerateContent models first, followed by defaults
                val mergedMap = linkedMapOf<String, GeminiModelInfo>()
                for (m in discovered) {
                    mergedMap[m.name] = m
                }
                for (m in defaultLiveModels) {
                    if (!mergedMap.containsKey(m.name)) {
                        mergedMap[m.name] = m
                    }
                }

                Result.success(mergedMap.values.toList())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uses gemini-3.8-flash with googleSearch tool for real-time Search Grounding
     * and returns verified facts + source URLs for the HUD overlay.
     */
    suspend fun executeGroundedSearch(
        apiKey: String,
        query: String
    ): Pair<String, List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "API-ключ не задан" to emptyList()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey"

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", query)
                        }
                    }
                }
            }
            putJsonArray("tools") {
                addJsonObject {
                    putJsonObject("googleSearch") {}
                }
            }
        }

        try {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext "Ошибка поиска (${resp.code})" to emptyList()

                val root = json.parseToJsonElement(raw).jsonObject
                val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                val text = candidate?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                    ?.joinToString("\n")
                    .orEmpty()

                val urls = mutableListOf<String>()
                val chunks = candidate?.get("groundingMetadata")?.jsonObject
                    ?.get("groundingChunks")?.jsonArray
                chunks?.forEach { chunkEl ->
                    val web = chunkEl.jsonObject["web"]?.jsonObject
                    val uri = web?.get("uri")?.jsonPrimitive?.content
                    val title = web?.get("title")?.jsonPrimitive?.content
                    if (!uri.isNullOrBlank()) {
                        urls.add(if (!title.isNullOrBlank()) "$title — $uri" else uri)
                    }
                }
                text.ifBlank { "Данные получены через Google Search Grounding" } to urls
            }
        } catch (e: Exception) {
            "Ошибка Google Search: ${e.localizedMessage}" to emptyList()
        }
    }

    /**
     * Uses gemini-4-argon with thinkingLevel = "HIGH" (ThinkingLevel.HIGH) and NO maxOutputTokens
     * to solve complex engineering/code queries and project the result onto the HUD overlay.
     */
    suspend fun executeHighThinkingCode(
        apiKey: String,
        taskDescription: String
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "// Укажите Gemini API Key в настройках" to "Нет ключа"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-argon:generateContent?key=$apiKey"

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put(
                                "text",
                                "Выполни глубокий инженерный анализ (ThinkingLevel.HIGH) и выдай только готовый к копированию код или команды терминала по задаче: $taskDescription"
                            )
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonObject("thinkingConfig") {
                    put("thinkingLevel", "HIGH")
                }
            }
        }

        try {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext "// HTTP ${resp.code}\n${raw.take(240)}" to "gemini-4-argon error"
                }
                val root = json.parseToJsonElement(raw).jsonObject
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray

                var thoughtText = ""
                var mainText = ""
                parts?.forEach { partEl ->
                    val partObj = partEl.jsonObject
                    val isThought = partObj["thought"]?.jsonPrimitive?.content == "true"
                    val t = partObj["text"]?.jsonPrimitive?.content.orEmpty()
                    if (isThought) {
                        thoughtText += t
                    } else {
                        mainText += t
                    }
                }
                val cleanedCode = extractCodeFence(mainText).ifBlank { mainText }
                cleanedCode to thoughtText.take(300)
            }
        } catch (e: Exception) {
            "// Ошибка gemini-4-argon: ${e.localizedMessage}" to ""
        }
    }

    /**
     * Generates a 1K, 2K, or 4K visual diagram/image for the HUD using gemini-3-pro-image-preview.
     */
    suspend fun generateHighResHudImage(
        apiKey: String,
        prompt: String,
        imageSize: String = "2K"
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null
        val normalizedSize = if (imageSize in listOf("1K", "2K", "4K")) imageSize else "1K"
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3-pro-image-preview:generateContent?key=$apiKey"

        val payload = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        addJsonObject {
                            put("text", prompt)
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                putJsonObject("imageConfig") {
                    put("aspectRatio", "16:9")
                    put("imageSize", normalizedSize)
                }
                putJsonArray("responseModalities") {
                    add("TEXT")
                    add("IMAGE")
                }
            }
        }

        try {
            val req = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext null
                val root = json.parseToJsonElement(raw).jsonObject
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray ?: return@withContext null

                for (partEl in parts) {
                    val inline = partEl.jsonObject["inlineData"]?.jsonObject ?: continue
                    val b64 = inline["data"]?.jsonPrimitive?.content ?: continue
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) return@withContext bmp
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun extractCodeFence(rawText: String): String {
        val regex = Regex("```(?:[a-zA-Z0-9_+-]+)?\\s*([\\s\\S]*?)```")
        val matches = regex.findAll(rawText).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        return if (matches.isNotEmpty()) matches.joinToString("\n\n") else ""
    }
}
