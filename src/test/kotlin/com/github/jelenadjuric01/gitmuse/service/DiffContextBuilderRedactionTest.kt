package com.github.jelenadjuric01.gitmuse.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the secret-redaction regex in [DiffContextBuilder].
 *
 * The full `build()` pipeline is exercised end-to-end via the manual `runIde` checklist
 * (it touches `ChangeListManager`, which is impractical to seed without a heavy
 * `BasePlatformTestCase` fixture for marginal extra signal beyond what these tests give).
 */
class DiffContextBuilderRedactionTest {

    @Test
    fun `redacts api_key equals assignment`() {
        val redacted = DiffContextBuilder.redactSecrets("api_key=AKIA0123456789")
        assertEquals("api_key=***REDACTED***", redacted)
    }

    @Test
    fun `redacts uppercase API_KEY with quoted value`() {
        val redacted = DiffContextBuilder.redactSecrets("""API_KEY: "secret-value-xyz"""")
        assertTrue(redacted.contains("***REDACTED***"))
        assertFalse(redacted.contains("secret-value-xyz"))
    }

    @Test
    fun `redacts password colon syntax`() {
        val redacted = DiffContextBuilder.redactSecrets("password: hunter2")
        assertTrue(redacted.contains("***REDACTED***"))
        assertFalse(redacted.contains("hunter2"))
    }

    @Test
    fun `redacts Authorization Bearer token`() {
        val redacted = DiffContextBuilder.redactSecrets("Authorization: Bearer eyJhbG.payload.sig")
        assertFalse(redacted.contains("eyJhbG.payload.sig"))
    }

    @Test
    fun `redacts client_secret`() {
        val redacted = DiffContextBuilder.redactSecrets("client_secret=abc123def")
        assertFalse(redacted.contains("abc123def"))
    }

    @Test
    fun `redaction preserves diff prefix character`() {
        val added = DiffContextBuilder.redactSecrets("+api_key=newSecret")
        assertEquals("+api_key=***REDACTED***", added)

        val removed = DiffContextBuilder.redactSecrets("-password: oldSecret")
        assertTrue(removed.startsWith("-password:"))
        assertFalse(removed.contains("oldSecret"))
    }

    @Test
    fun `non-secret content is not modified`() {
        val text = "+fun greet(name: String) = \"Hello, \$name\""
        assertEquals(text, DiffContextBuilder.redactSecrets(text))
    }

    @Test
    fun `multiple secrets in one input are all redacted`() {
        val input = """
            api_key=A_VALUE
            password: B_VALUE
            token = "C_VALUE"
        """.trimIndent()
        val redacted = DiffContextBuilder.redactSecrets(input)
        assertFalse(redacted.contains("A_VALUE"))
        assertFalse(redacted.contains("B_VALUE"))
        assertFalse(redacted.contains("C_VALUE"))
    }
}
