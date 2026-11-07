package com.eggbusinessmanager.domain.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LockoutPolicyTest {

    @Test
    fun `no lock before the 5th failure or between steps`() {
        (0..4).forEach { assertThat(LockoutPolicy.lockDurationAfter(it)).isNull() }
        (6..9).forEach { assertThat(LockoutPolicy.lockDurationAfter(it)).isNull() }
    }

    @Test
    fun `lock doubles every 5 failures`() {
        assertThat(LockoutPolicy.lockDurationAfter(5)).isEqualTo(Duration.ofSeconds(30))
        assertThat(LockoutPolicy.lockDurationAfter(10)).isEqualTo(Duration.ofSeconds(60))
        assertThat(LockoutPolicy.lockDurationAfter(15)).isEqualTo(Duration.ofSeconds(120))
        assertThat(LockoutPolicy.lockDurationAfter(30)).isEqualTo(Duration.ofSeconds(960))
    }

    @Test
    fun `lock is capped at 30 minutes, even after very many failures`() {
        assertThat(LockoutPolicy.lockDurationAfter(35)).isEqualTo(Duration.ofMinutes(30))
        assertThat(LockoutPolicy.lockDurationAfter(5_000)).isEqualTo(Duration.ofMinutes(30))
    }

    @Test
    fun `a clock moved backwards does not shorten a lock`() {
        val seen = Instant.parse("2026-11-08T10:00:00Z")
        assertThat(LockoutPolicy.effectiveNow(seen.minusSeconds(3600), seen)).isEqualTo(seen)
        assertThat(LockoutPolicy.effectiveNow(seen.plusSeconds(5), seen)).isEqualTo(seen.plusSeconds(5))
    }
}
