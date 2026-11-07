package com.eggbusinessmanager.app.feature.customers.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.navigation.CustomerEditorRoute
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.domain.customer.CustomerDraft
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.customer.CheckDuplicateNameUseCase
import com.eggbusinessmanager.domain.usecase.customer.CreateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.GetCustomerDraftUseCase
import com.eggbusinessmanager.domain.usecase.customer.UpdateCustomerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Add or edit a customer (FR-014/015/016/019). The route's `customerId` is 0 for a new customer;
 * `prefill` carries what was typed in a search with no results (US2-5).
 */
@HiltViewModel
class CustomerEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val create: CreateCustomerUseCase,
    private val update: UpdateCustomerUseCase,
    private val loadDraft: GetCustomerDraftUseCase,
    private val duplicateName: CheckDuplicateNameUseCase,
    private val clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CustomerEditorRoute>()
    val isNew: Boolean = route.customerId == 0L
    val today: LocalDate = clock.today()

    /** Another customer that already uses the typed mobile number, offered as "Open <name>". */
    data class ExistingCustomer(val id: Long, val name: String)

    data class UiState(
        val draft: CustomerDraft = CustomerDraft(),
        val feedback: FormFeedback = FormFeedback.None,
        val duplicateNameWarning: Boolean = false,
        val existingMobileOwner: ExistingCustomer? = null,
        val loading: Boolean = false,
        val busy: Boolean = false,
        /** Set once saved: the customer's id, so the screen can navigate. */
        val savedId: Long? = null,
    )

    private val _state = MutableStateFlow(UiState(loading = !isNew))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var nameCheck: Job? = null

    init {
        if (isNew) {
            val prefill = route.prefill.trim()
            val looksLikeNumber = prefill.isNotEmpty() && prefill.all { it.isDigit() || it == ' ' || it == '+' || it == '-' }
            _state.update {
                it.copy(
                    draft = CustomerDraft(
                        name = if (looksLikeNumber) "" else prefill,
                        mobile = if (looksLikeNumber) prefill else "",
                        openingDate = today,
                    ),
                )
            }
            if (!looksLikeNumber) checkName(prefill)
        } else {
            viewModelScope.launch {
                when (val result = loadDraft(route.customerId)) {
                    is DomainResult.Ok -> _state.update {
                        it.copy(draft = result.value.copy(openingDate = result.value.openingDate ?: today), loading = false)
                    }
                    is DomainResult.Err -> _state.update { it.copy(feedback = FormFeedback.from(result.error), loading = false) }
                }
            }
        }
    }

    private fun edit(field: Field, transform: CustomerDraft.() -> CustomerDraft) = _state.update {
        it.copy(draft = it.draft.transform(), feedback = it.feedback.without(field).copy(message = null))
    }

    fun onName(value: String) {
        edit(Field.CUSTOMER_NAME) { copy(name = value) }
        checkName(value)
    }

    fun onMobile(value: String) {
        edit(Field.MOBILE) { copy(mobile = value.filter { it.isDigit() || it == ' ' }.take(MOBILE_INPUT_MAX)) }
        _state.update { it.copy(existingMobileOwner = null) }
    }

    fun onAddress(value: String) = edit(Field.ADDRESS) { copy(address = value) }
    fun onNotes(value: String) = edit(Field.NOTES) { copy(notes = value) }
    fun onOpeningAmount(value: String) =
        edit(Field.OPENING_AMOUNT) { copy(openingAmount = value.filter { it.isDigit() || it == '.' || it == ',' }) }
    fun onOpeningDirection(value: OpeningDirection) = edit(Field.OPENING_AMOUNT) { copy(openingDirection = value) }
    fun onOpeningDate(value: LocalDate) = edit(Field.OPENING_DATE) { copy(openingDate = value) }

    fun save() {
        val current = _state.value
        if (current.busy || current.loading) return
        _state.update { it.copy(busy = true, feedback = FormFeedback.None, existingMobileOwner = null) }
        viewModelScope.launch {
            val result: DomainResult<Long> = if (isNew) {
                create(current.draft)
            } else {
                when (val updated = update(route.customerId, current.draft)) {
                    is DomainResult.Ok -> DomainResult.Ok(route.customerId)
                    is DomainResult.Err -> updated
                }
            }
            _state.update { state ->
                when (result) {
                    is DomainResult.Ok -> state.copy(busy = false, savedId = result.value)
                    is DomainResult.Err -> failure(state, result.error)
                }
            }
        }
    }

    private fun failure(state: UiState, error: DomainError): UiState = when (error) {
        // US2-3: block with "This mobile number belongs to <Name>" and offer to open that customer.
        is DomainError.DuplicateMobile -> state.copy(
            busy = false,
            feedback = FormFeedback(fieldErrors = mapOf(Field.MOBILE to ErrorMessages.forError(error))),
            existingMobileOwner = ExistingCustomer(error.customerId, error.customerName),
        )
        else -> state.copy(busy = false, feedback = FormFeedback.from(error))
    }

    /** Non-blocking warning when another customer already has this name (spec § Validation Rules). */
    private fun checkName(name: String) {
        nameCheck?.cancel()
        nameCheck = viewModelScope.launch {
            delay(NAME_CHECK_DEBOUNCE_MS)
            val exists = duplicateName(name, excludingId = route.customerId.takeIf { it != 0L })
            _state.update { it.copy(duplicateNameWarning = exists) }
        }
    }

    private companion object {
        const val NAME_CHECK_DEBOUNCE_MS = 300L
        const val MOBILE_INPUT_MAX = 14
    }
}
