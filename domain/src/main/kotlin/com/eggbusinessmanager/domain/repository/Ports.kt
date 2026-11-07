package com.eggbusinessmanager.domain.repository

import java.time.Instant
import java.time.LocalDate

/** Source of the current time. Injected everywhere "now" or "today" matters, so tests are deterministic. */
interface Clock {
    fun now(): Instant
    fun today(): LocalDate
}

/** Generates unique ids (e.g. idempotency keys for saves). */
interface IdGenerator {
    fun newId(): String
}

/** Runs [block] atomically: either every write inside it is committed or none is (Constitution II). */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
