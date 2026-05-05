package com.github.jelenadjuric01.gitmuse.llm

/**
 * Successful output from an [LlmClient].
 *
 * @param message    the assistant's reply, trimmed of leading/trailing whitespace.
 * @param tokensUsed total tokens reported by the provider, when available. Some
 *                   OpenAI-compatible servers (notably Ollama) omit this — `null` is fine.
 */
data class GenerationResult(
    val message: String,
    val tokensUsed: Int? = null,
)
