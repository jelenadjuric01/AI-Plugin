package com.github.jelenadjuric01.gitmuse.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTest {

    @Test
    fun `system prompt mentions Conventional Commits`() {
        assertTrue(Prompt.SYSTEM.contains("Conventional Commits"))
    }

    @Test
    fun `system prompt explicitly forbids code fences`() {
        assertTrue(
            "system prompt should ban code fences",
            Prompt.SYSTEM.contains("code fences", ignoreCase = true),
        )
    }

    @Test
    fun `system prompt explicitly forbids surrounding prose`() {
        assertTrue(Prompt.SYSTEM.contains("apology", ignoreCase = true) ||
            Prompt.SYSTEM.contains("explanation", ignoreCase = true))
    }

    @Test
    fun `build returns system message followed by user message`() {
        val msgs = Prompt.build(diff = "+ added line", branch = null)
        assertEquals(2, msgs.size)
        assertEquals("system", msgs[0].role)
        assertEquals(Prompt.SYSTEM, msgs[0].content)
        assertEquals("user", msgs[1].role)
    }

    @Test
    fun `build embeds the diff verbatim in the user message`() {
        val msgs = Prompt.build(diff = "DIFF_SENTINEL_4F2A", branch = null)
        assertTrue(msgs[1].content.contains("DIFF_SENTINEL_4F2A"))
    }

    @Test
    fun `build includes branch name when present`() {
        val msgs = Prompt.build(diff = "x", branch = "feat/auth-refresh")
        assertTrue(msgs[1].content.contains("feat/auth-refresh"))
    }

    @Test
    fun `build omits branch label when branch is null`() {
        val msgs = Prompt.build(diff = "x", branch = null)
        assertFalse(msgs[1].content.contains("Current branch"))
    }

    @Test
    fun `build omits branch label when branch is blank`() {
        val msgs = Prompt.build(diff = "x", branch = "   ")
        assertFalse(msgs[1].content.contains("Current branch"))
    }
}
