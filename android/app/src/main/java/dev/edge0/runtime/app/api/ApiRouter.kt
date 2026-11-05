// api/ApiRouter.kt - OpenAI-compatible routes on top of an engine-neutral ChatBackend.
// Pure JVM (no android.*): routing, auth and SSE framing are unit-tested in src/test.
package dev.edge0.runtime.app.api

import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** What the router needs from the engine. The Android side adapts LlamaRuntime to this. */
interface ChatBackend {
    suspend fun modelIds(): List<String>
    /** Runs one turn. Emits pieces, then exactly one terminal event. Collection is serialized by the backend. */
    fun chat(req: ApiChatRequest): Flow<BackendEvent>
}

sealed interface BackendEvent {
    data class Started(val model: String) : BackendEvent
    data class Text(val s: String) : BackendEvent
    data class Think(val s: String) : BackendEvent
    data class Done(val usage: OpenAiCodec.Usage?, val hitLimit: Boolean) : BackendEvent
    /** Slot taken (e.g. the in-app chat is generating). Maps to 429. */
    data class Busy(val reason: String) : BackendEvent
    /** Unknown model / nothing loaded. Maps to 404 / 503. */
    data class NoModel(val reason: String, val unknown: Boolean) : BackendEvent
    data class Failed(val reason: String) : BackendEvent
}

class ApiRouter(
    private val backend: ChatBackend,
    /** Current bearer token; null/blank = no auth required (loopback-only either way). */
    private val token: () -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    suspend fun handle(req: HttpRequest, res: HttpResponder) {
        // A web page in the phone's browser can reach 127.0.0.1 too. Browsers always send
        // Origin on cross-origin POSTs; native clients (curl, SDKs, other apps) do not.
        if (req.headers.containsKey("origin"))
            return res.json(403, OpenAiCodec.error("browser origins are not allowed", "permission_error"))
        // DNS-rebinding guard: only loopback host names
        val host = req.headers["host"]?.substringBeforeLast(':')?.trim('[', ']')?.lowercase()
        if (host != null && host !in LOOPBACK_HOSTS)
            return res.json(403, OpenAiCodec.error("host '$host' not allowed", "permission_error"))
        val expected = token()?.takeIf { it.isNotBlank() }
        if (expected != null && req.headers["authorization"] != "Bearer $expected")
            return res.json(401, OpenAiCodec.error("invalid or missing API key", "authentication_error",
                "invalid_api_key"))

        when (req.path.trimEnd('/')) {
            "/v1/models" ->
                if (req.method != "GET") methodNotAllowed(res)
                else res.json(200, OpenAiCodec.models(backend.modelIds(), clock()))
            "/v1/chat/completions" ->
                if (req.method != "POST") methodNotAllowed(res) else chat(req, res)
            "/health" -> res.json(200, """{"status":"ok"}""")
            else -> res.json(404, OpenAiCodec.error("no route ${req.method} ${req.path}", "invalid_request_error"))
        }
    }

    private fun methodNotAllowed(res: HttpResponder) =
        res.json(405, OpenAiCodec.error("method not allowed", "invalid_request_error"))

    private suspend fun chat(req: HttpRequest, res: HttpResponder) {
        val ct = req.headers["content-type"].orEmpty()
        if (!ct.startsWith("application/json", ignoreCase = true))
            return res.json(415, OpenAiCodec.error("Content-Type must be application/json", "invalid_request_error"))
        val parsed = try {
            OpenAiCodec.parseChatRequest(req.body)
        } catch (e: ApiBadRequest) {
            return res.json(400, OpenAiCodec.error(e.message ?: "bad request", "invalid_request_error"))
        }
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = clock()
        if (parsed.stream) stream(parsed, id, created, res) else blocking(parsed, id, created, res)
    }

    private suspend fun blocking(r: ApiChatRequest, id: String, created: Long, res: HttpResponder) {
        val text = StringBuilder(); val think = StringBuilder()
        var model = r.model ?: "edge0"
        var terminal: BackendEvent? = null
        backend.chat(r).collect { ev ->
            when (ev) {
                is BackendEvent.Started -> model = ev.model
                is BackendEvent.Text -> text.append(ev.s)
                is BackendEvent.Think -> think.append(ev.s)
                else -> terminal = ev
            }
        }
        when (val t = terminal) {
            is BackendEvent.Done -> res.json(200, OpenAiCodec.completion(id, created, model,
                text.toString(), think.toString(), if (t.hitLimit) "length" else "stop", t.usage))
            else -> fail(t, res)
        }
    }

    private suspend fun stream(r: ApiChatRequest, id: String, created: Long, res: HttpResponder) {
        var sse: SseWriter? = null
        var model = r.model ?: "edge0"
        // headers go out only once the backend accepted the turn, so busy/no-model
        // still become proper HTTP status codes instead of an in-stream error
        backend.chat(r).collect { ev ->
            when (ev) {
                is BackendEvent.Started -> {
                    model = ev.model
                    sse = res.sse().also { it.event(OpenAiCodec.chunk(id, created, model, role = true)) }
                }
                is BackendEvent.Text -> sse?.event(OpenAiCodec.chunk(id, created, model, content = ev.s))
                is BackendEvent.Think -> sse?.event(OpenAiCodec.chunk(id, created, model, reasoning = ev.s))
                is BackendEvent.Done -> sse?.let { w ->
                    w.event(OpenAiCodec.chunk(id, created, model, finish = if (ev.hitLimit) "length" else "stop"))
                    if (r.includeUsage && ev.usage != null)
                        w.event(OpenAiCodec.usageChunk(id, created, model, ev.usage))
                    w.done()
                }
                else -> {
                    val w = sse
                    if (w == null) fail(ev, res) else {
                        w.event(OpenAiCodec.error(reasonOf(ev), "server_error")); w.done()
                    }
                }
            }
        }
    }

    private fun fail(t: BackendEvent?, res: HttpResponder) = when (t) {
        is BackendEvent.Busy -> res.json(429, OpenAiCodec.error(t.reason, "rate_limit_error", "slot_busy"))
        is BackendEvent.NoModel -> if (t.unknown)
            res.json(404, OpenAiCodec.error(t.reason, "invalid_request_error", "model_not_found"))
            else res.json(503, OpenAiCodec.error(t.reason, "server_error", "no_model_loaded"))
        else -> res.json(500, OpenAiCodec.error(reasonOf(t), "server_error"))
    }

    private fun reasonOf(ev: BackendEvent?) = when (ev) {
        is BackendEvent.Failed -> ev.reason
        is BackendEvent.Busy -> ev.reason
        is BackendEvent.NoModel -> ev.reason
        null -> "generation ended without a result"
        else -> "unexpected event"
    }

    companion object {
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}
