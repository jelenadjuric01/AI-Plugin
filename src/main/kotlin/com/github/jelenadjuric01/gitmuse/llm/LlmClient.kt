package com.github.jelenadjuric01.gitmuse.llm

/**
 * Boundary between the orchestration layer and "an OpenAI-compatible chat-completion endpoint".
 *
 * Implementations must be safe to call from a background task — synchronous, blocking is fine.
 * Cancellation is the caller's responsibility (typically via `ProgressManager.checkCanceled()`).
 *
 * Tests substitute a fake to avoid hitting the network — see `FakeLlmClient` in the test sources.
 */
interface LlmClient {
    /**
     * @return [Result.success] with a [GenerationResult], or [Result.failure] wrapping a
     *         [LlmException] whose `error` carries the categorized failure mode.
     */
    fun generate(messages: List<ChatMessage>): Result<GenerationResult>
}
