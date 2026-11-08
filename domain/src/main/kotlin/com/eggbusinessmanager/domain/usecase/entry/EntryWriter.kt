package com.eggbusinessmanager.domain.usecase.entry

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.ledger.EntryValues
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.LedgerRepository
import com.eggbusinessmanager.domain.repository.TransactionRunner
import javax.inject.Inject

/**
 * The single write path for trades and payments (Constitution II): every save and correction runs in
 * one database transaction together with its audit rows, and the balance effect is always derived by
 * the repository from [com.eggbusinessmanager.domain.ledger.BalanceEffect]. Balances are never stored,
 * so edits and deletes of historical entries are reflected everywhere automatically.
 */
class EntryWriter @Inject constructor(
    private val ledger: LedgerRepository,
    private val audit: AuditRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    /** The entry an earlier save with this request id created, if any (double-tap protection). */
    suspend fun existing(clientRequestId: String): EntryRef? = ledger.findByClientRequest(clientRequestId)?.let { ref ->
        val linkedRoot = ledger.currentRevision(ref.rootId)?.linkedRootId ?: return@let ref
        ref.copy(linkedReference = ledger.referenceOf(linkedRoot))
    }

    suspend fun record(values: EntryValues, clientRequestId: String): EntryRef {
        val now = clock.now()
        return transactions.inTransaction {
            ledger.findByClientRequest(clientRequestId) ?: insertAudited(values, clientRequestId, now)
        }
    }

    /**
     * Saves a trade and the payment taken on the spot together (FR-023): both or neither, each with its
     * own reference and audit row, linked to each other.
     */
    suspend fun recordLinked(values: EntryValues, linked: EntryValues, clientRequestId: String): EntryRef {
        val now = clock.now()
        return transactions.inTransaction {
            ledger.findByClientRequest(clientRequestId) ?: run {
                val first = insertAudited(values, clientRequestId, now)
                val second = insertAudited(linked, clientRequestId + LINKED_SUFFIX, now)
                ledger.link(first.rootId, second.rootId)
                first.copy(linkedReference = second.reference)
            }
        }
    }

    private suspend fun insertAudited(values: EntryValues, clientRequestId: String, now: java.time.Instant): EntryRef =
        ledger.insertEntry(values, clientRequestId, now).also { ref ->
            audit.append(
                AuditEvent(
                    AuditEvent.ENTITY_LEDGER_ENTRY, ref.rootId, values.customerId, AuditEvent.ACTION_CREATE,
                    after = values.snapshot(), at = now,
                ),
            )
        }

    /**
     * Corrects [current] (FR-033): the original stays as a superseded revision. A type change creates a
     * new entry in the new type's series (FR-033b); a customer change moves the amount between both
     * customers in the same transaction (FR-033a), and both get an audit row.
     */
    suspend fun edit(current: LedgerEntry, values: EntryValues, reason: String?): EntryRef {
        if (current.toValues() == values) return EntryRef(current.rootId, current.reference)
        val now = clock.now()
        return transactions.inTransaction {
            val typeChanged = values.type != current.type
            val ref = if (typeChanged) {
                // The linked payment follows the trade to its new reference.
                ledger.replaceEntry(current, values, now).also { new -> current.linkedRootId?.let { ledger.link(new.rootId, it) } }
            } else {
                ledger.reviseEntry(current, values, now)
                EntryRef(current.rootId, current.reference)
            }
            val action = when {
                typeChanged -> AuditEvent.ACTION_TYPE_CHANGE
                values.customerId != current.customerId -> AuditEvent.ACTION_CUSTOMER_CHANGE
                else -> AuditEvent.ACTION_EDIT
            }
            setOf(current.customerId, values.customerId).forEach { customerId ->
                audit.append(
                    AuditEvent(
                        AuditEvent.ENTITY_LEDGER_ENTRY, current.rootId, customerId, action,
                        current.snapshot(), values.snapshot(), reason, now,
                    ),
                )
            }
            ref
        }
    }
}

private const val LINKED_SUFFIX = ":paid-now"

/** The stored values of an entry, for comparing with an edit. */
fun LedgerEntry.toValues() = EntryValues(type, customerId, date, amount, trade, payment, remarks)

/** Business fields only, never secrets (data-model.md § audit_log). */
fun EntryValues.snapshot(): Map<String, String?> = mapOf(
    "type" to type.name,
    "customer_id" to customerId.toString(),
    "date" to date.toString(),
    "amount_paise" to amount.paise.toString(),
    "variety_id" to trade?.varietyId?.toString(),
    "unit_id" to trade?.unitId?.toString(),
    "pieces_per_unit" to trade?.piecesPerUnit?.toString(),
    "quantity_milli" to trade?.quantity?.value?.toString(),
    "price_paise" to trade?.pricePerUnit?.paise?.toString(),
    "payment_mode" to payment?.mode?.name,
    "payment_reference" to payment?.reference,
    "remarks" to remarks,
)

fun LedgerEntry.snapshot(): Map<String, String?> = toValues().snapshot() + ("reference" to reference)
