package com.eggbusinessmanager.app.feature.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionList
import com.eggbusinessmanager.domain.report.TransactionSort
import com.eggbusinessmanager.domain.report.TransactionTotals
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.SearchCustomersUseCase
import com.eggbusinessmanager.domain.usecase.report.ObserveTransactionsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** The Transactions tab (Phase 6): date range, customer, type selection, search, sort and filtered totals. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel @Inject constructor(
    private val observeTransactions: ObserveTransactionsUseCase,
    private val searchCustomers: SearchCustomersUseCase,
    private val observeCustomer: ObserveCustomerUseCase,
    clock: Clock,
) : ViewModel() {

    val today: LocalDate = clock.today()

    private val _filter = MutableStateFlow(TransactionFilter())
    val filter: StateFlow<TransactionFilter> = _filter.asStateFlow()

    /** Name of the customer in the customer filter, for its chip. */
    private val _customerName = MutableStateFlow<String?>(null)
    val customerName: StateFlow<String?> = _customerName.asStateFlow()

    data class UiState(val loading: Boolean = true, val list: TransactionList = TransactionList(emptyList(), TransactionTotals()))

    val state: StateFlow<UiState> = _filter
        .flatMapLatest { observeTransactions(it) }
        .map { UiState(loading = false, list = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), UiState())

    private val pickerQuery = MutableStateFlow("")
    val pickerText: StateFlow<String> = pickerQuery.asStateFlow()
    val pickerResults: StateFlow<List<CustomerSummary>> = pickerQuery
        .flatMapLatest { searchCustomers(CustomerQuery(it, CustomerFilter.ACTIVE, CustomerSort.NAME)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun onSearch(text: String) = _filter.update { it.copy(search = text) }

    fun onFrom(date: LocalDate?) = _filter.update { it.copy(from = date) }

    fun onTo(date: LocalDate?) = _filter.update { it.copy(to = date) }

    /** Multiple selection: toggling the last selected type leaves none selected, which lists nothing. */
    fun onToggleType(type: EntryType) = _filter.update {
        it.copy(types = if (type in it.types) it.types - type else it.types + type)
    }

    fun onSort(sort: TransactionSort) = _filter.update { it.copy(sort = sort) }

    fun onPickerSearch(text: String) {
        pickerQuery.value = text
    }

    fun onCustomer(id: Long?) {
        _filter.update { it.copy(customerId = id) }
        pickerQuery.value = ""
        if (id == null) {
            _customerName.value = null
        } else {
            viewModelScope.launch { _customerName.value = observeCustomer(id).first()?.customer?.name }
        }
    }

    fun reset() {
        _filter.value = TransactionFilter(sort = _filter.value.sort)
        _customerName.value = null
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
