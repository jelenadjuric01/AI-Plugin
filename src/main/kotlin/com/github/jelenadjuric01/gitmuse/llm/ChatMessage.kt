package com.github.jelenadjuric01.gitmuse.llm

import kotlinx.serialization.Serializable

/**
 * One message in a chat-completion request.
 *
 * Reused as the wire DTO — the OpenAI `/v1/chat/completions` format names the same fields
 * (`role`, `content`), and every OpenAI-compatible provider (Groq, Ollama, OpenRouter, ...)
 * implements the same shape.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
)
