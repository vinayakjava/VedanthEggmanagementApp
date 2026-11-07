package com.eggbusinessmanager.data.db

import androidx.room.withTransaction
import com.eggbusinessmanager.domain.repository.TransactionRunner
import javax.inject.Inject
import javax.inject.Provider

/** All multi-row writes go through here so they are all-or-nothing (Constitution II). */
class RoomTransactionRunner @Inject constructor(
    private val database: Provider<EggBusinessDatabase>,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.get().withTransaction { block() }
}
