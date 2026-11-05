// api/OpenAiCodec.kt - OpenAI chat-completions wire format <-> engine-neutral types.
// Pure JVM (no android.*): unit-tested in src/test without a device.
package dev.edge0.runtime.app.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ApiMessage(val role: String, val content: String)

/** Parsed request. Null fields = "use the app's settings". */
data class ApiChatRequest(
    val model: String?,
    val messages: List<ApiMessage>,
    val stream: Boolean,
    val temperature: Float?,
    val maxTokens: Int?,
    val topK: Int?,
    val enableThinking: Boolean?,
    val includeUsage: Boolean,
)

class ApiBadRequest(message: String) : Exception(message)

object OpenAiCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val ROLES = setOf("system", "user", "assistant")

    fun parseChatRequest(body: String): ApiChatRequest {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ApiBadRequest("body is not a JSON object") }
        val rawMsgs = root["messages"] as? JsonArray
            ?: throw ApiBadRequest("'messages' must be an array")
        if (rawMsgs.isEmpty()) throw ApiBadRequest("'messages' is empty")

        val msgs = rawMsgs.mapIndexed { i, el ->
            val o = el as? JsonObject ?: throw ApiBadRequest("messages[$i] is not an object")
            var role = o.str("role") ?: throw ApiBadRequest("messages[$i].role missing")
            if (role == "developer") role = "system"          // newer OpenAI clients send this
            if (role !in ROLES) throw ApiBadRequest("messages[$i].role '$role' not supported")
            ApiMessage(role, contentText(o["content"]))
        }
        // engine contract: at most one system turn, and it must come first -> merge them
        val systems = msgs.filter { it.role == "system" }.map { it.content }.filter { it.isNotBlank() }
        val rest = msgs.filter { it.role != "system" }
        if (rest.isEmpty() || rest.last().role != "user")
            throw ApiBadRequest("the last message must have role 'user'")
        val merged = buildList {
            if (systems.isNotEmpty()) add(ApiMessage("system", systems.joinToString("\n\n")))
            addAll(rest)
        }

        val maxTokens = (root.int("max_completion_tokens") ?: root.int("max_tokens"))
            ?.also { if (it <= 0) throw ApiBadRequest("max_tokens must be > 0") }
        // thinking switch: llama.cpp / vLLM convention, plus a flat alias
        val kwargs = root["chat_template_kwargs"] as? JsonObject
        val thinking = kwargs?.bool("enable_thinking") ?: root.bool("enable_thinking")
        val streamOpts = root["stream_options"] as? JsonObject

        return ApiChatRequest(
            model = root.str("model"),
            messages = merged,
            stream = root.bool("stream") ?: false,
            temperature = root.float("temperature")?.coerceIn(0f, 2f),
            maxTokens = maxTokens,
            topK = root.int("top_k"),
            enableThinking = thinking,
            includeUsage = streamOpts?.bool("include_usage") ?: false,
        )
    }

    /** OpenAI allows content as a string or an array of parts; only text parts are kept. */
    private fun contentText(el: JsonElement?): String = when (el) {
        null, JsonNull -> ""
        is JsonPrimitive -> el.contentOrNull ?: ""
        is JsonArray -> el.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            if (o.str("type") == "text") o.str("text") else
                throw ApiBadRequest("only text content parts are supported (got '${o.str("type")}')")
        }.joinToString("")
        else -> throw ApiBadRequest("unsupported content type")
    }

    // ---- responses -------------------------------------------------------------------------

    data class Usage(val prompt: Int, val completion: Int)

    fun completion(id: String, created: Long, model: String, text: String,
                   reasoning: String, finish: String, usage: Usage?): String =
        buildJsonObject {
            put("id", id); put("object", "chat.completion"); put("created", created); put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("message", buildJsonObject {
                        put("role", "assistant")
                        put("content", text)
                        if (reasoning.isNotEmpty()) put("reasoning_content", reasoning)
                    })
                    put("finish_reason", finish)
                })
            })
            if (usage != null) put("usage", usageJson(usage))
        }.toString()

    /** One SSE chunk. Exactly one of role/content/reasoning/finish is normally set. */
    fun chunk(id: String, created: Long, model: String, role: Boolean = false,
              content: String? = null, reasoning: String? = null, finish: String? = null): String =
        buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", buildJsonObject {
                        if (role) put("role", "assistant")
                        if (content != null) put("content", content)
                        if (reasoning != null) put("reasoning_content", reasoning)
                    })
                    put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            })
        }.toString()

    fun usageChunk(id: String, created: Long, model: String, usage: Usage): String =
        buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
            put("choices", JsonArray(emptyList()))
            put("usage", usageJson(usage))
        }.toString()

    fun models(ids: List<String>, created: Long): String = buildJsonObject {
        put("object", "list")
        put("data", buildJsonArray {
            ids.forEach { id ->
                add(buildJsonObject {
                    put("id", id); put("object", "model"); put("created", created); put("owned_by", "edge0")
                })
            }
        })
    }.toString()

    fun error(message: String, type: String, code: String? = null): String = buildJsonObject {
        put("error", buildJsonObject {
            put("message", message); put("type", type)
            put("code", code?.let { JsonPrimitive(it) } ?: JsonNull)
        })
    }.toString()

    private fun usageJson(u: Usage) = buildJsonObject {
        put("prompt_tokens", u.prompt); put("completion_tokens", u.completion)
        put("total_tokens", u.prompt + u.completion)
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.float(k: String) = (this[k] as? JsonPrimitive)?.floatOrNull
}
