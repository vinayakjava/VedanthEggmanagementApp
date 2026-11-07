package com.eggbusinessmanager.data.security

import com.eggbusinessmanager.domain.model.account.HashedSecret
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

class Pbkdf2SecretHasherTest {

    // Low iteration count keeps the suite fast; the production default is asserted separately.
    private val hasher = Pbkdf2SecretHasher(Dispatchers.Unconfined, iterations = 1_000)

    @Test
    fun `production cost follows OWASP guidance`() {
        assertThat(Pbkdf2SecretHasher.DEFAULT_ITERATIONS).isAtLeast(600_000)
    }

    @Test
    fun `hash verifies the same secret and rejects others`() = runTest {
        val hashed = hasher.hash("eggs2024")

        assertThat(hasher.verify("eggs2024", hashed)).isTrue()
        assertThat(hasher.verify("Eggs2024", hashed)).isFalse()
        assertThat(hasher.verify("", hashed)).isFalse()
    }

    @Test
    fun `output is a 32-byte key with a 16-byte random salt and the stored cost`() = runTest {
        val hashed = hasher.hash("482916")

        assertThat(hashed.hash).hasLength(32)
        assertThat(hashed.salt).hasLength(16)
        assertThat(hashed.iterations).isEqualTo(1_000)
        assertThat(hashed.hash).isNotEqualTo("482916".toByteArray())
    }

    @Test
    fun `the same secret hashed twice gets different salts and hashes`() = runTest {
        val first = hasher.hash("same-secret1")
        val second = hasher.hash("same-secret1")

        assertThat(first.salt).isNotEqualTo(second.salt)
        assertThat(first.hash).isNotEqualTo(second.hash)
    }

    @Test
    fun `verification honours the iterations stored with the hash`() = runTest {
        val old = Pbkdf2SecretHasher(Dispatchers.Unconfined, iterations = 500).hash("legacy1pass")

        assertThat(hasher.verify("legacy1pass", old)).isTrue()
        val tampered = HashedSecret(old.hash, old.salt, iterations = 501)
        assertThat(hasher.verify("legacy1pass", tampered)).isFalse()
    }

    @Test
    fun `non-ASCII secrets such as Hindi answers round-trip`() = runTest {
        val hashed = hasher.hash("नासिक")
        assertThat(hasher.verify("नासिक", hashed)).isTrue()
        assertThat(hasher.verify("नासीक", hashed)).isFalse()
    }
}
