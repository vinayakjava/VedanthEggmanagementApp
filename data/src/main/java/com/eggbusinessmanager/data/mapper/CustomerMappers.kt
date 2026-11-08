package com.eggbusinessmanager.data.mapper

import com.eggbusinessmanager.data.db.dao.CustomerSummaryRow
import com.eggbusinessmanager.data.db.entity.CustomerEntity
import com.eggbusinessmanager.data.db.entity.LedgerEntryEntity
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.ledger.BalanceEffect
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.ledger.TradeLine
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityMilli
import com.eggbusinessmanager.domain.trade.ValidTrade
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
    trade = tradeLine(),
    supersedesId = supersedesEntryId,
    replacedByRootId = replacedByRootId,
    voidReason = voidReason,
    voidedAt = voidedAt?.let(Instant::ofEpochMilli),
)

/** Trade columns are all set for SELL/BUY and all null otherwise (enforced by SchemaCallback triggers). */
private fun LedgerEntryEntity.tradeLine(): TradeLine? {
    val variety = varietyId ?: return null
    return TradeLine(
        varietyId = variety,
        unitId = checkNotNull(unitId),
        quantity = QuantityMilli(checkNotNull(quantityMilli)),
        piecesPerUnit = checkNotNull(piecesPerUnitSnapshot),
        pieces = checkNotNull(pieces),
        pricePerUnit = Money(checkNotNull(pricePerUnitPaise)),
    )
}

internal fun ValidTrade.toEntity(rootId: Long, reference: String, clientRequestId: String?, at: Instant) = LedgerEntryEntity(
    rootEntryId = rootId,
    entryType = type.name,
    referenceNo = reference,
    status = EntryStatus.ACTIVE.name,
    customerId = customerId,
    entryDate = date.toEpochDay(),
    amountPaise = total.paise,
    balanceEffectPaise = BalanceEffect.of(type, total).paise,
    varietyId = varietyId,
    unitId = unitId,
    quantityMilli = quantity.value,
    piecesPerUnitSnapshot = piecesPerUnit,
    pieces = pieces,
    pricePerUnitPaise = pricePerUnit.paise,
    remarks = remarks,
    clientRequestId = clientRequestId,
    createdAt = at.toEpochMilli(),
)
