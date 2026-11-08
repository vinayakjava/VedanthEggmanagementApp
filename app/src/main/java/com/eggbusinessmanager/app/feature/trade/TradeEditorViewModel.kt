package com.eggbusinessmanager.app.feature.trade

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.navigation.TradeEditorRoute
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.settings.ObservePreferencesUseCase
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.IdGenerator
import com.eggbusinessmanager.domain.trade.TradeDraft
import com.eggbusinessmanager.domain.trade.TradePreview
import com.eggbusinessmanager.domain.trade.TradeValidator
import com.eggbusinessmanager.domain.usecase.catalog.ObserveUnitsUseCase
import com.eggbusinessmanager.domain.usecase.catalog.ObserveVarietiesUseCase
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.SearchCustomersUseCase
import com.eggbusinessmanager.domain.usecase.trade.EditImpact
import com.eggbusinessmanager.domain.usecase.trade.EditTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.GetTradeDraftUseCase
import com.eggbusinessmanager.domain.usecase.trade.ObserveEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.RecordTradeUseCase
import com.eggbusinessmanager.domain.usecase.trade.SuggestPriceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * The Sell and Buy screens (US3), and editing an existing trade (US5-4). The route's `type` picks
 * Sell or Buy; `rootId` > 0 edits that entry; `customerId` > 0 pre-selects a customer.
 */
@HiltViewModel
class TradeEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recordTrade: RecordTradeUseCase,
    private val editTrade: EditTradeUseCase,
    private val loadDraft: GetTradeDraftUseCase,
    private val suggestPrice: SuggestPriceUseCase,
    private val observeCustomer: ObserveCustomerUseCase,
    observeEntry: ObserveEntryUseCase,
    private val observeUnits: ObserveUnitsUseCase,
    observeVarieties: ObserveVarietiesUseCase,
    private val searchCustomers: SearchCustomersUseCase,
    private val observePreferences: ObservePreferencesUseCase,
    clock: Clock,
    private val ids: IdGenerator,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<TradeEditorRoute>()
    val isEdit: Boolean = route.rootId > 0
    val today: LocalDate = clock.today()

    /** What happened after a successful save: a new entry (show "Record another") or an edit (close). */
    data class Saved(val ref: EntryRef, val customerName: String, val total: Money, val isNew: Boolean)

    data class UiState(
        val draft: TradeDraft,
        val customerName: String = "",
        val reason: String = "",
        val feedback: FormFeedback = FormFeedback.None,
        val priceSuggested: Boolean = false,
        val loading: Boolean = false,
        val busy: Boolean = false,
        val saved: Saved? = null,
    )

    private val _state = MutableStateFlow(
        UiState(
            draft = TradeDraft(EntryType.valueOf(route.type), customerId = route.customerId.takeIf { it > 0 }, date = today),
            loading = isEdit,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** A new id per form: a repeated tap on Save cannot create a second entry (US3-8). */
    private var requestId = ids.newId()

    /** Units and varieties for the pickers: active ones, plus the edited entry's own if since deactivated. */
    val units: StateFlow<List<MeasurementUnit>> = combine(observeUnits(includeInactive = true), _state) { all, s ->
        all.filter { it.isActive || it.id == s.draft.unitId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    val varieties: StateFlow<List<EggVariety>> = combine(observeVarieties(includeInactive = true), _state) { all, s ->
        all.filter { it.isActive || it.id == s.draft.varietyId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    /** Live total and pieces (FR-021). */
    val preview: StateFlow<TradePreview> = combine(_state, units) { s, list ->
        TradeValidator.preview(s.draft.quantity, s.draft.price, list.firstOrNull { it.id == s.draft.unitId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), TradePreview(null, null))

    private val original = MutableStateFlow<LedgerEntry?>(null)

    /** FR-033: how saving this correction moves each affected customer's balance. */
    val impact: StateFlow<List<Pair<String, Money>>> = combine(original, _state, preview) { current, s, p ->
        if (current == null) return@combine emptyList()
        EditImpact.of(current, s.draft.customerId, s.draft.type, p.total).map { (customerId, change) -> nameOf(customerId) to change }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    private val pickerQuery = MutableStateFlow("")

    /** Customer picker: active customers, most recent activity first (FR-015). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val pickerResults: StateFlow<List<CustomerSummary>> = pickerQuery
        .flatMapLatest { searchCustomers(CustomerQuery(it, CustomerFilter.ACTIVE, CustomerSort.LAST_ACTIVITY)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    val pickerText: StateFlow<String> = pickerQuery.asStateFlow()

    init {
        if (isEdit) {
            viewModelScope.launch {
                original.value = observeEntry(route.rootId).first()?.current
                when (val result = loadDraft(route.rootId)) {
                    is DomainResult.Ok -> _state.update {
                        it.copy(draft = result.value, customerName = nameOf(result.value.customerId), loading = false)
                    }
                    is DomainResult.Err -> _state.update { it.copy(feedback = FormFeedback.from(result.error), loading = false) }
                }
            }
        } else {
            route.customerId.takeIf { it > 0 }?.let { id -> viewModelScope.launch { onCustomerPicked(id) } }
            // The default variety (FR-020) and the owner's default unit (if still active) are pre-selected.
            viewModelScope.launch {
                val default = observeVarieties().first().firstOrNull { it.isDefault }
                val unitId = observePreferences().first().defaultUnitId
                val unit = unitId?.let { id -> observeUnits(includeInactive = false).first().firstOrNull { it.id == id } }
                _state.update { s ->
                    s.copy(draft = s.draft.copy(varietyId = s.draft.varietyId ?: default?.id, unitId = s.draft.unitId ?: unit?.id))
                }
            }
        }
    }

    private suspend fun nameOf(customerId: Long?): String =
        customerId?.let { observeCustomer(it).first()?.customer?.name }.orEmpty()

    private fun edit(field: Field, transform: TradeDraft.() -> TradeDraft) = _state.update {
        it.copy(draft = it.draft.transform(), feedback = it.feedback.without(field).copy(message = null))
    }

    fun onType(type: EntryType) {
        edit(Field.TOTAL) { copy(type = type) }
        refreshSuggestedPrice()
    }

    fun onPickerSearch(text: String) {
        pickerQuery.value = text
    }

    /** From the picker, or after adding a customer inline (US3-9): the typed trade is kept. */
    fun onCustomerPicked(id: Long) {
        viewModelScope.launch {
            val name = nameOf(id)
            edit(Field.CUSTOMER) { copy(customerId = id) }
            _state.update { it.copy(customerName = name) }
            pickerQuery.value = ""
            refreshSuggestedPrice()
        }
    }

    fun onDate(value: LocalDate) = edit(Field.ENTRY_DATE) { copy(date = value) }

    fun onVariety(id: Long) {
        edit(Field.VARIETY) { copy(varietyId = id) }
        refreshSuggestedPrice()
    }

    fun onUnit(id: Long) {
        edit(Field.UNIT) { copy(unitId = id) }
        _state.update { it.copy(feedback = it.feedback.without(Field.QUANTITY)) }
        refreshSuggestedPrice()
    }

    fun onQuantity(value: String) = edit(Field.QUANTITY) { copy(quantity = value.filter { it.isDigit() || it == '.' || it == ',' }) }

    fun onPrice(value: String) {
        edit(Field.PRICE) { copy(price = value.filter { it.isDigit() || it == '.' || it == ',' }) }
        _state.update { it.copy(priceSuggested = false) }
    }

    fun onRemarks(value: String) = edit(Field.REMARKS) { copy(remarks = value) }

    fun onReason(value: String) = _state.update { it.copy(reason = value, feedback = it.feedback.without(Field.REASON)) }

    /** FR-024: pre-fill the last price for this customer, type, variety and unit, unless the owner typed one. */
    private fun refreshSuggestedPrice() {
        if (isEdit) return
        viewModelScope.launch {
            val d = _state.value.draft
            val customer = d.customerId ?: return@launch
            val variety = d.varietyId ?: return@launch
            val unit = d.unitId ?: return@launch
            val canReplace = d.price.isBlank() || _state.value.priceSuggested
            val last = suggestPrice(customer, d.type, variety, unit)
            if (canReplace && last != null) {
                _state.update { it.copy(draft = it.draft.copy(price = MoneyParser.toInput(last)), priceSuggested = true) }
            }
        }
    }

    fun save() {
        val current = _state.value
        if (current.busy || current.loading) return
        _state.update { it.copy(busy = true, feedback = FormFeedback.None) }
        viewModelScope.launch {
            val result = if (isEdit) editTrade(route.rootId, current.draft, current.reason) else recordTrade(current.draft, requestId)
            _state.update { s ->
                when (result) {
                    is DomainResult.Ok -> s.copy(
                        busy = false,
                        saved = Saved(result.value, s.customerName, preview.value.total ?: Money.ZERO, isNew = !isEdit),
                    )
                    is DomainResult.Err -> s.copy(busy = false, feedback = FormFeedback.from(result.error))
                }
            }
        }
    }

    /** "Record another": keep customer, date, variety and unit; clear the amounts; new save id. */
    fun recordAnother() {
        requestId = ids.newId()
        _state.update {
            it.copy(draft = it.draft.copy(quantity = "", price = "", remarks = ""), saved = null, priceSuggested = false)
        }
        refreshSuggestedPrice()
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
