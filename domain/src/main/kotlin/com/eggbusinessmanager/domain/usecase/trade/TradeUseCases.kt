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
import com.eggbusinessmanager.domain.trade.paymentValues
import com.eggbusinessmanager.domain.trade.toValues
import com.eggbusinessmanager.domain.usecase.entry.EntryWriter
import com.eggbusinessmanager.domain.usecase.entry.snapshot
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
 * Saves a SELL or BUY (US3): validated, numbered in its own series (S-/B-), stored and audited in one
 * transaction by [EntryWriter]. A repeated save with the same [clientRequestId] returns the first
 * entry instead of creating a second one (US3-8).
 */
class RecordTradeUseCase @Inject constructor(
    private val checks: TradeChecks,
    private val writer: EntryWriter,
) {
    suspend operator fun invoke(draft: TradeDraft, clientRequestId: String): DomainResult<EntryRef> = catchingResult {
        writer.existing(clientRequestId)?.let { return DomainResult.Ok(it) }
        when (val result = checks.check(draft)) {
            is DomainResult.Err -> result
            is DomainResult.Ok -> {
                val trade = result.value
                val payment = trade.paymentValues()
                DomainResult.Ok(
                    if (payment == null) writer.record(trade.toValues(), clientRequestId)
                    else writer.recordLinked(trade.toValues(), payment, clientRequestId),
                )
            }
        }
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
    private val writer: EntryWriter,
) {
    suspend operator fun invoke(rootId: Long, draft: TradeDraft, reason: String = ""): DomainResult<EntryRef> = catchingResult {
        val current = ledger.currentRevision(rootId)
        if (current == null || current.status != EntryStatus.ACTIVE || current.trade == null) {
            return DomainResult.Err(if (current == null) DomainError.NotFound else DomainError.EntryNotEditable)
        }
        if (reason.trim().length > REASON_MAX) return DomainResult.Err(DomainError.Validation(Field.REASON, ValidationCode.TOO_LONG))
        val keep = TradeContext.KeepIds(current.customerId, current.trade.varietyId, current.trade.unitId, current.trade.piecesPerUnit)
        when (val result = checks.check(draft, keep)) {
            is DomainResult.Err -> result
            is DomainResult.Ok -> DomainResult.Ok(writer.edit(current, result.value.toValues(), reason.trim().ifEmpty { null }))
        }
    }

    private companion object {
        const val REASON_MAX = 200
    }
}

/**
 * Deletes an entry as the owner sees it (FR-034): it becomes VOIDED, stays visible labelled
 * "Deleted", counts nowhere, and can no longer be edited. Nothing is ever removed (FR-035).
 * With [alsoLinked], the payment saved with a trade (or the trade saved with a payment) goes too.
 */
class DeleteEntryUseCase @Inject constructor(
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    suspend operator fun invoke(rootId: Long, reason: String = "", alsoLinked: Boolean = false): DomainResult<Unit> = catchingResult {
        val current = ledger.currentRevision(rootId) ?: return DomainResult.Err(DomainError.NotFound)
        if (current.status != EntryStatus.ACTIVE) return DomainResult.Err(DomainError.EntryNotEditable)
        if (reason.trim().length > REASON_MAX) return DomainResult.Err(DomainError.Validation(Field.REASON, ValidationCode.TOO_LONG))
        val now = clock.now()
        val why = reason.trim().ifEmpty { null }
        transactions.inTransaction {
            void(current, why, now)
            if (alsoLinked) {
                val linked = current.linkedRootId?.let { ledger.currentRevision(it) }
                if (linked?.status == EntryStatus.ACTIVE) void(linked, why, now)
            }
        }
        DomainResult.Ok(Unit)
    }

    private suspend fun void(entry: LedgerEntry, why: String?, now: java.time.Instant) {
        ledger.voidEntry(entry, why, now)
        audit.append(
            AuditEvent(
                AuditEvent.ENTITY_LEDGER_ENTRY, entry.rootId, entry.customerId, AuditEvent.ACTION_VOID, entry.snapshot(), null, why, now,
            ),
        )
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
    /** FR-023: the entry saved together with this one (the sale's on-the-spot payment, or that payment's sale). */
    val linked: LedgerEntry? = null,
)

/** One entry and its history (US5-4), updated live. */
class ObserveEntryUseCase @Inject constructor(private val ledger: LedgerRepository) {
    operator fun invoke(rootId: Long): Flow<EntryDetail?> = ledger.observeRevisions(rootId).map { revisions ->
        val current = revisions.lastOrNull() ?: return@map null
        EntryDetail(
            current,
            revisions,
            current.replacedByRootId?.let { ledger.referenceOf(it) },
            current.linkedRootId?.let { ledger.currentRevision(it) },
        )
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
