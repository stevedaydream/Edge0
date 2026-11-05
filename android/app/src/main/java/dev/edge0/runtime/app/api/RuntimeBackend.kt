// api/RuntimeBackend.kt - adapts the app's single LlamaRuntime to the API's ChatBackend.
// Same engine instance as the chat screen: one loaded model, one generation slot,
// and the tuned JNI loop (moe_pool streaming, keepwarm, KV prefix reuse) is reused as-is.
package dev.edge0.runtime.app.api

import dev.edge0.runtime.app.data.AppSettings
import dev.edge0.runtime.app.data.SettingsStore
import dev.edge0.runtime.app.runtime.GenEvent
import dev.edge0.runtime.app.runtime.GenState
import dev.edge0.runtime.app.runtime.ModelInventory
import dev.edge0.runtime.app.runtime.Runtime
import dev.edge0.runtime.engine.ChatMessage
import dev.edge0.runtime.engine.GenParams
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class RuntimeBackend(
    private val runtime: Runtime,
    private val settings: SettingsStore,
    private val modelRoots: () -> List<File?>,
) : ChatBackend {
    /** API requests queue behind each other; the in-app chat is refused-with-429 instead. */
    private val apiLock = Mutex()

    override suspend fun modelIds(): List<String> =
        ModelInventory.scanRoots(modelRoots()).filter { it.valid }.map { it.name }

    override fun chat(req: ApiChatRequest): Flow<BackendEvent> = flow {
        apiLock.withLock {
            val s = settings.flow.first()

            // ---- pick + load the model --------------------------------------------------
            val entries = ModelInventory.scanRoots(modelRoots()).filter { it.valid }
            val wanted = req.model?.takeIf { it.isNotBlank() && it != "edge0" }
            val target: String? = when {
                wanted != null -> entries.firstOrNull { it.name == wanted }?.dir?.absolutePath
                    ?: return@withLock emit(BackendEvent.NoModel(
                        "model '$wanted' not found; GET /v1/models lists the imported ones", unknown = true))
                runtime.activeModelDir != null -> runtime.activeModelDir
                s.activeModelDir.isNotBlank() -> s.activeModelDir
                else -> null
            }
            if (target == null) return@withLock emit(BackendEvent.NoModel(
                "no model loaded: import a .gguf in the Edge0 app and load it once", unknown = false))
            if (runtime.activeModelDir != target) {
                if (runtime.state.value is GenState.Streaming)
                    return@withLock emit(BackendEvent.Busy("in-app chat is generating; cannot switch models now"))
                runCatching { runtime.ensureLoaded(target) }.onFailure {
                    return@withLock emit(BackendEvent.Failed("load failed: ${it.message}"))
                }
                (runtime.state.value as? GenState.Error)?.let {
                    return@withLock emit(BackendEvent.Failed("load failed: ${it.diag}"))
                }
            }
            val modelName = File(target).name.removeSuffix(".gguf")

            // a just-cancelled turn settles within a moment; an in-app turn does not
            withTimeoutOrNull(SETTLE_MS) { runtime.state.first { it !is GenState.Cancelling } }
            if (runtime.state.value is GenState.Streaming)
                return@withLock emit(BackendEvent.Busy("in-app chat is generating; retry shortly"))

            // ---- run the turn -----------------------------------------------------------
            val msgs = req.messages.map { ChatMessage(it.role, it.content) }
            val params = s.toApiParams(req)
            val thinking = req.enableThinking ?: s.enableThinking
            var finished = false
            var started = false
            try {
                runtime.submit(THREAD_ID, msgs, thinking, params).collect { ev ->
                    if (!started && ev !is GenEvent.Failed) {
                        started = true; emit(BackendEvent.Started(modelName))
                    }
                    when (ev) {
                        is GenEvent.TextPiece -> emit(BackendEvent.Text(ev.s))
                        is GenEvent.ThinkPiece -> emit(BackendEvent.Think(ev.s))
                        is GenEvent.Done -> {
                            finished = true
                            val m = ev.metrics
                            emit(BackendEvent.Done(
                                m?.let { OpenAiCodec.Usage(it.promptTokens, it.newTokens) },
                                hitLimit = m != null && m.newTokens >= params.maxNewTokens))
                        }
                        is GenEvent.Cancelled -> { finished = true; emit(BackendEvent.Failed("cancelled")) }
                        is GenEvent.Failed -> {
                            finished = true
                            emit(if (ev.diag.contains("busy")) BackendEvent.Busy(ev.diag)
                                 else BackendEvent.Failed(ev.diag))
                        }
                    }
                }
            } finally {
                // client disconnected (or anything else threw) mid-turn: stop the native loop,
                // otherwise it keeps decoding into the void and blocks the slot
                if (started && !finished) runtime.cancelActive()
            }
        }
    }

    private fun AppSettings.toApiParams(r: ApiChatRequest) = GenParams(
        temperature = r.temperature ?: temperature,
        topK = r.topK ?: topK,
        topP = topP,                     // engine has no top-p; kept for parity with the chat screen
        seed = seed,
        maxNewTokens = r.maxTokens ?: if (maxNewTokens > 0) maxNewTokens else 1024,
        repetitionPenalty = repetitionPenalty,
        firstTokenGreedy = true,
    )

    companion object {
        /** Separate from chat-screen thread ids; KV prefix reuse is decided by message match. */
        const val THREAD_ID = "api"
        const val SETTLE_MS = 3_000L
    }
}
