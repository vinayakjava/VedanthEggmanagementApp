package com.eggbusinessmanager.data.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreKeyWrapperTest {

    private val wrapper = KeystoreKeyWrapper()
    private val alias = "ebm_test_wrap"

    @Test
    fun wrapThenUnwrapReturnsOriginal() {
        val secret = ByteArray(32) { it.toByte() }
        val wrapped = wrapper.wrap(alias, secret)

        assertThat(wrapped.ciphertext).isNotEqualTo(secret)
        assertThat(wrapper.unwrap(alias, wrapped)).isEqualTo(secret)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val wrapped = wrapper.wrap(alias, ByteArray(32) { 7 })
        val tampered = wrapped.copy(ciphertext = wrapped.ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() })

        assertThrows(KeyUnavailableException::class.java) { wrapper.unwrap(alias, tampered) }
    }

    @Test
    fun missingAliasIsReportedAsUnavailable() {
        val wrapped = wrapper.wrap(alias, ByteArray(32))
        assertThrows(KeyUnavailableException::class.java) { wrapper.unwrap("ebm_alias_that_does_not_exist", wrapped) }
    }
}
