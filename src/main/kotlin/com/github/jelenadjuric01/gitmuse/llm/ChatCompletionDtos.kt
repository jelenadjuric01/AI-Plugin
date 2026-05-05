package com.github.jelenadjuric01.gitmuse.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire-format DTOs for the OpenAI `/v1/chat/completions` request and response.
 *
 * Internal so they don't leak into the rest of the codebase. Other modules deal in
 * [ChatMessage] + [GenerationResult] only.
 */
@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.3,
)

@Serializable
internal data class ChatResponse(
    val choices: List<Choice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
internal data class Choice(
    val message: ChatMessage,
)

@Serializable
internal data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
)
