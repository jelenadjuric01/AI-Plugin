package com.github.jelenadjuric01.gitmuse.testutil

import com.github.jelenadjuric01.gitmuse.llm.ChatMessage
import com.github.jelenadjuric01.gitmuse.llm.GenerationResult
import com.github.jelenadjuric01.gitmuse.llm.LlmClient
import com.github.jelenadjuric01.gitmuse.llm.LlmError
import com.github.jelenadjuric01.gitmuse.llm.LlmException

/**
 * Test double for [LlmClient]. Returns queued responses in order; records every prompt it saw.
 */
class FakeLlmClient : LlmClient {

    private val queue: ArrayDeque<Result<GenerationResult>> = ArrayDeque()

    /** Every messages-list this fake was asked to generate from, in order. */
    val seen: MutableList<List<ChatMessage>> = mutableListOf()

    fun enqueueSuccess(message: String, tokens: Int? = null): FakeLlmClient = apply {
        queue.addLast(Result.success(GenerationResult(message, tokens)))
    }

    fun enqueueFailure(error: LlmError): FakeLlmClient = apply {
        queue.addLast(Result.failure(LlmException(error)))
    }

    override fun generate(messages: List<ChatMessage>): Result<GenerationResult> {
        seen.add(messages)
        check(queue.isNotEmpty()) { "FakeLlmClient: no queued response left" }
        return queue.removeFirst()
    }
}
