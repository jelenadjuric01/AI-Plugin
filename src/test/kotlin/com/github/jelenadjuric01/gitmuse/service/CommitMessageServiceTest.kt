package com.github.jelenadjuric01.gitmuse.service

import com.github.jelenadjuric01.gitmuse.llm.LlmError
import com.github.jelenadjuric01.gitmuse.llm.LlmException
import com.github.jelenadjuric01.gitmuse.testutil.FakeLlmClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the orchestration seam — [CommitMessageService.generateWith].
 *
 * The IDE-bound `generate()` is exercised through the manual checklist in `runIde`;
 * unit-testing it would require a `Project` fixture for marginal value, since all the
 * interesting logic (prompt building, LLM round-trip, error mapping) lives in the
 * components this seam wires together.
 */
class CommitMessageServiceTest {

    @Test
    fun `success result from the client flows through unchanged`() {
        val client = FakeLlmClient().enqueueSuccess("feat: add foo", tokens = 42)

        val result = CommitMessageService.generateWith(
            client = client,
            diff = "+ added line",
            branch = null,
        )

        assertTrue(result.isSuccess)
        assertEquals("feat: add foo", result.getOrNull()?.message)
        assertEquals(42, result.getOrNull()?.tokensUsed)
    }

    @Test
    fun `failure from the client flows through unchanged`() {
        val client = FakeLlmClient().enqueueFailure(LlmError.HttpError(401, "Invalid API key"))

        val result = CommitMessageService.generateWith(client, diff = "+ x", branch = null)

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.HttpError)
        assertEquals(401, (error as LlmError.HttpError).status)
    }

    @Test
    fun `prompt sent to client contains diff and branch`() {
        val client = FakeLlmClient().enqueueSuccess("ok")

        CommitMessageService.generateWith(
            client = client,
            diff = "+ DIFF_CONTENT_SENTINEL",
            branch = "feat/branch-name-sentinel",
        )

        val sent = client.seen.single()
        assertEquals(2, sent.size)
        assertEquals("system", sent[0].role)
        assertEquals("user", sent[1].role)
        assertTrue(sent[1].content.contains("DIFF_CONTENT_SENTINEL"))
        assertTrue(sent[1].content.contains("feat/branch-name-sentinel"))
    }

    @Test
    fun `null branch produces a user message without a branch line`() {
        val client = FakeLlmClient().enqueueSuccess("ok")

        CommitMessageService.generateWith(client, diff = "+ x", branch = null)

        val userContent = client.seen.single()[1].content
        assertTrue(!userContent.contains("Current branch"))
    }
}
