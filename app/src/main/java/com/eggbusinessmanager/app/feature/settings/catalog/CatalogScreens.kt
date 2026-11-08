package com.eggbusinessmanager.app.feature.settings.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.catalog.EggVariety
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.usecase.catalog.ObserveUnitsUseCase
import com.eggbusinessmanager.domain.usecase.catalog.ObserveVarietiesUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SaveUnitUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SaveVarietyUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SetDefaultVarietyUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SetUnitActiveUseCase
import com.eggbusinessmanager.domain.usecase.catalog.SetVarietyActiveUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Units and varieties settings (US12-1..4, US12-8). */
@HiltViewModel
class CatalogViewModel @Inject constructor(
    observeUnits: ObserveUnitsUseCase,
    observeVarieties: ObserveVarietiesUseCase,
    private val saveUnit: SaveUnitUseCase,
    private val unitActive: SetUnitActiveUseCase,
    private val saveVariety: SaveVarietyUseCase,
    private val setDefault: SetDefaultVarietyUseCase,
    private val varietyActive: SetVarietyActiveUseCase,
) : ViewModel() {

    val units: StateFlow<List<MeasurementUnit>> =
        observeUnits(includeInactive = true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())
    val varieties: StateFlow<List<EggVariety>> =
        observeVarieties(includeInactive = true).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    /** Feedback for the open add/edit dialog; [closeDialog] turns true after a successful save. */
    data class DialogState(val feedback: FormFeedback = FormFeedback.None, val closeDialog: Boolean = false)

    private val _dialog = MutableStateFlow(DialogState())
    val dialog: StateFlow<DialogState> = _dialog.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    val message: StateFlow<UiText?> = _message.asStateFlow()

    fun startDialog() {
        _dialog.value = DialogState()
    }

    fun saveUnit(id: Long?, name: String, pieces: String) = submit { saveUnit.invoke(id, name, pieces) }

    fun saveVariety(id: Long?, name: String) = submit { saveVariety.invoke(id, name) }

    fun setUnitActive(id: Long, active: Boolean) = act { unitActive(id, active) }

    fun setVarietyActive(id: Long, active: Boolean) = act { varietyActive(id, active) }

    fun makeDefault(id: Long) = act { setDefault(id) }

    fun dismissMessage() {
        _message.value = null
    }

    private fun submit(action: suspend () -> DomainResult<Long>) {
        viewModelScope.launch {
            _dialog.value = when (val result = action()) {
                is DomainResult.Ok -> DialogState(closeDialog = true)
                is DomainResult.Err -> DialogState(feedback = FormFeedback.from(result.error))
            }
        }
    }

    private fun act(action: suspend () -> DomainResult<Unit>) {
        viewModelScope.launch {
            _message.value = (action() as? DomainResult.Err)?.let { ErrorMessages.forError(it.error) }
        }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Measurement units, each with its own number of eggs (FR-059). */
@Composable
fun UnitsScreen(onBack: () -> Unit, viewModel: CatalogViewModel = hiltViewModel()) {
    val units by viewModel.units.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    FormScreen(title = stringResource(R.string.units_title), onBack = onBack) {
        message?.let { ErrorBanner(it.asString(), onDismiss = viewModel::dismissMessage) }
        units.forEach { unit ->
            CatalogRow(
                title = unit.name,
                subtitle = stringResource(R.string.units_size, unit.piecesPerUnit),
                active = unit.isActive,
                badge = null,
                note = if (unit.isSystem) stringResource(R.string.units_base_note) else null,
                actions = {
                    if (!unit.isSystem) {
                        TextButton(onClick = {
                            viewModel.startDialog()
                            editing = unit.id
                        }) { Text(stringResource(R.string.units_edit)) }
                        TextButton(onClick = { viewModel.setUnitActive(unit.id, !unit.isActive) }) {
                            Text(stringResource(if (unit.isActive) R.string.action_deactivate else R.string.action_activate))
                        }
                    }
                },
            )
        }
        PrimaryButton(stringResource(R.string.units_add), onClick = {
            viewModel.startDialog()
            adding = true
        })
    }

    val target = units.firstOrNull { it.id == editing }
    if (adding || target != null) {
        UnitDialog(
            unit = target,
            viewModel = viewModel,
            onClose = {
                adding = false
                editing = null
            },
        )
    }
}

@Composable
private fun UnitDialog(unit: MeasurementUnit?, viewModel: CatalogViewModel, onClose: () -> Unit) {
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(unit?.name.orEmpty()) }
    var pieces by rememberSaveable { mutableStateOf(unit?.piecesPerUnit?.toString().orEmpty()) }
    if (dialog.closeDialog) {
        onClose()
        return
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (unit == null) R.string.units_add else R.string.units_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FormTextField(name, { name = it }, stringResource(R.string.label_unit_name), error = dialog.feedback.error(Field.UNIT_NAME))
                FormTextField(
                    pieces, { pieces = it.filter(Char::isDigit) }, stringResource(R.string.label_unit_pieces),
                    error = dialog.feedback.error(Field.UNIT_PIECES), keyboardType = KeyboardType.Number,
                )
                // US12-3: tell the owner before a resize that past entries are not affected.
                if (unit != null) Text(stringResource(R.string.units_resize_warning), style = MaterialTheme.typography.bodySmall)
                dialog.feedback.message?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.saveUnit(unit?.id, name, pieces) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Egg varieties with one default (FR-027a). */
@Composable
fun VarietiesScreen(onBack: () -> Unit, viewModel: CatalogViewModel = hiltViewModel()) {
    val varieties by viewModel.varieties.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    FormScreen(title = stringResource(R.string.varieties_title), onBack = onBack) {
        message?.let { ErrorBanner(it.asString(), onDismiss = viewModel::dismissMessage) }
        varieties.forEach { variety ->
            CatalogRow(
                title = variety.name,
                subtitle = null,
                active = variety.isActive,
                badge = if (variety.isDefault) stringResource(R.string.varieties_default_badge) else null,
                note = null,
                actions = {
                    TextButton(onClick = {
                        viewModel.startDialog()
                        editing = variety.id
                    }) { Text(stringResource(R.string.varieties_edit)) }
                    if (!variety.isDefault) {
                        TextButton(onClick = { viewModel.makeDefault(variety.id) }) {
                            Text(stringResource(R.string.varieties_make_default))
                        }
                    }
                    TextButton(onClick = { viewModel.setVarietyActive(variety.id, !variety.isActive) }) {
                        Text(stringResource(if (variety.isActive) R.string.action_deactivate else R.string.action_activate))
                    }
                },
            )
        }
        PrimaryButton(stringResource(R.string.varieties_add), onClick = {
            viewModel.startDialog()
            adding = true
        })
    }

    val target = varieties.firstOrNull { it.id == editing }
    if (adding || target != null) {
        val dialog by viewModel.dialog.collectAsStateWithLifecycle()
        var name by rememberSaveable { mutableStateOf(target?.name.orEmpty()) }
        val close = {
            adding = false
            editing = null
        }
        if (dialog.closeDialog) {
            close()
        } else {
            AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(if (target == null) R.string.varieties_add else R.string.varieties_edit)) },
                text = {
                    FormTextField(
                        name, { name = it }, stringResource(R.string.label_variety_name),
                        error = dialog.feedback.error(Field.VARIETY_NAME),
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.saveVariety(target?.id, name) }) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
    }
}

@Composable
private fun CatalogRow(
    title: String,
    subtitle: String?,
    active: Boolean,
    badge: String?,
    note: String?,
    actions: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                badge?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                if (!active) {
                    Text(
                        stringResource(R.string.inactive_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
        }
    }
}

