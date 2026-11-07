package com.eggbusinessmanager.domain.error

import kotlin.coroutines.cancellation.CancellationException

/** Result type returned by every use case. Expected failures are values, not exceptions. */
sealed interface DomainResult<out T> {
    data class Ok<out T>(val value: T) : DomainResult<T>
    data class Err(val error: DomainError) : DomainResult<Nothing>
}

inline fun <T, R> DomainResult<T>.map(transform: (T) -> R): DomainResult<R> = when (this) {
    is DomainResult.Ok -> DomainResult.Ok(transform(value))
    is DomainResult.Err -> this
}

inline fun <T> DomainResult<T>.onOk(action: (T) -> Unit): DomainResult<T> {
    if (this is DomainResult.Ok) action(value)
    return this
}

inline fun <T> DomainResult<T>.onErr(action: (DomainError) -> Unit): DomainResult<T> {
    if (this is DomainResult.Err) action(error)
    return this
}

fun <T> DomainResult<T>.getOrNull(): T? = (this as? DomainResult.Ok)?.value

/**
 * Runs [block] and converts unexpected exceptions into [DomainError.Unexpected].
 * Coroutine cancellation is always re-thrown so structured concurrency keeps working.
 */
inline fun <T> domainCatching(block: () -> T): DomainResult<T> = catchingResult { DomainResult.Ok(block()) }

/** Like [domainCatching] for blocks that already return a [DomainResult]. */
@Suppress("TooGenericExceptionCaught") // Boundary: every unexpected failure becomes a value.
inline fun <T> catchingResult(block: () -> DomainResult<T>): DomainResult<T> = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    DomainResult.Err(DomainError.Unexpected(e))
}
