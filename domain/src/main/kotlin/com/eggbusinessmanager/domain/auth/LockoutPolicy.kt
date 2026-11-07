package com.eggbusinessmanager.domain.auth

import java.time.Duration
import java.time.Instant

/**
 * Progressive lockout (FR-006): every 5th consecutive failure locks for 30 s, doubling with each
 * further 5 failures (60 s, 120 s, …) up to 30 minutes.
 */
object LockoutPolicy {
    const val FAILURES_PER_STEP = 5
    val FIRST_LOCK: Duration = Duration.ofSeconds(30)
    val MAX_LOCK: Duration = Duration.ofMinutes(30)

    /** The lock to apply after [failedAttempts] consecutive failures, or null when no lock applies. */
    fun lockDurationAfter(failedAttempts: Int): Duration? {
        if (failedAttempts <= 0 || failedAttempts % FAILURES_PER_STEP != 0) return null
        val step = failedAttempts / FAILURES_PER_STEP - 1
        // Past ~2^6 the doubling is far beyond the cap; clamp before shifting to avoid overflow.
        val seconds = FIRST_LOCK.seconds shl step.coerceAtMost(MAX_SHIFT)
        return Duration.ofSeconds(seconds).coerceAtMost(MAX_LOCK)
    }

    /**
     * The time to use for lockout decisions. If the device clock was moved backwards, the last time
     * seen is used instead, so changing the clock cannot shorten a lock (research R8).
     */
    fun effectiveNow(now: Instant, lastSeenAt: Instant): Instant = if (now.isBefore(lastSeenAt)) lastSeenAt else now

    private const val MAX_SHIFT = 16
}

private fun Duration.coerceAtMost(max: Duration): Duration = if (this > max) max else this
