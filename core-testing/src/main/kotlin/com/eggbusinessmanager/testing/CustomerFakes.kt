package com.eggbusinessmanager.testing

import com.eggbusinessmanager.domain.customer.AuditEvent
import com.eggbusinessmanager.domain.customer.Customer
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.customer.ValidCustomer
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.LedgerCalculator
import com.eggbusinessmanager.domain.repository.AuditRepository
import com.eggbusinessmanager.domain.repository.CustomerRepository
import com.eggbusinessmanager.domain.validation.SearchKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant

/** In-memory customers whose balances come from [ledger] via [LedgerCalculator], like the SQL aggregate. */
class FakeCustomerRepository(private val ledger: FakeLedgerRepository) : CustomerRepository {
    val customers = MutableStateFlow<List<Customer>>(emptyList())
    private var nextId = 1L

    private fun summaries(): Flow<List<CustomerSummary>> = combine(customers, ledger.rows) { list, rows ->
        list.map { customer ->
            val entries = rows.filter { it.customerId == customer.id }
            CustomerSummary(
                customer = customer,
                netBalance = LedgerCalculator.balance(entries),
                lastActivity = entries.filter { it.status == EntryStatus.ACTIVE }.maxOfOrNull { it.date },
            )
        }
    }

    override fun search(query: CustomerQuery): Flow<List<CustomerSummary>> = summaries().map { all ->
        all.filter { matchesText(it, query.text) && matchesFilter(it, query.filter) }.sortedWith(order(query.sort))
    }

    private fun matchesText(summary: CustomerSummary, query: String): Boolean {
        val text = SearchKey.of(query)
        val digits = SearchKey.digits(query)
        return text.isEmpty() || SearchKey.of(summary.customer.name).contains(text) ||
            (digits.isNotEmpty() && summary.customer.mobile?.contains(digits) == true)
    }

    private fun matchesFilter(summary: CustomerSummary, filter: CustomerFilter): Boolean {
        val active = summary.customer.isActive
        return when (filter) {
            CustomerFilter.ACTIVE -> active
            CustomerFilter.TO_RECEIVE -> active && summary.netBalance.isPositive
            CustomerFilter.TO_PAY -> active && summary.netBalance.isNegative
            CustomerFilter.SETTLED -> active && summary.netBalance.isZero
            CustomerFilter.DEACTIVATED -> !active
        }
    }

    private fun order(sort: CustomerSort): Comparator<CustomerSummary> = when (sort) {
        CustomerSort.NAME -> compareBy { SearchKey.of(it.customer.name) }
        CustomerSort.HIGHEST_RECEIVABLE -> compareByDescending { it.netBalance.paise }
        CustomerSort.HIGHEST_PAYABLE -> compareBy { it.netBalance.paise }
        CustomerSort.LAST_ACTIVITY -> compareByDescending { it.lastActivity }
    }

    override fun observe(id: Long): Flow<CustomerSummary?> = summaries().map { all -> all.firstOrNull { it.customer.id == id } }

    override suspend fun get(id: Long): Customer? = customers.value.firstOrNull { it.id == id }

    override suspend fun findByMobile(mobile: String): Customer? = customers.value.firstOrNull { it.mobile == mobile }

    override suspend fun nameExists(name: String, excludingId: Long?): Boolean =
        customers.value.any { it.id != excludingId && SearchKey.of(it.name) == SearchKey.of(name) }

    override suspend fun insert(customer: ValidCustomer, at: Instant): Long {
        val id = nextId++
        customers.value += Customer(id, customer.name, customer.mobile, customer.address, customer.notes, true, at, at)
        return id
    }

    override suspend fun update(id: Long, customer: ValidCustomer, at: Instant) = change(id) {
        it.copy(name = customer.name, mobile = customer.mobile, address = customer.address, notes = customer.notes, updatedAt = at)
    }

    override suspend fun setActive(id: Long, active: Boolean, at: Instant) = change(id) { it.copy(isActive = active, updatedAt = at) }

    override suspend fun deleteIfUnused(id: Long): Boolean {
        if (ledger.rows.value.any { it.customerId == id }) return false
        customers.value = customers.value.filterNot { it.id == id }
        return true
    }

    private fun change(id: Long, transform: (Customer) -> Customer) {
        customers.value = customers.value.map { if (it.id == id) transform(it) else it }
    }
}

class FakeAuditRepository : AuditRepository {
    val events = mutableListOf<AuditEvent>()
    override suspend fun append(event: AuditEvent) {
        events += event
    }
}
