package com.eggbusinessmanager.domain.error

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class DomainResultTest {

    @Test
    fun `map transforms Ok and keeps Err`() {
        val ok: DomainResult<Int> = DomainResult.Ok(2)
        val err: DomainResult<Int> = DomainResult.Err(DomainError.NotFound)

        assertThat(ok.map { it * 10 }).isEqualTo(DomainResult.Ok(20))
        assertThat(err.map { it * 10 }).isEqualTo(err)
    }

    @Test
    fun `domainCatching converts exceptions to Unexpected`() {
        val boom = IllegalStateException("boom")
        val result = domainCatching<Int> { throw boom }

        assertThat(result).isInstanceOf(DomainResult.Err::class.java)
        val error = (result as DomainResult.Err).error
        assertThat(error).isEqualTo(DomainError.Unexpected(boom))
    }

    @Test
    fun `domainCatching rethrows cancellation`() {
        assertThrows(CancellationException::class.java) {
            domainCatching<Int> { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun `onOk and onErr run only for the matching case`() {
        val seen = mutableListOf<String>()
        val ok: DomainResult<Int> = DomainResult.Ok(1)
        val err: DomainResult<Int> = DomainResult.Err(DomainError.NotFound)

        ok.onOk { seen += "ok:$it" }.onErr { seen += "err-on-ok" }
        err.onOk { seen += "ok-on-err" }.onErr { seen += "err:$it" }

        assertThat(seen).containsExactly("ok:1", "err:NotFound").inOrder()
    }

    @Test
    fun `domainCatching wraps a successful value`() {
        assertThat(domainCatching { 42 }).isEqualTo(DomainResult.Ok(42))
    }

    @Test
    fun `getOrNull returns value only for Ok`() {
        assertThat(DomainResult.Ok("x").getOrNull()).isEqualTo("x")
        val err: DomainResult<String> = DomainResult.Err(DomainError.LowStorage)
        assertThat(err.getOrNull()).isNull()
    }
}
