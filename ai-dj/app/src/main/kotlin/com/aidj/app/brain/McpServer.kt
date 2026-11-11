package com.aidj.app.brain

import android.util.Log
import com.aidj.core.ai.DjController
import com.aidj.core.ai.McpProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Minimal MCP server over HTTP (the JSON-response form of the Streamable HTTP transport) so external MCP
 * clients on the same network (e.g. Claude Desktop/Claude Code through an HTTP MCP connector) can drive the
 * DJ with the same tools Claude uses in-app.
 *
 * Endpoint: POST http://<phone-ip>:<port>/mcp, header `Authorization: Bearer <token>` (shown in Settings).
 * Browser-originated requests (an Origin header) are rejected to prevent DNS-rebinding attacks.
 */
class McpServer(private val controller: DjController, private val tokenProvider: () -> String, private val version: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val protocol = McpProtocol(controller, version)
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile var running = false
        private set

    fun start(port: Int) {
        if (running) return
        val ss = ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))
        server = ss
        running = true
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                pool.execute { runCatching { handle(s) }.onFailure { Log.w("Mcp", "request failed: ${it.message}") }; runCatching { s.close() } }
            }
        }, "mcp-accept").apply { isDaemon = true }.start()
        Log.i("Mcp", "MCP server listening on :$port")
    }

    fun stop() { running = false; runCatching { server?.close() }; server = null }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val out = socket.getOutputStream()
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (path.substringBefore('?') != "/mcp") return respond(out, 404, "{\"error\":\"not found\"}")
        if (headers.containsKey("origin")) return respond(out, 403, "{\"error\":\"browser origins are not allowed\"}")
        val auth = headers["authorization"].orEmpty().removePrefix("Bearer ").trim()
        if (!constantTimeEquals(auth, tokenProvider())) return respond(out, 401, "{\"error\":\"unauthorized\"}")
        if (method == "GET") return respond(out, 405, "{\"error\":\"SSE stream not offered; use POST\"}")
        if (method == "DELETE") return respond(out, 200, "{}")
        if (method != "POST") return respond(out, 405, "{\"error\":\"method not allowed\"}")
        val len = headers["content-length"]?.toIntOrNull() ?: return respond(out, 411, "{\"error\":\"length required\"}")
        if (len > 256 * 1024) return respond(out, 413, "{\"error\":\"too large\"}")
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        val parsed = runCatching { json.parseToJsonElement(String(body, 0, read, Charsets.UTF_8)) }.getOrNull()
            ?: return respond(out, 400, json.encodeToString(JsonObject.serializer(), McpProtocol.rpcError(null, -32700, "Parse error")))
        val responses = when (parsed) {
            is JsonArray -> parsed.mapNotNull { protocol.handle(it.jsonObject) }
            is JsonObject -> listOfNotNull(protocol.handle(parsed))
            else -> listOf(McpProtocol.rpcError(null, -32600, "Invalid request"))
        }
        when {
            responses.isEmpty() -> respond(out, 202, "")
            parsed is JsonArray -> respond(out, 200, json.encodeToString(JsonArray.serializer(), JsonArray(responses)))
            else -> respond(out, 200, json.encodeToString(JsonObject.serializer(), responses.first()))
        }
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 411 to "Length Required", 413 to "Payload Too Large")[code] ?: "Error"
        out.write("HTTP/1.1 $code $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes); out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(c.toChar())
        }
    }

    private fun constantTimeEquals(a: String, b: String) = b.isNotEmpty() && MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
