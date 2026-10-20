package app.ezmanga.core

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {
    fun findLocalIpv4Address(): String? = candidateAddresses().firstOrNull()

    fun candidateAddresses(): List<String> {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        return interfaces.asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .sortedWith(compareBy({ if (it.isSiteLocalAddress) 0 else 1 }, { it.hostAddress }))
            .mapNotNull { it.hostAddress }
            .distinct()
            .toList()
    }
}
