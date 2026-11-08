package com.eggbusinessmanager.domain.repository

import java.time.Instant
import java.time.LocalDate

/** Source of the current time. Injected everywhere "now" or "today" matters, so tests are deterministic. */
interface Clock {
    fun now(): Instant
    fun today(): LocalDate

    /** The phone's time zone, for wall-clock schedules such as "back up at 2 AM". */
    fun zone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
}

/** Generates unique ids (e.g. idempotency keys for saves). */
interface IdGenerator {
    fun newId(): String
}

/** Runs [block] atomically: either every write inside it is committed or none is (Constitution II). */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}
