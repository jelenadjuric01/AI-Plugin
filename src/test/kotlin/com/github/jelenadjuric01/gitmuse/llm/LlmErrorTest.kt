package com.github.jelenadjuric01.gitmuse.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmErrorTest {

    @Test
    fun `LlmException carries the error variant`() {
        val error = LlmError.HttpError(401, "Invalid API key")
        val ex = LlmException(error)
        assertSame(error, ex.error)
    }

    @Test
    fun `LlmException message includes the error toString`() {
        val ex = LlmException(LlmError.HttpError(404, "not found"))
        val msg = ex.message
        assertNotNull(msg)
        assertTrue(msg!!.contains("HttpError"))
        assertTrue(msg.contains("404"))
    }

    @Test
    fun `singleton variants are stable references`() {
        assertSame(LlmError.MissingApiKey, LlmError.MissingApiKey)
        assertSame(LlmError.NoChanges, LlmError.NoChanges)
        assertSame(LlmError.Timeout, LlmError.Timeout)
    }

    @Test
    fun `data class equality holds for HttpError`() {
        assertEquals(LlmError.HttpError(429, "rate"), LlmError.HttpError(429, "rate"))
    }

    @Test
    fun `sealed interface forces exhaustive when over all variants`() {
        // Smoke test: if a future variant is added without updating this when, the test fails to compile.
        val error: LlmError = LlmError.NoChanges
        val handled: String = when (error) {
            LlmError.MissingApiKey -> "missing"
            LlmError.NoChanges -> "none"
            LlmError.Timeout -> "timeout"
            is LlmError.Network -> "net"
            is LlmError.HttpError -> "http"
            is LlmError.InvalidResponse -> "invalid"
        }
        assertEquals("none", handled)
    }
}
