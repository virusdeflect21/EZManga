package app.ezmanga.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class RotatingPairingCodeTest {
    @Test
    fun `codes are six digits and rotate on the five minute boundary`() {
        var now = 1_000_000L
        val code = RotatingPairingCode(
            clock = { now },
            random = SequenceRandom(123456, 654321),
        )

        val first = code.current()
        assertEquals("123456", first.code)
        assertEquals(now + RotatingPairingCode.DEFAULT_LIFETIME_MILLIS, first.expiresAt)
        assertTrue(code.verify("123456"))

        now += RotatingPairingCode.DEFAULT_LIFETIME_MILLIS - 1
        assertTrue(code.verify("123456"))

        now += 1
        val second = code.current()
        assertEquals("654321", second.code)
        assertNotEquals(first.code, second.code)
        assertFalse(code.verify(first.code))
        assertTrue(code.verify(second.code))
    }

    @Test
    fun `malformed and wrong codes are rejected`() {
        val code = RotatingPairingCode(random = SequenceRandom(42))

        assertFalse(code.verify(""))
        assertFalse(code.verify("12345"))
        assertFalse(code.verify("12a456"))
        assertFalse(code.verify("000000"))
        assertTrue(code.verify("000042"))
    }

    private class SequenceRandom(vararg values: Int) : SecureRandom() {
        private val iterator = values.iterator()
        private var last = 0

        override fun nextInt(bound: Int): Int {
            if (iterator.hasNext()) last = iterator.next()
            return last % bound
        }
    }
}
