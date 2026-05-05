package com.github.jelenadjuric01.gitmuse.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.time.Duration

class OpenAiCompatibleClientTest {

    private lateinit var server: HttpServer
    private var lastAuthHeader: String? = null
    private var lastRequestBody: String? = null

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        lastAuthHeader = null
        lastRequestBody = null
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun port(): Int = server.address.port
    private fun baseUrl(): String = "http://127.0.0.1:${port()}/v1"

    private fun stub(status: Int, body: String) {
        server.createContext("/v1/chat/completions", object : HttpHandler {
            override fun handle(exchange: HttpExchange) {
                lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
                lastRequestBody = exchange.requestBody.bufferedReader().readText()
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
                exchange.close()
            }
        })
    }

    @Test
    fun `200 response yields GenerationResult with content and tokens`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"feat: add foo"}}],"usage":{"total_tokens":42}}""")

        val result = OpenAiCompatibleClient(baseUrl(), "test-model", "sk-test", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        assertTrue(result.isSuccess)
        val gen = result.getOrNull()!!
        assertEquals("feat: add foo", gen.message)
        assertEquals(42, gen.tokensUsed)
    }

    @Test
    fun `assistant content is trimmed`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"  feat: x  "}}]}""")

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        assertEquals("feat: x", result.getOrNull()?.message)
    }

    @Test
    fun `Authorization Bearer header is set when key is present`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"x"}}]}""")

        OpenAiCompatibleClient(baseUrl(), "m", "sk-secret", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        assertEquals("Bearer sk-secret", lastAuthHeader)
    }

    @Test
    fun `Authorization header is omitted when apiKey is null`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"x"}}]}""")

        OpenAiCompatibleClient(baseUrl(), "m", apiKey = null, Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        assertNull(lastAuthHeader)
    }

    @Test
    fun `Authorization header is omitted when apiKey is blank`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"x"}}]}""")

        OpenAiCompatibleClient(baseUrl(), "m", apiKey = "   ", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        assertNull(lastAuthHeader)
    }

    @Test
    fun `request body carries model and messages`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"x"}}]}""")

        OpenAiCompatibleClient(baseUrl(), "test-model-name", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("system", "S"), ChatMessage("user", "U")))

        val body = lastRequestBody
        assertNotNull(body)
        assertTrue(body!!.contains("\"model\":\"test-model-name\""))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
    }

    @Test
    fun `401 response yields HttpError with status and bodySnippet`() {
        stub(401, """{"error":"Invalid API key"}""")

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-bad", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.HttpError)
        val http = error as LlmError.HttpError
        assertEquals(401, http.status)
        assertTrue(http.bodySnippet.contains("Invalid API key"))
    }

    @Test
    fun `5xx response yields HttpError`() {
        stub(503, "Service Unavailable")

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.HttpError)
        assertEquals(503, (error as LlmError.HttpError).status)
    }

    @Test
    fun `bodySnippet is capped at 500 characters`() {
        stub(500, "x".repeat(1000))

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val http = (result.exceptionOrNull() as LlmException).error as LlmError.HttpError
        assertEquals(500, http.bodySnippet.length)
    }

    @Test
    fun `malformed JSON yields InvalidResponse`() {
        stub(200, "this is not json at all")

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.InvalidResponse)
    }

    @Test
    fun `empty choices yields InvalidResponse`() {
        stub(200, """{"choices":[]}""")

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.InvalidResponse)
    }

    @Test
    fun `empty content string yields InvalidResponse`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":""}}]}""")

        val result = OpenAiCompatibleClient(baseUrl(), "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.InvalidResponse)
    }

    @Test
    fun `empty baseUrl yields InvalidResponse without making any HTTP call`() {
        // Note: no stub registered — if the client tried to make a call, the server would 404.
        val result = OpenAiCompatibleClient("", "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val error = (result.exceptionOrNull() as LlmException).error
        assertTrue(error is LlmError.InvalidResponse)
    }

    @Test
    fun `trailing slash in baseUrl is normalized`() {
        stub(200, """{"choices":[{"message":{"role":"assistant","content":"x"}}]}""")

        val result = OpenAiCompatibleClient("${baseUrl()}/", "m", "sk-x", Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        assertTrue(result.isSuccess)
    }

    @Test
    fun `API key never appears in error toString, exception message, or stack trace`() {
        val secret = "sk-must-not-leak-${System.nanoTime()}"
        stub(500, "Internal Server Error")

        val result = OpenAiCompatibleClient(baseUrl(), "m", secret, Duration.ofSeconds(5))
            .generate(listOf(ChatMessage("user", "hi")))

        val ex = result.exceptionOrNull() as LlmException

        // Error variant's toString must not contain the key.
        assertFalse(
            "API key leaked into error.toString()",
            ex.error.toString().contains(secret),
        )

        // Exception message must not contain the key.
        assertFalse(
            "API key leaked into exception.message",
            ex.message?.contains(secret) == true,
        )

        // Full stack trace must not contain the key.
        val stack = StringWriter().also { ex.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse("API key leaked into stack trace", stack.contains(secret))
    }
}
