package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.IdGenerator
import com.eggbusinessmanager.domain.repository.TransactionRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A clock that only moves when the test says so. */
class FixedClock(
    var instant: Instant = Instant.parse("2026-11-07T10:00:00Z"),
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata"),
) : Clock {
    override fun now(): Instant = instant
    override fun today(): LocalDate = instant.atZone(zone).toLocalDate()
    override fun zone(): ZoneId = zone
    fun advanceBy(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }
}

/** Produces predictable ids: id-1, id-2, ... */
class SequentialIdGenerator(private val prefix: String = "id-") : IdGenerator {
    private var next = 1
    override fun newId(): String = prefix + next++
}

/** Runs the block directly; fakes keep their own consistency. */
object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
}

/** Collects log lines so tests can assert nothing sensitive was logged. */
class RecordingLogger : AppLogger {
    val lines = mutableListOf<String>()
    override fun debug(tag: String, message: () -> String) { lines += "D/$tag: ${message()}" }
    override fun info(tag: String, message: () -> String) { lines += "I/$tag: ${message()}" }
    override fun warn(tag: String, throwable: Throwable?, message: () -> String) { lines += "W/$tag: ${message()}" }
    override fun error(tag: String, throwable: Throwable?, message: () -> String) { lines += "E/$tag: ${message()}" }
}
