package app.ezmanga.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PairingPayloadTest {
    @Test
    fun `pairing payload round trips through the QR URI`() {
        val payload = PairingPayload(
            host = "192.168.1.42",
            port = 43210,
            token = "0123456789abcdef0123456789abcdef",
            deviceName = "Living room TV",
        )

        assertEquals(payload, PairingPayload.parse(payload.toUri()))
        assertEquals("http://192.168.1.42:43210", payload.baseUrl)
    }

    @Test
    fun `pairing payload rejects unknown schemes`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse("https://example.com/pair")
        }
    }

    @Test
    fun `pairing payload rejects invalid ports and short tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse("ezmanga://pair?host=192.168.1.2&port=0&token=0123456789abcdef")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingPayload.parse("ezmanga://pair?host=192.168.1.2&port=8080&token=short")
        }
    }
}
