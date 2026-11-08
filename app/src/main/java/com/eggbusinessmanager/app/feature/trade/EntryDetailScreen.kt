package com.eggbusinessmanager.app.feature.trade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.payment.modeLabel
import com.eggbusinessmanager.app.feature.settings.SettingsRow
import com.eggbusinessmanager.app.navigation.EntryDetailRoute
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.ledger.EntryStatus
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.money.QuantityParser
import com.eggbusinessmanager.domain.usecase.catalog.ObserveUnitsUseCase
import com.eggbusinessmanager.domain.usecase.catalog.ObserveVarietiesUseCase
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import com.eggbusinessmanager.domain.usecase.trade.DeleteEntryUseCase
import com.eggbusinessmanager.domain.usecase.trade.EntryDetail
import com.eggbusinessmanager.domain.usecase.trade.ObserveEntryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class EntryDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeEntry: ObserveEntryUseCase,
    observeCustomer: ObserveCustomerUseCase,
    observeUnits: ObserveUnitsUseCase,
    observeVarieties: ObserveVarietiesUseCase,
    private val deleteEntry: DeleteEntryUseCase,
) : ViewModel() {

    val rootId: Long = savedStateHandle.toRoute<EntryDetailRoute>().rootId

    data class UiState(
        val detail: EntryDetail? = null,
        val loaded: Boolean = false,
        val customerName: String = "",
        val units: Map<Long, MeasurementUnit> = emptyMap(),
        val varieties: Map<Long, EggVariety> = emptyMap(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val customerName = observeEntry(rootId).flatMapLatest { detail ->
        detail?.let { observeCustomer(it.current.customerId).map { s -> s?.customer?.name.orEmpty() } } ?: flowOf("")
    }

    val state: StateFlow<UiState> = combine(
        observeEntry(rootId),
        customerName,
        observeUnits(includeInactive = true),
        observeVarieties(includeInactive = true),
    ) { detail, name, units, varieties ->
        UiState(
            detail = detail,
            loaded = true,
            customerName = name,
            units = units.associateBy { it.id },
            varieties = varieties.associateBy { it.id },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), UiState())

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun delete(reason: String) {
        viewModelScope.launch {
            (deleteEntry(rootId, reason) as? DomainResult.Err)?.let { _message.value = ErrorMessages.forError(it.error) }
        }
    }

    fun dismissMessage() = _message.update { null }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** One transaction with its details, status and change history (US5-4/5). */
@Composable
fun EntryDetailScreen(
    onEdit: (rootId: Long, type: EntryType) -> Unit,
    onBack: () -> Unit,
    viewModel: EntryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val detail = state.detail

    FormScreen(title = detail?.current?.reference ?: stringResource(R.string.entry_title), onBack = onBack) {
        when {
            !state.loaded -> Unit
            detail == null -> EmptyState(title = stringResource(R.string.error_not_found), body = "")
            else -> {
                val entry = detail.current
                SummaryCard(entry, state)
                detail.replacedBy?.let {
                    Text(
                        stringResource(R.string.entry_changed_to, it),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                message?.let { ErrorBanner(it.asString(), onDismiss = viewModel::dismissMessage) }
                val editable = entry.status == EntryStatus.ACTIVE && (entry.trade != null || entry.payment != null)
                if (editable) {
                    SettingsRow(
                        R.drawable.ic_edit, stringResource(R.string.entry_edit), null,
                        onClick = { onEdit(entry.rootId, entry.type) },
                    )
                    SettingsRow(R.drawable.ic_delete, stringResource(R.string.entry_delete), null, onClick = { confirmDelete = true })
                } else if (entry.status == EntryStatus.ACTIVE) {
                    Text(stringResource(R.string.entry_opening_edit_hint), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text(stringResource(R.string.entry_cannot_change), style = MaterialTheme.typography.bodyMedium)
                }
                if (detail.revisions.size > 1) History(detail.revisions)
            }
        }
    }

    if (confirmDelete && detail != null) {
        DeleteDialog(
            reference = detail.current.reference,
            onConfirm = { reason ->
                confirmDelete = false
                viewModel.delete(reason)
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SummaryCard(entry: LedgerEntry, state: EntryDetailViewModel.UiState) {
    val locale = LocalConfiguration.current.locales[0]
    val deleted = entry.status == EntryStatus.VOIDED
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (deleted) {
                MaterialTheme.colorScheme.surfaceContainerHighest
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(entryTypeLabel(entry.type)), style = MaterialTheme.typography.titleMedium)
            if (deleted) {
                Text(
                    stringResource(R.string.entry_deleted_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                entry.voidReason?.let {
                    Text(stringResource(R.string.entry_deleted_reason, it), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(IndianFormatters.money(entry.amount), style = MaterialTheme.typography.headlineMedium)
            Text("${state.customerName} · ${IndianFormatters.date(entry.date, locale)}", style = MaterialTheme.typography.bodyLarge)
            entry.trade?.let { line ->
                val unitName = state.units[line.unitId]?.name.orEmpty()
                Text(state.varieties[line.varietyId]?.name.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(
                        R.string.entry_quantity_line,
                        QuantityParser.toInput(line.quantity),
                        unitName,
                        IndianFormatters.group(line.pieces),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.entry_price_line, IndianFormatters.money(line.pricePerUnit), unitName),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            entry.payment?.let { payment ->
                val mode = stringResource(modeLabel(payment.mode))
                Text(
                    payment.reference?.let { stringResource(R.string.entry_payment_line, mode, it) } ?: mode,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            entry.remarks?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Every revision with when it was recorded; the newest is current (FR-033). */
@Composable
private fun History(revisions: List<LedgerEntry>) {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", LocalConfiguration.current.locales[0])
    Text(stringResource(R.string.entry_history), style = MaterialTheme.typography.titleSmall)
    revisions.asReversed().forEach { revision ->
        val status = when (revision.status) {
            EntryStatus.SUPERSEDED -> stringResource(R.string.entry_revised_badge)
            EntryStatus.VOIDED -> stringResource(R.string.entry_deleted_badge)
            EntryStatus.ACTIVE -> ""
        }
        val time = formatter.format(revision.createdAt.atZone(ZoneId.systemDefault()))
        Text(
            listOf(time, IndianFormatters.money(revision.amount), status).filter { it.isNotEmpty() }.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DeleteDialog(reference: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.entry_delete_title, reference)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.entry_delete_body))
                FormTextField(reason, { reason = it.take(REASON_MAX) }, stringResource(R.string.entry_delete_reason))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason) }) { Text(stringResource(R.string.entry_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val REASON_MAX = 200

/** Shared labels for entry types. */
fun entryTypeLabel(type: EntryType): Int = when (type) {
    EntryType.OPENING_BALANCE -> R.string.entry_type_opening
    EntryType.SELL -> R.string.entry_type_sell
    EntryType.BUY -> R.string.entry_type_buy
    EntryType.PAYMENT_RECEIVED -> R.string.entry_type_payment_received
    EntryType.PAYMENT_MADE -> R.string.entry_type_payment_made
}
