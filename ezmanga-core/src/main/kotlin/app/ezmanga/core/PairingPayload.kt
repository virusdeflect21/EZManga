package app.ezmanga.core

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The content of the QR code shown by the TV.
 *
 * The token is only valid for the lifetime of the TV process. It is sent in the
 * [EzMangaClient]'s `X-EzManga-Token` header and is never stored on the phone.
 */
data class PairingPayload(
    val host: String,
    val port: Int,
    val token: String,
    val deviceName: String,
    val version: Int = PROTOCOL_VERSION,
) {
    init {
        require(host.isNotBlank()) { "Pairing host is missing" }
        require(host.none { it == '/' || it == ':' || it.isWhitespace() }) { "Pairing host is invalid" }
        require(port in 1..65535) { "Pairing port is out of range" }
        require(token.length >= MIN_TOKEN_LENGTH) { "Pairing token is too short" }
        require(version == PROTOCOL_VERSION) { "Unsupported pairing protocol version: $version" }
    }

    val baseUrl: String get() = "http://$host:$port"

    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(PAIR_HOST).append('?')
        append("host=").append(encode(host))
        append("&port=").append(port)
        append("&token=").append(encode(token))
        append("&name=").append(encode(deviceName))
        append("&v=").append(version)
    }

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MIN_TOKEN_LENGTH = 16
        const val SCHEME = "ezmanga"
        const val PAIR_HOST = "pair"

        fun parse(raw: String): PairingPayload {
            val text = raw.trim()
            val prefix = "$SCHEME://$PAIR_HOST"
            require(text.startsWith(prefix, ignoreCase = true)) { "Not an EZManga pairing code" }
            var rest = text.substring(prefix.length)
            if (rest.startsWith("/")) rest = rest.substring(1)
            require(rest.startsWith("?")) { "Pairing code is missing its parameters" }

            val params = rest.substring(1)
                .split('&')
                .mapNotNull { part ->
                    if (part.isBlank()) return@mapNotNull null
                    val index = part.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    decode(part.substring(0, index)) to decode(part.substring(index + 1))
                }
                .toMap()

            return PairingPayload(
                host = params["host"]?.trim().orEmpty(),
                port = params["port"]?.toIntOrNull() ?: 0,
                token = params["token"].orEmpty(),
                deviceName = params["name"]?.trim().takeUnless { it.isNullOrBlank() } ?: "EZManga TV",
                version = params["v"]?.toIntOrNull() ?: PROTOCOL_VERSION,
            )
        }

        private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}
