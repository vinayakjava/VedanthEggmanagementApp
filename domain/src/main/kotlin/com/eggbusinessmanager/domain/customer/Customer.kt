package com.eggbusinessmanager.domain.customer

import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.money.Money
import java.time.Instant
import java.time.LocalDate

/**
 * A party the shop trades with in either direction (FR-015). There is no buyer/supplier type: the
 * same customer can be sold to and bought from, and has one netted balance.
 */
data class Customer(
    val id: Long,
    val name: String,
    val mobile: String?,
    val address: String?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A customer with derived figures for lists and the detail header. */
data class CustomerSummary(
    val customer: Customer,
    val netBalance: Money,
    /** Business date of the most recent active entry, or null when there are none. */
    val lastActivity: LocalDate?,
) {
    val balance: Balance get() = Balance.of(netBalance)
}

/** An opening balance carried over from a paper book (FR-015). */
data class OpeningBalance(
    val amount: Money,
    val direction: OpeningDirection,
    val date: LocalDate,
)

/** What the owner typed in the customer editor, before validation. */
data class CustomerDraft(
    val name: String = "",
    val mobile: String = "",
    val address: String = "",
    val notes: String = "",
    val openingAmount: String = "",
    val openingDirection: OpeningDirection = OpeningDirection.CUSTOMER_OWES,
    val openingDate: LocalDate? = null,
)

/** A validated draft. [opening] is null when no opening amount was entered. */
data class ValidCustomer(
    val name: String,
    val mobile: String?,
    val address: String?,
    val notes: String?,
    val opening: OpeningBalance?,
)

/** List filters (FR-018). [ACTIVE] is the default list; [DEACTIVATED] shows only deactivated customers. */
enum class CustomerFilter { ACTIVE, TO_RECEIVE, TO_PAY, SETTLED, DEACTIVATED }

/** List orders (US2-9). */
enum class CustomerSort { NAME, HIGHEST_RECEIVABLE, HIGHEST_PAYABLE, LAST_ACTIVITY }

data class CustomerQuery(
    val text: String = "",
    val filter: CustomerFilter = CustomerFilter.ACTIVE,
    val sort: CustomerSort = CustomerSort.NAME,
)

/** Audit trail rows written in the same transaction as the change they describe (Constitution II). */
data class AuditEvent(
    val entityType: String,
    val entityId: Long,
    val customerId: Long?,
    val action: String,
    val before: Map<String, String?>? = null,
    val after: Map<String, String?>? = null,
    val reason: String? = null,
    val at: Instant,
) {
    companion object {
        const val ENTITY_CUSTOMER = "CUSTOMER"
        const val ENTITY_LEDGER_ENTRY = "LEDGER_ENTRY"
        const val ENTITY_UNIT = "UNIT"
        const val ENTITY_VARIETY = "VARIETY"
        const val ACTION_CREATE = "CREATE"
        const val ACTION_EDIT = "EDIT"
        const val ACTION_OPENING_CHANGE = "OPENING_CHANGE"
        const val ACTION_ARCHIVE = "ARCHIVE"
        const val ACTION_UNARCHIVE = "UNARCHIVE"
        const val ACTION_DELETE = "DELETE"
        const val ACTION_TYPE_CHANGE = "TYPE_CHANGE"
        const val ACTION_CUSTOMER_CHANGE = "CUSTOMER_CHANGE"
        const val ACTION_VOID = "VOID"
        const val ACTION_UNIT_RESIZE = "UNIT_RESIZE"
        const val ACTION_RENAME = "RENAME"
    }
}
