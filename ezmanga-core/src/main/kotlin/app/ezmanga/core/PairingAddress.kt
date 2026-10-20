package app.ezmanga.core

import java.net.URI

/**
 * A TV network address shown beneath the pairing code.
 *
 * Only plain HTTP on the local network is supported. An explicit port is
 * required because the TV server binds to a random port at startup.
 */
data class PairingAddress(val host: String, val port: Int) {
    init {
        require(host.isNotBlank()) { "The TV address is missing" }
        require(port in 1..65535) { "The TV port is out of range" }
    }

    val authority: String get() = "$host:$port"

    companion object {
        fun parse(raw: String): PairingAddress? {
            val text = raw.trim()
            if (text.isEmpty()) return null

            val withScheme = if ("://" in text) text else "http://$text"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true)) return null

            val host = uri.host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (host.contains(':')) return null
            val port = uri.port
            if (port !in 1..65535) return null

            return runCatching { PairingAddress(host, port) }.getOrNull()
        }
    }
}

object EzMangaDiscovery {
    const val SERVICE_TYPE = "_ezmanga._tcp."
    const val VERSION_ATTRIBUTE = "v"
}
