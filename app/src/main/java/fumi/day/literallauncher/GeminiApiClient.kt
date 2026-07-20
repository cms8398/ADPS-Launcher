package fumi.day.literallauncher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

internal sealed interface GeminiResult {
    data class Success(val text: String) : GeminiResult
    data class Error(val message: String) : GeminiResult
}

internal object GeminiApiClient {
    private const val MODEL_NAME = "gemini-3.1-flash-lite"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 60_000

    fun generateAnswer(apiKey: String, userPrompt: String): GeminiResult {
        if (apiKey.isBlank()) {
            return GeminiResult.Error(
                "Gemini API Key가 설정되지 않았습니다. local.properties를 확인해 주세요.",
            )
        }

        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "$MODEL_NAME:generateContent"

        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey)

            val requestBody = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        buildPrompt(userPrompt),
                                    ),
                                ),
                            )
                        },
                    ),
                )
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.7)
                        put("maxOutputTokens", 1_024)
                    },
                )
            }

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    .orEmpty()
            }

            if (responseCode !in 200..299) {
                return GeminiResult.Error(
                    createHttpErrorMessage(responseCode, responseBody),
                )
            }

            parseResponse(responseBody)
        } catch (error: Exception) {
            GeminiResult.Error(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Gemini 서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildPrompt(userPrompt: String): String {
        return """
            You are an AI assistant built into an ADPS Android display launcher.
            Respond in the same language as the user.
            Keep the answer clear and reasonably concise unless the user requests detail.

            User question:
            ${userPrompt.trim()}
        """.trimIndent()
    }

    private fun parseResponse(responseBody: String): GeminiResult {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates")
            ?: return GeminiResult.Error("Gemini 응답에 답변 후보가 없습니다.")

        if (candidates.length() == 0) {
            return GeminiResult.Error("Gemini가 답변을 생성하지 못했습니다.")
        }

        val content = candidates
            .optJSONObject(0)
            ?.optJSONObject("content")
            ?: return GeminiResult.Error("Gemini 응답 형식을 해석할 수 없습니다.")

        val parts = content.optJSONArray("parts")
            ?: return GeminiResult.Error("Gemini 응답에 텍스트가 없습니다.")

        val answer = buildString {
            for (index in 0 until parts.length()) {
                val text = parts.optJSONObject(index)?.optString("text").orEmpty()
                if (text.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(text)
                }
            }
        }.trim()

        return if (answer.isNotEmpty()) {
            GeminiResult.Success(answer)
        } else {
            GeminiResult.Error("Gemini가 빈 답변을 반환했습니다.")
        }
    }

    private fun createHttpErrorMessage(responseCode: Int, responseBody: String): String {
        val apiMessage = runCatching {
            JSONObject(responseBody)
                .optJSONObject("error")
                ?.optString("message")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        val baseMessage = when (responseCode) {
            400 -> "요청 형식 또는 API Key 설정을 확인해 주세요."
            401, 403 -> "Gemini API 인증에 실패했습니다. API Key와 프로젝트 권한을 확인해 주세요."
            404 -> "설정된 Gemini 모델을 사용할 수 없습니다."
            429 -> "Gemini 무료 사용 한도 또는 요청 속도 제한을 초과했습니다. 잠시 후 다시 시도해 주세요."
            in 500..599 -> "Gemini 서버에서 일시적인 오류가 발생했습니다."
            else -> "Gemini 요청에 실패했습니다. HTTP $responseCode"
        }

        return if (apiMessage.isNullOrBlank()) {
            baseMessage
        } else {
            "$baseMessage\n$apiMessage"
        }
    }
}
