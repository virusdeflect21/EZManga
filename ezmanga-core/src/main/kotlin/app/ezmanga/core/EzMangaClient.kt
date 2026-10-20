package app.ezmanga.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class EzMangaHttpException(val statusCode: Int, val responseBody: String) :
    IOException("The TV returned HTTP $statusCode${if (responseBody.isBlank()) "" else ": $responseBody"}")

class EzMangaClient(
    val pairing: PairingPayload,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 90_000,
) {
    fun ping(): Boolean = runCatching {
        request("GET", "/api/ping").status in 200..299
    }.getOrDefault(false)

    fun infoRaw(): String = request("GET", "/api/info").body

    fun libraryRaw(): String = request("GET", "/api/library").body

    fun upload(file: File, filename: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        require(file.isFile) { "The selected file does not exist" }
        return file.inputStream().use { input ->
            upload(input, file.length(), filename, onProgress)
        }
    }

    /**
     * Streams an already-open document directly to the TV. This avoids writing a
     * second copy into the phone's cache when the document provider reports a size.
     */
    fun upload(
        input: InputStream,
        total: Long,
        filename: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): String {
        require(total > 0L) { "The selected document is empty" }
        val url = URL("${pairing.baseUrl}/api/upload?filename=${encode(filename)}")
        val connection = open(url)

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/vnd.comicbook+zip")
            connection.setRequestProperty("X-EzManga-Token", pairing.token)
            connection.setFixedLengthStreamingMode(total)
            connection.connect()

            var sent = 0L
            var lastPercent = -1
            connection.outputStream.buffered(64 * 1024).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    sent += read
                    val percent = ((sent * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(sent, total)
                    }
                }
            }

            if (sent != total) {
                throw IOException("The selected document changed while it was being uploaded")
            }

            val response = readResponse(connection)
            if (response.status !in 200..299) throw EzMangaHttpException(response.status, response.body)
            return response.body
        } finally {
            connection.disconnect()
        }
    }

    fun delete(bookId: String): Boolean = request("DELETE", "/api/books/${encodePath(bookId)}").status in 200..299

    private fun request(method: String, path: String): Response {
        val connection = open(URL(pairing.baseUrl + path))
        try {
            connection.requestMethod = method
            connection.setRequestProperty("X-EzManga-Token", pairing.token)
            connection.setRequestProperty("Accept", "application/json")
            val response = readResponse(connection)
            if (response.status !in 200..299) throw EzMangaHttpException(response.status, response.body)
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: URL): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        useCaches = false
        instanceFollowRedirects = false
    }

    private fun readResponse(connection: HttpURLConnection): Response {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        return Response(status, body)
    }

    private data class Response(val status: Int, val body: String)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

    companion object {
        /**
         * Exchanges a six-digit TV code for the bearer token used by [EzMangaClient].
         * The returned JSON is parsed by the caller so this core module stays free
         * of Android-specific JSON classes.
         */
        fun pairWithCode(
            host: String,
            port: Int,
            code: String,
            connectTimeoutMs: Int = 5_000,
            readTimeoutMs: Int = 10_000,
        ): String {
            require(host.isNotBlank()) { "The TV address is missing" }
            require(port in 1..65535) { "The TV port is out of range" }
            require(RotatingPairingCode.isWellFormed(code)) { "The six-digit code is invalid" }

            val body = """{"code":"$code"}""".toByteArray(StandardCharsets.UTF_8)
            val connection = (URL("http://$host:$port/api/pair").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setFixedLengthStreamingMode(body.size)
            }

            try {
                connection.outputStream.use { it.write(body) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw EzMangaHttpException(status, response)
                return response
            } finally {
                connection.disconnect()
            }
        }
    }
}
