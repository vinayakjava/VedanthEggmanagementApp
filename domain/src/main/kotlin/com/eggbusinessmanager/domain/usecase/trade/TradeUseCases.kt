package com.eggbusinessmanager.domain.usecase.trade

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.error.ValidationCode
import com.eggbusinessmanager.domain.error.catchingResult
import com.eggbusinessmanager.domain.ledger.BalanceEffect
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.QuantityParser
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.CatalogRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.repository.TransactionRunner
import com.eggbusinessmanager.domain.trade.TradeContext
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.trade.TradeValidator
import com.eggbusinessmanager.domain.trade.ValidTrade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Looks up what a draft refers to and validates it (FR-020–FR-022). */
class TradeChecks @Inject constructor(
    private val customers: CustomerRepository,
    private val catalog: CatalogRepository,
    private val clock: Clock,
) {
    suspend fun check(draft: TradeDraft, keep: TradeContext.KeepIds = TradeContext.KeepIds()): DomainResult<ValidTrade> {
        val context = TradeContext(
            customer = draft.customerId?.let { customers.get(it) },
            variety = draft.varietyId?.let { catalog.variety(it) },
            unit = draft.unitId?.let { catalog.unit(it) }?.let { unit ->
                // An edited entry keeps its saved unit size unless the unit itself changes (FR-022).
                if (unit.id == keep.unitId && keep.unitSize != null) unit.copy(piecesPerUnit = keep.unitSize) else unit
            },
            today = clock.today(),
            keepIds = keep,
        )
        return TradeValidator.validate(draft, context)
    }
}

/**
 * Saves a SELL or BUY (US3): validated, numbered in its own series (S-/B-), its balance effect computed
 * by [BalanceEffect], audited, all in one transaction. A repeated save with the same
 * [clientRequestId] returns the first entry instead of creating a second one (US3-8).
 */
class RecordTradeUseCase @Inject constructor(
    private val checks: TradeChecks,
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(draft: TradeDraft, clientRequestId: String): DomainResult<EntryRef> = catchingResult {
        ledger.findByClientRequest(clientRequestId)?.let { return DomainResult.Ok(it) }
        val trade = when (val result = checks.check(draft)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        val now = clock.now()
        val ref = transactions.inTransaction {
            ledger.findByClientRequest(clientRequestId) ?: ledger.insertTrade(trade, clientRequestId, now).also { ref ->
                audit.append(
                    AuditEvent(
                        AuditEvent.ENTITY_LEDGER_ENTRY, ref.rootId, trade.customerId, AuditEvent.ACTION_CREATE,
                        after = trade.snapshot(), at = now,
                    ),
                )
            }
        }
        DomainResult.Ok(ref)
    }
}

/**
 * Corrects a SELL/BUY (FR-033): any field may change, including customer (both balances move together,
 * FR-033a) and type (new reference in the new series, FR-033b). The original stays as a superseded
 * revision and the audit trail records old and new values with the optional reason.
 */
class EditTradeUseCase @Inject constructor(
    private val checks: TradeChecks,
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(rootId: Long, draft: TradeDraft, reason: String = ""): DomainResult<EntryRef> = catchingResult {
        val current = ledger.currentRevision(rootId)
        if (current == null || current.status != EntryStatus.ACTIVE || current.trade == null) {
            return DomainResult.Err(if (current == null) DomainError.NotFound else DomainError.EntryNotEditable)
        }
        if (reason.trim().length > REASON_MAX) return DomainResult.Err(DomainError.Validation(Field.REASON, ValidationCode.TOO_LONG))
        val keep = TradeContext.KeepIds(current.customerId, current.trade.varietyId, current.trade.unitId, current.trade.piecesPerUnit)
        val trade = when (val result = checks.check(draft, keep)) {
            is DomainResult.Err -> return result
            is DomainResult.Ok -> result.value
        }
        if (current.sameAs(trade)) return DomainResult.Ok(EntryRef(current.rootId, current.reference))

        val now = clock.now()
        val why = reason.trim().ifEmpty { null }
        val ref = transactions.inTransaction {
            val typeChanged = trade.type != current.type
            val ref = if (typeChanged) {
                ledger.replaceTrade(current, trade, now)
            } else {
                ledger.reviseTrade(current, trade, now)
                EntryRef(current.rootId, current.reference)
            }
            val action = when {
                typeChanged -> AuditEvent.ACTION_TYPE_CHANGE
                trade.customerId != current.customerId -> AuditEvent.ACTION_CUSTOMER_CHANGE
                else -> AuditEvent.ACTION_EDIT
            }
            // Both customers get an audit row when the customer changes, so both histories show it.
            setOf(current.customerId, trade.customerId).forEach { customerId ->
                audit.append(
                    AuditEvent(
                        AuditEvent.ENTITY_LEDGER_ENTRY, current.rootId, customerId, action,
                        current.snapshot(), trade.snapshot(), why, now,
                    ),
                )
            }
            ref
        }
        DomainResult.Ok(ref)
    }

    private fun LedgerEntry.sameAs(trade: ValidTrade): Boolean {
        val line = this.trade ?: return false
        return type == trade.type && customerId == trade.customerId && date == trade.date &&
            line.varietyId == trade.varietyId && line.unitId == trade.unitId && line.quantity == trade.quantity &&
            line.piecesPerUnit == trade.piecesPerUnit && line.pricePerUnit == trade.pricePerUnit && remarks == trade.remarks
    }

    private companion object {
        const val REASON_MAX = 200
    }
}

/**
 * Deletes an entry as the owner sees it (FR-034): it becomes VOIDED, stays visible labelled
 * "Deleted", counts nowhere, and can no longer be edited. Nothing is ever removed (FR-035).
 */
class DeleteEntryUseCase @Inject constructor(
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(rootId: Long, reason: String = ""): DomainResult<Unit> = catchingResult {
        val current = ledger.currentRevision(rootId) ?: return DomainResult.Err(DomainError.NotFound)
        if (current.status != EntryStatus.ACTIVE) return DomainResult.Err(DomainError.EntryNotEditable)
        if (reason.trim().length > REASON_MAX) return DomainResult.Err(DomainError.Validation(Field.REASON, ValidationCode.TOO_LONG))
        val now = clock.now()
        val why = reason.trim().ifEmpty { null }
        transactions.inTransaction {
            ledger.voidEntry(current, why, now)
            audit.append(
                AuditEvent(
                    AuditEvent.ENTITY_LEDGER_ENTRY, current.rootId, current.customerId, AuditEvent.ACTION_VOID,
                    current.snapshot(), null, why, now,
                ),
            )
        }
        DomainResult.Ok(Unit)
    }

    private companion object {
        const val REASON_MAX = 200
    }
}

/** An entry with its whole revision history, newest revision first in [current]. */
data class EntryDetail(
    val current: LedgerEntry,
    val revisions: List<LedgerEntry>,
    /** Reference of the entry that replaced this one after a type change ("Changed to B-000004"). */
    val replacedBy: String?,
)

/** One entry and its history (US5-4), updated live. */
class ObserveEntryUseCase @Inject constructor(private val ledger: LedgerRepository) {
    operator fun invoke(rootId: Long): Flow<EntryDetail?> = ledger.observeRevisions(rootId).map { revisions ->
        val current = revisions.lastOrNull() ?: return@map null
        EntryDetail(current, revisions, current.replacedByRootId?.let { ledger.referenceOf(it) })
    }
}

/** The editor's starting values for an existing trade. */
class GetTradeDraftUseCase @Inject constructor(private val ledger: LedgerRepository) {
    suspend operator fun invoke(rootId: Long): DomainResult<TradeDraft> = catchingResult {
        val current = ledger.currentRevision(rootId) ?: return DomainResult.Err(DomainError.NotFound)
        val line = current.trade
        if (current.status != EntryStatus.ACTIVE || line == null) return DomainResult.Err(DomainError.EntryNotEditable)
        DomainResult.Ok(
            TradeDraft(
                type = current.type,
                customerId = current.customerId,
                date = current.date,
                varietyId = line.varietyId,
                unitId = line.unitId,
                quantity = QuantityParser.toInput(line.quantity),
                price = MoneyParser.toInput(line.pricePerUnit),
                remarks = current.remarks.orEmpty(),
            ),
        )
    }
}

/** FR-024: the last price used for this customer, type, variety and unit. */
class SuggestPriceUseCase @Inject constructor(private val ledger: LedgerRepository) {
    suspend operator fun invoke(customerId: Long, type: EntryType, varietyId: Long, unitId: Long): Money? =
        runCatching { ledger.lastPrice(customerId, type, varietyId, unitId) }.getOrNull()
}

/**
 * FR-033: how a correction moves each affected customer's balance, shown before saving.
 * Keys are customer ids; values are the change in that customer's balance.
 */
object EditImpact {
    fun of(current: LedgerEntry, newCustomerId: Long?, newType: EntryType, newTotal: Money?): Map<Long, Money> {
        val changes = linkedMapOf(current.customerId to -current.balanceEffect)
        if (newCustomerId != null && newTotal != null) {
            changes[newCustomerId] = (changes[newCustomerId] ?: Money.ZERO) + BalanceEffect.of(newType, newTotal)
        }
        return changes.filterValues { !it.isZero }
    }
}

private fun ValidTrade.snapshot(): Map<String, String?> = mapOf(
    "type" to type.name,
    "customer_id" to customerId.toString(),
    "date" to date.toString(),
    "variety_id" to varietyId.toString(),
    "unit_id" to unitId.toString(),
    "pieces_per_unit" to piecesPerUnit.toString(),
    "quantity_milli" to quantity.value.toString(),
    "price_paise" to pricePerUnit.paise.toString(),
    "amount_paise" to total.paise.toString(),
    "remarks" to remarks,
)

private fun LedgerEntry.snapshot(): Map<String, String?> = mapOf(
    "type" to type.name,
    "reference" to reference,
    "customer_id" to customerId.toString(),
    "date" to date.toString(),
    "variety_id" to trade?.varietyId?.toString(),
    "unit_id" to trade?.unitId?.toString(),
    "pieces_per_unit" to trade?.piecesPerUnit?.toString(),
    "quantity_milli" to trade?.quantity?.value?.toString(),
    "price_paise" to trade?.pricePerUnit?.paise?.toString(),
    "amount_paise" to amount.paise.toString(),
    "remarks" to remarks,
)
