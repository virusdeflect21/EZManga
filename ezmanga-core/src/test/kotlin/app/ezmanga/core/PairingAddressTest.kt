package app.ezmanga.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PairingAddressTest {
    @Test
    fun `accepts host port with or without a scheme`() {
        assertEquals(PairingAddress("10.0.2.2", 34067), PairingAddress.parse("10.0.2.2:34067"))
        assertEquals(PairingAddress("192.168.1.20", 41234), PairingAddress.parse("http://192.168.1.20:41234"))
        assertEquals(PairingAddress("tv.local", 8080), PairingAddress.parse("  TV.local:8080  "))
    }

    @Test
    fun `rejects addresses that cannot be used for the TV server`() {
        assertNull(PairingAddress.parse(""))
        assertNull(PairingAddress.parse("10.0.2.2"))
        assertNull(PairingAddress.parse("http://10.0.2.2:0"))
        assertNull(PairingAddress.parse("ftp://10.0.2.2:1234"))
        assertNull(PairingAddress.parse("http://[::1]:1234"))
    }
}
