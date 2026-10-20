package app.ezmanga.core

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * A short-lived pairing code shown on the TV.
 *
 * Codes are six digits long and are replaced every [DEFAULT_LIFETIME_MILLIS].
 * The code is deliberately kept separate from [PairingPayload.token]: the code
 * is only used once to exchange for the long-lived, per-process bearer token.
 */
data class PairingCodeState(
    val code: String,
    val expiresAt: Long,
    val lifetimeMillis: Long,
) {
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long =
        (expiresAt - now).coerceAtLeast(0L)

    fun isExpired(now: Long = System.currentTimeMillis()): Boolean = remainingMillis(now) == 0L
}

class RotatingPairingCode(
    private val lifetimeMillis: Long = DEFAULT_LIFETIME_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(lifetimeMillis > 0) { "The pairing code lifetime must be positive" }
    }

    private val lock = Any()
    private var state: PairingCodeState = newState()

    fun current(): PairingCodeState = synchronized(lock) {
        rotateIfNeeded()
        state
    }

    /**
     * Returns true only while [candidate] is the active six-digit code.
     * Comparison is constant-time to avoid leaking the code through timing.
     */
    fun verify(candidate: String): Boolean = synchronized(lock) {
        rotateIfNeeded()
        if (candidate.length != CODE_LENGTH || candidate.any { !it.isDigit() }) return false

        MessageDigest.isEqual(
            candidate.toByteArray(StandardCharsets.US_ASCII),
            state.code.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    fun rotate(): PairingCodeState = synchronized(lock) {
        state = newState()
        state
    }

    private fun rotateIfNeeded() {
        if (clock() >= state.expiresAt) state = newState()
    }

    private fun newState(): PairingCodeState {
        val now = clock()
        return PairingCodeState(
            code = String.format(Locale.US, "%06d", random.nextInt(1_000_000)),
            expiresAt = now + lifetimeMillis,
            lifetimeMillis = lifetimeMillis,
        )
    }

    companion object {
        const val CODE_LENGTH = 6
        const val DEFAULT_LIFETIME_MILLIS = 5 * 60 * 1000L

        fun isWellFormed(candidate: String): Boolean =
            candidate.length == CODE_LENGTH && candidate.all { it.isDigit() }
    }
}
