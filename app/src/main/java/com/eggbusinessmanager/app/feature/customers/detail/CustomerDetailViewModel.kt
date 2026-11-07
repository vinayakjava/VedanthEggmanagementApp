package com.eggbusinessmanager.app.feature.customers.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.navigation.CustomerDetailRoute
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.usecase.customer.ActivateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.CanDeleteCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.DeactivateCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.DeleteCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One customer: balance, contact, and deactivate/activate/delete with the confirmations of US2-6/7/8. */
@HiltViewModel
class CustomerDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observe: ObserveCustomerUseCase,
    private val deactivateCustomer: DeactivateCustomerUseCase,
    private val activateCustomer: ActivateCustomerUseCase,
    private val canDelete: CanDeleteCustomerUseCase,
    private val deleteCustomer: DeleteCustomerUseCase,
) : ViewModel() {

    val customerId: Long = savedStateHandle.toRoute<CustomerDetailRoute>().customerId

    sealed interface Loaded {
        data object Loading : Loaded
        data object Missing : Loaded
        data class Ready(val summary: CustomerSummary) : Loaded
    }

    enum class Dialog { DEACTIVATE, DELETE, DELETE_BLOCKED }

    data class Ui(val dialog: Dialog? = null, val message: UiText? = null, val deleted: Boolean = false, val busy: Boolean = false)

    val customer: StateFlow<Loaded> = observe(customerId)
        .map { summary -> summary?.let(Loaded::Ready) ?: Loaded.Missing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), Loaded.Loading)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    fun requestDeactivate() = _ui.update { it.copy(dialog = Dialog.DEACTIVATE) }

    /** Checks first, so a customer with transactions gets "deactivate instead" rather than a delete prompt. */
    fun requestDelete() {
        viewModelScope.launch {
            val dialog = if (canDelete(customerId)) Dialog.DELETE else Dialog.DELETE_BLOCKED
            _ui.update { it.copy(dialog = dialog) }
        }
    }

    fun dismissDialog() = _ui.update { it.copy(dialog = null) }

    fun confirmDeactivate() = perform { deactivateCustomer(customerId) }

    fun activate() = perform { activateCustomer(customerId) }

    fun confirmDelete() {
        perform(onOk = { _ui.update { it.copy(deleted = true) } }) { deleteCustomer(customerId) }
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    private fun perform(onOk: () -> Unit = {}, action: suspend () -> DomainResult<Unit>) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, dialog = null) }
        viewModelScope.launch {
            val result = action()
            _ui.update { it.copy(busy = false, message = (result as? DomainResult.Err)?.let { err -> ErrorMessages.forError(err.error) }) }
            if (result is DomainResult.Ok) onOk()
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
