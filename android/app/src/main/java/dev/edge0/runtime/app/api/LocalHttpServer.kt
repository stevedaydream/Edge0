// api/LocalHttpServer.kt - minimal HTTP/1.1 server bound to the loopback interface only.
// Deliberately dependency-free (the app ships as one small APK): one request per
// connection (Connection: close), Content-Length request bodies, chunked responses for SSE.
// Pure JVM (no android.*).
package dev.edge0.runtime.app.api

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,   // keys lower-cased
    val body: String,
)

/** Response sink handed to the router. Use either [json] or [sse] once. */
class HttpResponder internal constructor(private val out: OutputStream) {
    private var started = false
    val committed get() = started

    fun json(status: Int, body: String) {
        check(!started) { "response already started" }
        started = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(status, listOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Content-Length" to bytes.size.toString()))
        out.write(bytes); out.flush()
    }

    /** Starts a text/event-stream response; returns a writer for `data:` events. */
    fun sse(): SseWriter {
        check(!started) { "response already started" }
        started = true
        writeHead(200, listOf(
            "Content-Type" to "text/event-stream; charset=utf-8",
            "Cache-Control" to "no-cache",
            "Transfer-Encoding" to "chunked"))
        out.flush()
        return SseWriter(out)
    }

    private fun writeHead(status: Int, extra: List<Pair<String, String>>) {
        val sb = StringBuilder("HTTP/1.1 $status ${reason(status)}\r\n")
        (extra + ("Connection" to "close")).forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.US_ASCII))
    }

    private fun reason(s: Int) = when (s) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
        404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"; 429 -> "Too Many Requests"; 503 -> "Service Unavailable"
        else -> if (s >= 500) "Internal Server Error" else "Status"
    }
}

class SseWriter internal constructor(private val out: OutputStream) {
    /** Writes one `data:` event; throws IOException when the client has gone away. */
    fun event(data: String) = chunk("data: $data\n\n")
    fun done() { chunk("data: [DONE]\n\n"); out.write("0\r\n\r\n".toByteArray()); out.flush() }
    private fun chunk(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        out.write((Integer.toHexString(b.size) + "\r\n").toByteArray(Charsets.US_ASCII))
        out.write(b); out.write("\r\n".toByteArray()); out.flush()
    }
}

class LocalHttpServer(
    private val port: Int,
    private val handler: suspend (HttpRequest, HttpResponder) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Bound port (useful when constructed with port 0 in tests). */
    val boundPort: Int get() = socket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        // loopback only: never reachable from Wi-Fi / LAN
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16)
        socket = ss
        acceptJob = scope.launch {
            while (isActive) {
                val c = try { ss.accept() } catch (e: SocketException) { break }
                launch { serve(c) }
            }
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    private suspend fun serve(c: Socket) = c.use { sock ->
        sock.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val res = HttpResponder(out)
        val req = try {
            readRequest(input)
        } catch (e: TooLarge) {
            res.json(413, OpenAiCodec.error("request body too large", "invalid_request_error")); return
        } catch (e: Exception) {
            runCatching { res.json(400, OpenAiCodec.error("malformed HTTP request", "invalid_request_error")) }
            return
        } ?: return
        sock.soTimeout = 0 // generation may legitimately take minutes (35B cold pool)
        try {
            handler(req, res)
        } catch (e: IOException) {
            // client went away mid-stream: nothing to report
        } catch (e: Exception) {
            if (!res.committed) runCatching {
                res.json(500, OpenAiCodec.error(e.message ?: "internal error", "server_error"))
            }
        }
    }

    private class TooLarge : Exception()

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw IOException("bad request line")
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("eof in headers")
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) throw IOException("bad header")
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > 64) throw IOException("too many headers")
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true)
            throw IOException("chunked request bodies not supported")
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > MAX_BODY) throw TooLarge()
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(body, off, len - off)
            if (n < 0) throw IOException("eof in body")
            off += n
        }
        val path = parts[1].substringBefore('?')
        return HttpRequest(parts[0].uppercase(), path, headers, String(body, Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) throw IOException("line too long")
        }
        return buf.toString(Charsets.US_ASCII.name())
    }

    companion object {
        const val MAX_BODY = 4 * 1024 * 1024
        const val READ_TIMEOUT_MS = 15_000
    }
}
