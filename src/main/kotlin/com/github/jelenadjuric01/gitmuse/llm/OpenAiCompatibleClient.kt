package com.github.jelenadjuric01.gitmuse.llm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * [LlmClient] that speaks the OpenAI `/v1/chat/completions` wire format.
 *
 * Works with any provider that copies the same shape — OpenAI, Groq, Ollama, OpenRouter,
 * Together, etc. Uses the JDK 21 `HttpClient` so we don't pull in OkHttp (which would
 * conflict with the version IntelliJ bundles).
 *
 * The class is plain — no IntelliJ services touched, no settings looked up. Tests pass
 * a stub `HttpClient` via the constructor.
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String?,
    private val requestTimeout: Duration,
    private val httpClient: HttpClient = defaultHttpClient(requestTimeout),
) : LlmClient {

    override fun generate(messages: List<ChatMessage>): Result<GenerationResult> {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return failure(
                LlmError.InvalidResponse(
                    "Base URL is not configured. Open Settings → Tools → Git Muse."
                )
            )
        }
        val uri = runCatching { URI.create("$trimmed/chat/completions") }.getOrNull()
            ?: return failure(
                LlmError.InvalidResponse(
                    "Base URL is not a valid URI. Check Settings → Tools → Git Muse."
                )
            )
        if (uri.scheme != "http" && uri.scheme != "https") {
            return failure(
                LlmError.InvalidResponse(
                    "Base URL must start with http:// or https://. Check Settings → Tools → Git Muse."
                )
            )
        }

        val body = json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = model, messages = messages),
        )

        val builder = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))

        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        return try {
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            interpret(response)
        } catch (e: HttpTimeoutException) {
            failure(LlmError.Timeout)
        } catch (e: IOException) {
            // Sanitize: never include the request URI's authority or any header value.
            // Exception class name + message is plenty of context for the user.
            failure(LlmError.Network("${e.javaClass.simpleName}: ${e.message ?: "unknown"}"))
        }
    }

    private fun interpret(response: HttpResponse<String>): Result<GenerationResult> {
        val status = response.statusCode()
        val rawBody = response.body() ?: ""

        if (status !in 200..299) {
            return failure(LlmError.HttpError(status, rawBody.take(BODY_SNIPPET_MAX)))
        }

        val parsed = try {
            json.decodeFromString(ChatResponse.serializer(), rawBody)
        } catch (e: SerializationException) {
            return failure(LlmError.InvalidResponse("Malformed JSON: ${e.message ?: "unknown"}"))
        }

        val content = parsed.choices.firstOrNull()?.message?.content?.trim()
        return when {
            content == null -> failure(LlmError.InvalidResponse("Response had no choices."))
            content.isEmpty() -> failure(LlmError.InvalidResponse("Response message was empty."))
            else -> Result.success(GenerationResult(content, parsed.usage?.totalTokens))
        }
    }

    private fun <T> failure(error: LlmError): Result<T> =
        Result.failure(LlmException(error))

    companion object {
        private const val BODY_SNIPPET_MAX: Int = 500

        private val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        private fun defaultHttpClient(requestTimeout: Duration): HttpClient =
            HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10).coerceAtMost(requestTimeout))
                .build()
    }
}
