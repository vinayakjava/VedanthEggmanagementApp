package com.eggbusinessmanager.app.feature.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.navigation.PaymentEditorRoute
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.money.MoneyParser
import com.eggbusinessmanager.domain.payment.PaymentDraft
import com.eggbusinessmanager.domain.payment.PaymentImpact
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.repository.EntryRef
import com.eggbusinessmanager.domain.repository.IdGenerator
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.SearchCustomersUseCase
import com.eggbusinessmanager.domain.usecase.payment.EditPaymentUseCase
import com.eggbusinessmanager.domain.usecase.payment.GetPaymentDraftUseCase
import com.eggbusinessmanager.domain.usecase.payment.PreviewPaymentUseCase
import com.eggbusinessmanager.domain.usecase.payment.RecordPaymentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Receive payment / Pay customer (US4, FR-028), and editing a saved payment. The route's `type` picks
 * the direction; `rootId` > 0 edits that payment; `customerId` > 0 pre-selects a customer.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val recordPayment: RecordPaymentUseCase,
    private val editPayment: EditPaymentUseCase,
    private val loadDraft: GetPaymentDraftUseCase,
    private val previewPayment: PreviewPaymentUseCase,
    private val observeCustomer: ObserveCustomerUseCase,
    private val searchCustomers: SearchCustomersUseCase,
    clock: Clock,
    private val ids: IdGenerator,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PaymentEditorRoute>()
    val isEdit: Boolean = route.rootId > 0
    val today: LocalDate = clock.today()

    data class Saved(val ref: EntryRef, val customerName: String, val amount: Money, val isNew: Boolean)

    data class UiState(
        val draft: PaymentDraft,
        val customerName: String = "",
        val reason: String = "",
        val feedback: FormFeedback = FormFeedback.None,
        val loading: Boolean = false,
        val busy: Boolean = false,
        /** Set while the owner confirms a payment that creates an advance (FR-030). */
        val confirmAdvance: Money? = null,
        val saved: Saved? = null,
    )

    private val _state = MutableStateFlow(
        UiState(
            draft = PaymentDraft(EntryType.valueOf(route.type), customerId = route.customerId.takeIf { it > 0 }, date = today),
            loading = isEdit,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** A new id per form: a repeated tap on Save cannot create a second payment. */
    private var requestId = ids.newId()

    /**
     * What is owed in this direction, the advance if any, and the balance after (US4-2/3/4). Recomputed
     * from the ledger whenever customer, direction or amount change; the edited payment itself is left out.
     */
    val impact: StateFlow<PaymentImpact?> = _state
        .map { Triple(it.draft.customerId, it.draft.type, it.draft.amount) }
        .distinctUntilChanged()
        .mapLatest { (customerId, type, amount) ->
            customerId ?: return@mapLatest null
            val money = (MoneyParser.parse(amount) as? MoneyParser.Result.Valid)?.money ?: Money.ZERO
            previewPayment(customerId, type, money, editingRootId = route.rootId.takeIf { it > 0 })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    private val pickerQuery = MutableStateFlow("")

    val pickerResults: StateFlow<List<CustomerSummary>> = pickerQuery
        .flatMapLatest { searchCustomers(CustomerQuery(it, CustomerFilter.ACTIVE, CustomerSort.LAST_ACTIVITY)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    val pickerText: StateFlow<String> = pickerQuery.asStateFlow()

    init {
        if (isEdit) {
            viewModelScope.launch {
                when (val result = loadDraft(route.rootId)) {
                    is DomainResult.Ok -> _state.update {
                        it.copy(draft = result.value, customerName = nameOf(result.value.customerId), loading = false)
                    }
                    is DomainResult.Err -> _state.update { it.copy(feedback = FormFeedback.from(result.error), loading = false) }
                }
            }
        } else {
            route.customerId.takeIf { it > 0 }?.let(::onCustomerPicked)
        }
    }

    private suspend fun nameOf(customerId: Long?): String =
        customerId?.let { observeCustomer(it).first()?.customer?.name }.orEmpty()

    private fun edit(field: Field, transform: PaymentDraft.() -> PaymentDraft) = _state.update {
        it.copy(draft = it.draft.transform(), feedback = it.feedback.without(field).copy(message = null))
    }

    fun onType(type: EntryType) = edit(Field.AMOUNT) { copy(type = type) }

    fun onPickerSearch(text: String) {
        pickerQuery.value = text
    }

    fun onCustomerPicked(id: Long) {
        viewModelScope.launch {
            val name = nameOf(id)
            edit(Field.CUSTOMER) { copy(customerId = id) }
            _state.update { it.copy(customerName = name) }
            pickerQuery.value = ""
        }
    }

    fun onDate(value: LocalDate) = edit(Field.ENTRY_DATE) { copy(date = value) }

    fun onAmount(value: String) = edit(Field.AMOUNT) { copy(amount = value.filter { it.isDigit() || it == '.' || it == ',' }) }

    /** US4-4: fills exactly what is owed in this direction. */
    fun onFullAmount() {
        val outstanding = impact.value?.outstanding ?: return
        if (outstanding.isPositive) onAmount(MoneyParser.toInput(outstanding))
    }

    fun onMode(mode: PaymentMode) = edit(Field.PAYMENT_REFERENCE) { copy(mode = mode) }

    fun onReference(value: String) = edit(Field.PAYMENT_REFERENCE) { copy(reference = value) }

    fun onRemarks(value: String) = edit(Field.REMARKS) { copy(remarks = value) }

    fun onReason(value: String) = _state.update { it.copy(reason = value, feedback = it.feedback.without(Field.REASON)) }

    /** Saves, but first asks for confirmation when the payment goes beyond what is owed (FR-030). */
    fun save() {
        val current = _state.value
        if (current.busy || current.loading) return
        viewModelScope.launch {
            val advance = currentImpact()?.advance
            if (advance != null && advance.isPositive && current.confirmAdvance == null) {
                _state.update { it.copy(confirmAdvance = advance) }
            } else {
                commit()
            }
        }
    }

    fun confirmAdvance() {
        commit()
    }

    fun cancelAdvance() = _state.update { it.copy(confirmAdvance = null) }

    /** The impact for the current input, computed now (the shared flow may not be collected yet). */
    private suspend fun currentImpact(): PaymentImpact? {
        val d = _state.value.draft
        val money = (MoneyParser.parse(d.amount) as? MoneyParser.Result.Valid)?.money
        val customerId = d.customerId
        if (customerId == null || money == null) return null
        return previewPayment(customerId, d.type, money, editingRootId = route.rootId.takeIf { it > 0 })
    }

    private fun commit() {
        val current = _state.value
        if (current.busy) return
        _state.update { it.copy(busy = true, confirmAdvance = null, feedback = FormFeedback.None) }
        viewModelScope.launch {
            val result = if (isEdit) editPayment(route.rootId, current.draft, current.reason) else recordPayment(current.draft, requestId)
            _state.update { s ->
                when (result) {
                    is DomainResult.Ok -> s.copy(
                        busy = false,
                        saved = Saved(
                            result.value,
                            s.customerName,
                            (MoneyParser.parse(s.draft.amount) as? MoneyParser.Result.Valid)?.money ?: Money.ZERO,
                            isNew = !isEdit,
                        ),
                    )
                    is DomainResult.Err -> s.copy(busy = false, feedback = FormFeedback.from(result.error))
                }
            }
        }
    }

    /** "Record another": keep customer, direction, date and mode; clear the amount; new save id. */
    fun recordAnother() {
        requestId = ids.newId()
        _state.update { it.copy(draft = it.draft.copy(amount = "", reference = "", remarks = ""), saved = null) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
