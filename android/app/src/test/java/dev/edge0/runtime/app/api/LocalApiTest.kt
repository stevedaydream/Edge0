// JVM tests for the local API: request parsing, routing/auth, and real-socket SSE framing.
package dev.edge0.runtime.app.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LocalApiTest {

    // ---- codec ----------------------------------------------------------------------------

    @Test fun mergesSystemTurnsAndFlattensParts() {
        val r = OpenAiCodec.parseChatRequest("""
            {"model":"edge0-8b","stream":true,"max_tokens":64,"temperature":5,
             "chat_template_kwargs":{"enable_thinking":true},
             "messages":[{"role":"system","content":"A"},
                         {"role":"developer","content":"B"},
                         {"role":"user","content":[{"type":"text","text":"hi "},{"type":"text","text":"there"}]}]}
        """.trimIndent())
        assertEquals(listOf(ApiMessage("system", "A\n\nB"), ApiMessage("user", "hi there")), r.messages)
        assertTrue(r.stream); assertEquals(64, r.maxTokens); assertEquals(2f, r.temperature)
        assertEquals(true, r.enableThinking); assertEquals("edge0-8b", r.model)
    }

    @Test fun defaultsAreNullSoAppSettingsApply() {
        val r = OpenAiCodec.parseChatRequest("""{"messages":[{"role":"user","content":"x"}]}""")
        assertFalse(r.stream); assertNull(r.temperature); assertNull(r.maxTokens); assertNull(r.enableThinking)
    }

    @Test(expected = ApiBadRequest::class) fun lastTurnMustBeUser() {
        OpenAiCodec.parseChatRequest("""{"messages":[{"role":"user","content":"a"},{"role":"assistant","content":"b"}]}""")
    }

    @Test(expected = ApiBadRequest::class) fun rejectsImageParts() {
        OpenAiCodec.parseChatRequest("""{"messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"x"}}]}]}""")
    }

    // ---- over a real loopback socket ------------------------------------------------------

    private class FakeBackend : ChatBackend {
        var mode = "ok"
        var lastReq: ApiChatRequest? = null
        override suspend fun modelIds() = listOf("edge0-8b", "edge0-35b")
        override fun chat(req: ApiChatRequest): Flow<BackendEvent> = flow {
            lastReq = req
            when (mode) {
                "busy" -> emit(BackendEvent.Busy("slot busy"))
                "nomodel" -> emit(BackendEvent.NoModel("nope", unknown = true))
                else -> {
                    emit(BackendEvent.Started("edge0-8b"))
                    emit(BackendEvent.Think("plan"))
                    emit(BackendEvent.Text("Hel")); emit(BackendEvent.Text("lo"))
                    emit(BackendEvent.Done(OpenAiCodec.Usage(5, 2), hitLimit = false))
                }
            }
        }
    }

    private val backend = FakeBackend()
    private var token: String? = null
    private lateinit var server: LocalHttpServer

    @Before fun up() {
        val router = ApiRouter(backend, { token }, clock = { 1700000000L })
        server = LocalHttpServer(0) { req, res -> router.handle(req, res) }
        server.start()
    }

    @After fun down() = server.stop()

    private data class Resp(val code: Int, val body: String, val headers: Map<String, List<String>>)

    private fun call(method: String, path: String, body: String? = null,
                     headers: Map<String, String> = emptyMap()): Resp {
        val c = URL("http://127.0.0.1:${server.boundPort}$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", headers["Content-Type"] ?: "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        return Resp(code, text, c.headerFields.filterKeys { it != null })
    }

    private val chatBody = """{"messages":[{"role":"user","content":"hi"}]}"""

    @Test fun listsModels() {
        val r = call("GET", "/v1/models")
        assertEquals(200, r.code)
        val ids = Json.parseToJsonElement(r.body).jsonObject["data"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("edge0-8b", "edge0-35b"), ids)
    }

    @Test fun blockingCompletion() {
        val r = call("POST", "/v1/chat/completions", chatBody)
        assertEquals(200, r.code)
        val choice = Json.parseToJsonElement(r.body).jsonObject["choices"]!!.jsonArray[0].jsonObject
        val msg = choice["message"]!!.jsonObject
        assertEquals("Hello", msg["content"]!!.jsonPrimitive.content)
        assertEquals("plan", msg["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertTrue(r.body.contains("\"total_tokens\":7"))
    }

    @Test fun streamingIsValidSse() {
        val r = call("POST", "/v1/chat/completions",
            """{"stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(200, r.code)
        assertTrue(r.headers["Content-Type"]!!.first().startsWith("text/event-stream"))
        val events = r.body.split("\n\n").filter { it.isNotBlank() }.map { it.removePrefix("data: ") }
        assertEquals("[DONE]", events.last())
        val chunks = events.dropLast(1).map { Json.parseToJsonElement(it).jsonObject }
        val content = chunks.mapNotNull {
            it["choices"]!!.jsonArray.firstOrNull()?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.content
        }.joinToString("")
        assertEquals("Hello", content)
        assertTrue(chunks.last().containsKey("usage"))
    }

    @Test fun busyIs429BeforeHeaders() {
        backend.mode = "busy"
        assertEquals(429, call("POST", "/v1/chat/completions", chatBody).code)
        assertEquals(429, call("POST", "/v1/chat/completions",
            """{"stream":true,"messages":[{"role":"user","content":"hi"}]}""").code)
    }

    @Test fun unknownModelIs404() {
        backend.mode = "nomodel"
        assertEquals(404, call("POST", "/v1/chat/completions", chatBody).code)
    }

    @Test fun bearerTokenEnforcedWhenSet() {
        token = "e0-secret"
        assertEquals(401, call("GET", "/v1/models").code)
        assertEquals(200, call("GET", "/v1/models", headers = mapOf("Authorization" to "Bearer e0-secret")).code)
    }

    /** Raw request: HttpURLConnection refuses to set Origin/Host itself. */
    private fun rawStatus(request: String): Int =
        java.net.Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write(request.toByteArray()); s.getOutputStream().flush()
            BufferedReader(InputStreamReader(s.getInputStream())).readLine().split(' ')[1].toInt()
        }

    @Test fun browserOriginsAndForeignHostsRejected() {
        assertEquals(403, rawStatus("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
            "Origin: https://evil.example\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${chatBody.length}\r\n\r\n$chatBody"))
        assertEquals(403, rawStatus("GET /v1/models HTTP/1.1\r\nHost: evil.example:8080\r\n\r\n"))
        assertEquals(200, rawStatus("GET /v1/models HTTP/1.1\r\nHost: localhost:8080\r\n\r\n"))
    }

    @Test fun nonJsonPostRejected() {
        assertEquals(415, call("POST", "/v1/chat/completions", chatBody,
            mapOf("Content-Type" to "text/plain")).code)
    }

    @Test fun badJsonIs400AndUnknownRouteIs404() {
        assertEquals(400, call("POST", "/v1/chat/completions", "{nope").code)
        assertEquals(404, call("GET", "/v1/nothing").code)
    }
}
