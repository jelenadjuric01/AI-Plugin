package com.github.jelenadjuric01.gitmuse.llm

/**
 * Categorized failure modes for [LlmClient].
 *
 * Each variant carries only what's safe to surface to the user — never headers, never the API key.
 * The action layer maps these to user-facing notification text.
 */
sealed interface LlmError {

    /** No API key configured (or key is blank). The client never reached the network. */
    data object MissingApiKey : LlmError

    /** No staged changes to summarize — emitted by the orchestration layer, not the HTTP client. */
    data object NoChanges : LlmError

    /** The request timed out (connect or read). */
    data object Timeout : LlmError

    /**
     * Network-layer failure (DNS, connection refused, TLS error, etc.).
     *
     * @param description sanitized one-line summary — exception class + message only,
     *                    never headers or the request body.
     */
    data class Network(val description: String) : LlmError

    /**
     * Non-2xx HTTP response from the provider.
     *
     * @param status     HTTP status code.
     * @param bodySnippet up to 500 chars of the response body. Provider error responses
     *                   ("Invalid API key", "Model not found", ...) typically don't echo
     *                   the request, so this is safe to display.
     */
    data class HttpError(val status: Int, val bodySnippet: String) : LlmError

    /**
     * 2xx response that we couldn't parse, or that didn't contain usable content.
     *
     * @param detail what's wrong, in plain English. Never includes the raw body.
     */
    data class InvalidResponse(val detail: String) : LlmError
}

/**
 * Throwable wrapper so [LlmError] can ride inside a Kotlin [Result.failure].
 *
 * The `message` is just the error's `toString()` — deterministic, no PII.
 */
class LlmException(val error: LlmError) : RuntimeException(error.toString())
