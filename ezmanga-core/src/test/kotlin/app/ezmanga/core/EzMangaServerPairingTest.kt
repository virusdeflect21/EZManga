package app.ezmanga.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.SecureRandom

class EzMangaServerPairingTest {
    @TempDir
    lateinit var tempDirectory: Path

    private var now = 5_000_000L
    private lateinit var server: EzMangaServer

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    @Test
    fun `a six digit code can be exchanged for a working bearer token`() {
        server = startServer()
        val code = server.currentPairingCode().code

        val response = EzMangaClient.pairWithCode("127.0.0.1", server.port, code)

        assertTrue(response.contains("\"deviceName\":\"Test TV\""))
        val token = TOKEN_PATTERN.find(response)?.groupValues?.getOrNull(1)
        assertEquals(TOKEN, token)

        val client = EzMangaClient(PairingPayload("127.0.0.1", server.port, token.orEmpty(), "Test TV"))
        assertTrue(client.infoRaw().contains("Test TV"))
    }

    @Test
    fun `the code stops working after five minutes`() {
        server = startServer()
        val oldCode = server.currentPairingCode().code

        now += RotatingPairingCode.DEFAULT_LIFETIME_MILLIS
        val newCode = server.currentPairingCode().code

        val error = assertThrows<EzMangaHttpException> {
            EzMangaClient.pairWithCode("127.0.0.1", server.port, oldCode)
        }
        assertEquals(401, error.statusCode)

        if (newCode != oldCode) {
            assertTrue(EzMangaClient.pairWithCode("127.0.0.1", server.port, newCode).contains(TOKEN))
        }
    }

    @Test
    fun `repeated wrong codes are throttled and recover after a minute`() {
        server = startServer()
        val code = server.currentPairingCode().code

        repeat(8) {
            val error = assertThrows<EzMangaHttpException> {
                EzMangaClient.pairWithCode("127.0.0.1", server.port, "000000")
            }
            assertEquals(401, error.statusCode)
        }

        val throttled = assertThrows<EzMangaHttpException> {
            EzMangaClient.pairWithCode("127.0.0.1", server.port, code)
        }
        assertEquals(429, throttled.statusCode)

        now += 60_000
        assertTrue(EzMangaClient.pairWithCode("127.0.0.1", server.port, code).contains(TOKEN))
    }

    private fun startServer(): EzMangaServer {
        val store = LocalBookStore(tempDirectory.resolve("library-${System.nanoTime()}").toFile())
        val pairingCode = RotatingPairingCode(
            clock = { now },
            random = SequenceRandom(123456, 654321),
        )
        return EzMangaServer(
            store = store,
            pairingToken = TOKEN,
            deviceName = "Test TV",
            pairingCode = pairingCode,
            clock = { now },
        ).also { it.start() }
    }

    private class SequenceRandom(vararg values: Int) : SecureRandom() {
        private val iterator = values.iterator()
        private var last = 0

        override fun nextInt(bound: Int): Int {
            if (iterator.hasNext()) last = iterator.next()
            return last % bound
        }
    }

    companion object {
        private const val TOKEN = "0123456789abcdef0123456789abcdef"
        private val TOKEN_PATTERN = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"")
    }
}
