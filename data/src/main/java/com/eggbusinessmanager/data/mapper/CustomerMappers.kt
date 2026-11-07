package com.eggbusinessmanager.data.mapper

import com.eggbusinessmanager.data.db.dao.CustomerSummaryRow
import com.eggbusinessmanager.data.db.entity.CustomerEntity
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.validation.SearchKey
import java.time.Instant
import java.time.LocalDate

internal fun ValidCustomer.toEntity(at: Instant) = CustomerEntity(
    name = name,
    nameNormalized = SearchKey.of(name),
    mobile = mobile,
    address = address,
    notes = notes,
    createdAt = at.toEpochMilli(),
    updatedAt = at.toEpochMilli(),
)

internal fun CustomerEntity.toDomain() = Customer(
    id = id,
    name = name,
    mobile = mobile,
    address = address,
    notes = notes,
    isActive = !isArchived,
    createdAt = Instant.ofEpochMilli(createdAt),
    updatedAt = Instant.ofEpochMilli(updatedAt),
)

internal fun CustomerSummaryRow.toDomain() = CustomerSummary(
    customer = customer.toDomain(),
    netBalance = Money(netBalancePaise),
    lastActivity = lastActivityDay?.let(LocalDate::ofEpochDay),
)

internal fun LedgerEntryEntity.toDomain() = LedgerEntry(
    id = id,
    rootId = rootEntryId,
    reference = referenceNo,
    type = EntryType.valueOf(entryType),
    status = EntryStatus.valueOf(status),
    customerId = customerId,
    date = LocalDate.ofEpochDay(entryDate),
    amount = Money(amountPaise),
    balanceEffect = Money(balanceEffectPaise),
    openingDirection = openingDirection?.let(OpeningDirection::valueOf),
    remarks = remarks,
    createdAt = Instant.ofEpochMilli(createdAt),
)
