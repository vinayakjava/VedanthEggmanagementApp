package com.eggbusinessmanager.app.feature.customers.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.usecase.customer.SearchCustomersUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Customer list with search-as-you-type, filter and sort (FR-017, FR-018). */
@HiltViewModel
class CustomersViewModel @Inject constructor(
    search: SearchCustomersUseCase,
) : ViewModel() {

    data class UiState(
        val query: CustomerQuery = CustomerQuery(),
        val customers: List<CustomerSummary> = emptyList(),
        val loading: Boolean = true,
    )

    private val query = MutableStateFlow(CustomerQuery())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val results = query
        .debounce { if (it.text.isEmpty()) 0 else TYPING_DEBOUNCE_MS }
        .flatMapLatest { search(it) }

    val state: StateFlow<UiState> = combine(query, results) { q, list -> UiState(q, list, loading = false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), UiState())

    /** The query as typed, updated immediately so the text field never lags. */
    val queryText: StateFlow<CustomerQuery> = query.asStateFlow()

    fun onSearch(text: String) = query.update { it.copy(text = text) }
    fun onFilter(filter: CustomerFilter) = query.update { it.copy(filter = filter) }
    fun onSort(sort: CustomerSort) = query.update { it.copy(sort = sort) }

    private companion object {
        const val TYPING_DEBOUNCE_MS = 150L
        const val STOP_MS = 5_000L
    }
}
