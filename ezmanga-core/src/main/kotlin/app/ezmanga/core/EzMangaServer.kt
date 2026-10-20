package app.ezmanga.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * A deliberately small, token-protected HTTP server for the TV.
 *
 * Endpoints:
 * - GET    /api/ping
 * - POST   /api/pair                  (six-digit code -> bearer token)
 * - GET    /api/info
 * - GET    /api/library
 * - POST   /api/upload?filename=...
 * - DELETE /api/books/{id}
 * - GET    /api/books/{id}/pages
 */
class EzMangaServer(
    private val store: LocalBookStore,
    private val pairingToken: String,
    private val deviceName: String,
    private val listener: Listener = object : Listener {},
    private val pairingCode: RotatingPairingCode = RotatingPairingCode(),
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    interface Listener {
        fun onAuthorized() = Unit
        fun onLibraryChanged() = Unit
    }

    private val pairAttempts = PairAttemptLimiter(clock = clock)

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ezmanga-http").apply { isDaemon = true }
    }

    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false

    val port: Int get() = serverSocket?.localPort ?: -1

    fun currentPairingCode(): PairingCodeState = pairingCode.current()

    fun start(): Int {
        check(serverSocket == null) { "The server is already running" }

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(0), 64)
        serverSocket = socket
        running = true
        Thread({ acceptLoop(socket) }, "ezmanga-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                break
            }

            executor.execute {
                runCatching { handle(client) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val response = try {
                val request = readRequest(input)
                if (request == null) httpJson(400, "Bad request") else route(request)
            } catch (e: RequestTooLargeException) {
                httpJson(413, e.message ?: "Request too large")
            } catch (e: Exception) {
                System.err.println("EZManga server error: ${e.message}")
                httpJson(500, "Internal server error")
            }

            writeResponse(output, response)
        }
    }

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null

        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val rawPath = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            if (headers.size > 64) throw RequestTooLargeException("Too many headers")
        }

        val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
        if (contentLength < 0 || contentLength > MAX_UPLOAD_BYTES) {
            throw RequestTooLargeException("Upload is too large")
        }

        val path = rawPath.split('/').filter { it.isNotEmpty() }.map { decode(it) }
        return HttpRequest(method, path, query, headers, input, contentLength)
    }

    private fun readBody(request: HttpRequest, limit: Long): String? {
        val length = request.contentLength
        if (length <= 0L || length > limit) return null

        val bytes = ByteArray(length.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = request.body.read(bytes, offset, bytes.size - offset)
            if (read < 0) return null
            offset += read
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun route(request: HttpRequest): HttpResponse {
        val path = request.path

        if (request.method == "GET" && path == listOf("api", "ping")) {
            return httpJson(200, """{"ok":true,"app":"EZManga","version":${PairingPayload.PROTOCOL_VERSION}}""")
        }

        if (request.method == "POST" && path == listOf("api", "pair")) {
            return pair(request)
        }

        if (!isAuthorized(request)) return httpJson(401, """{"error":"Pairing token is invalid"}""")
        listener.onAuthorized()

        return when {
            request.method == "GET" && path == listOf("api", "info") -> {
                val body = buildString {
                    append("{\"deviceName\":\"").append(jsonEscape(deviceName)).append("\",")
                    append("\"version\":").append(PairingPayload.PROTOCOL_VERSION).append('}')
                }
                httpJson(200, body)
            }
            request.method == "GET" && path == listOf("api", "library") -> httpJson(200, libraryJson())
            request.method == "POST" && path == listOf("api", "upload") -> upload(request)
            request.method == "DELETE" && path.size == 3 && path[0] == "api" && path[1] == "books" -> {
                delete(path[2])
            }
            request.method == "GET" &&
                path.size == 4 &&
                path[0] == "api" &&
                path[1] == "books" &&
                path[3] == "pages" -> {
                pages(path[2])
            }
            else -> httpJson(404, """{"error":"Not found"}""")
        }
    }

    private fun pair(request: HttpRequest): HttpResponse {
        if (request.contentLength <= 0L) {
            return httpJson(411, """{"error":"A JSON body with a six-digit code is required"}""")
        }
        if (request.contentLength > MAX_PAIR_BODY_BYTES) {
            return httpJson(413, """{"error":"The pairing request is too large"}""")
        }

        val body = readBody(request, MAX_PAIR_BODY_BYTES)
            ?: return httpJson(400, """{"error":"The pairing request could not be read"}""")
        val code = CODE_PATTERN.find(body)?.groupValues?.getOrNull(1)
            ?: return httpJson(400, """{"error":"A six-digit code is required"}""")

        if (!RotatingPairingCode.isWellFormed(code)) {
            return httpJson(400, """{"error":"A six-digit code is required"}""")
        }
        if (pairAttempts.isBlocked()) {
            val retryAfter = pairAttempts.retryAfterSeconds()
            return httpJson(
                429,
                """{"error":"Too many incorrect codes","retryAfterSeconds":$retryAfter}""",
            )
        }
        if (!pairingCode.verify(code)) {
            pairAttempts.recordFailure()
            return httpJson(401, """{"error":"The six-digit code is invalid or has expired"}""")
        }

        pairAttempts.reset()
        listener.onAuthorized()
        val response = buildString {
            append("{\"token\":\"").append(jsonEscape(pairingToken)).append("\",")
            append("\"deviceName\":\"").append(jsonEscape(deviceName)).append("\",")
            append("\"version\":").append(PairingPayload.PROTOCOL_VERSION).append('}')
        }
        return httpJson(200, response)
    }

    private fun upload(request: HttpRequest): HttpResponse {
        if (request.contentLength <= 0L) {
            return httpJson(411, """{"error":"A Content-Length header is required"}""")
        }

        val filename = request.headers["x-ezmanga-filename"] ?: request.query["filename"] ?: "ezmanga-upload.cbz"
        val temp = store.newIncomingFile()

        try {
            val buffer = ByteArray(64 * 1024)
            var remaining = request.contentLength
            temp.outputStream().use { output ->
                while (remaining > 0) {
                    val read = request.body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("Upload ended before all bytes were received")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }

            val book = store.importDownload(temp, decodeOrOriginal(filename))
            listener.onLibraryChanged()
            return httpJson(201, "{\"book\":${bookJson(book)}}")
        } catch (e: InvalidCbzException) {
            temp.delete()
            return httpJson(422, "{\"error\":\"${jsonEscape(e.message ?: "Invalid CBZ")}\"}")
        } catch (e: Exception) {
            temp.delete()
            return httpJson(500, "{\"error\":\"${jsonEscape(e.message ?: "Upload failed")}\"}")
        }
    }

    private fun delete(id: String): HttpResponse {
        if (!store.delete(id)) return httpJson(404, """{"error":"Book not found"}""")
        listener.onLibraryChanged()
        return httpJson(200, "{\"deleted\":true,\"id\":\"${jsonEscape(id)}\"}")
    }

    private fun pages(id: String): HttpResponse {
        val book = store.book(id) ?: return httpJson(404, """{"error":"Book not found"}""")
        val contents = runCatching { CbzInspector.inspect(book.file) }.getOrElse {
            return httpJson(500, """{"error":"Could not read the CBZ"}""")
        }

        return httpJson(
            200,
            buildString {
                append("{\"id\":\"").append(jsonEscape(id)).append("\",\"pages\":[")
                contents.pageEntries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    append('"').append(jsonEscape(entry)).append('"')
                }
                append("]}")
            },
        )
    }

    private fun libraryJson(): String = buildString {
        append("{\"books\":[")
        store.books().forEachIndexed { index, book ->
            if (index > 0) append(',')
            append(bookJson(book))
        }
        append("]}")
    }

    private fun bookJson(book: StoredBook): String = buildString {
        append("{\"id\":\"").append(jsonEscape(book.id)).append("\",")
        append("\"title\":\"").append(jsonEscape(book.title)).append("\",")
        append("\"filename\":\"").append(jsonEscape(book.metadata.originalFilename)).append("\",")
        append("\"pageCount\":").append(book.pageCount).append(',')
        append("\"sizeBytes\":").append(book.sizeBytes).append(',')
        append("\"addedAt\":").append(book.addedAt).append('}')
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val supplied = request.headers["x-ezmanga-token"] ?: request.query["token"] ?: return false
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            pairingToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) {
                return if (buffer.size() == 0) null else String(buffer.toByteArray(), StandardCharsets.UTF_8)
            }
            if (value == '\n'.code) {
                val bytes = buffer.toByteArray()
                val length = if (bytes.isNotEmpty() &&
                    bytes.last() == '\r'.code.toByte()
                ) {
                    bytes.size - 1
                } else {
                    bytes.size
                }
                return String(bytes, 0, length, StandardCharsets.UTF_8)
            }
            buffer.write(value)
            if (buffer.size() > MAX_HEADER_LINE) throw RequestTooLargeException("Header line is too long")
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            decode(part.substring(0, index)) to decode(part.substring(index + 1))
        }.toMap()
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun decodeOrOriginal(value: String): String = runCatching { decode(value) }.getOrDefault(value)

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val reason = when (response.status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Error"
        }

        val headers = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(response.contentType).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("\r\n")
        }

        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(response.body)
        output.flush()
    }

    private fun httpJson(status: Int, body: String): HttpResponse = HttpResponse(
        status = status,
        contentType = "application/json; charset=utf-8",
        body = body.toByteArray(StandardCharsets.UTF_8),
    )

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: List<String>,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: InputStream,
        val contentLength: Long,
    )

    private data class HttpResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
    )

    private class RequestTooLargeException(message: String) : Exception(message)

    /**
     * A small global throttle for code guesses. The six-digit code rotates every
     * five minutes, and this makes online brute force impractical.
     */
    private class PairAttemptLimiter(
        private val maxFailures: Int = 8,
        private val windowMillis: Long = 60_000,
        private val clock: () -> Long,
    ) {
        private var windowStartedAt = 0L
        private var failures = 0

        @Synchronized
        fun isBlocked(): Boolean {
            resetWindowIfNeeded()
            return failures >= maxFailures
        }

        @Synchronized
        fun recordFailure() {
            resetWindowIfNeeded()
            failures++
        }

        @Synchronized
        fun reset() {
            failures = 0
            windowStartedAt = clock()
        }

        @Synchronized
        fun retryAfterSeconds(): Long {
            resetWindowIfNeeded()
            val elapsed = clock() - windowStartedAt
            return ((windowMillis - elapsed).coerceAtLeast(0L) + 999L) / 1000L
        }

        private fun resetWindowIfNeeded() {
            val now = clock()
            if (windowStartedAt == 0L || now - windowStartedAt >= windowMillis) {
                windowStartedAt = now
                failures = 0
            }
        }
    }

    companion object {
        private const val MAX_HEADER_LINE = 16 * 1024
        private const val MAX_UPLOAD_BYTES = 1_500_000_000L
        private const val MAX_PAIR_BODY_BYTES = 4L * 1024
        private val CODE_PATTERN = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"")
    }
}
