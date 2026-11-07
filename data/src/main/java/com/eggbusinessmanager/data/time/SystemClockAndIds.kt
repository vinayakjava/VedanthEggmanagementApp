package com.eggbusinessmanager.data.time

import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.IdGenerator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Device clock in the device's current time zone (business dates use the local calendar, BR-016). */
class SystemClock @Inject constructor() : Clock {
    override fun now(): Instant = Instant.now()
    override fun today(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}

class UuidIdGenerator @Inject constructor() : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}
