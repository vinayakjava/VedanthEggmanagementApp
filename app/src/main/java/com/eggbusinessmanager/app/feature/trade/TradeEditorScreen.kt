package com.eggbusinessmanager.app.feature.trade

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.DateField
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.components.balanceLabel
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.money.Money

/** Test tags used by UI tests. */
const val CUSTOMER_FIELD_TAG = "trade_customer"
const val TOTAL_TAG = "trade_total"

/**
 * Sell eggs / Buy eggs (US3), and editing a saved trade (US5-4). [newCustomerId] is the id of a
 * customer just added from this screen (US3-9); the typed trade is kept.
 */
@Composable
fun TradeEditorScreen(
    newCustomerId: Long?,
    onNewCustomerConsumed: () -> Unit,
    onAddCustomer: (prefill: String) -> Unit,
    onClose: () -> Unit,
    viewModel: TradeEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val varieties by viewModel.varieties.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val impact by viewModel.impact.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    val feedback = state.feedback
    val unit = units.firstOrNull { it.id == draft.unitId }

    LaunchedEffect(newCustomerId) {
        newCustomerId?.let {
            viewModel.onCustomerPicked(it)
            onNewCustomerConsumed()
        }
    }
    LaunchedEffect(state.saved) { if (state.saved?.isNew == false) onClose() }

    FormScreen(title = editorTitle(viewModel.isEdit, draft.type), onBack = onClose) {
        if (viewModel.isEdit) TypeSwitch(draft.type, viewModel::onType)
        CustomerField(state.customerName, feedback.error(Field.CUSTOMER)?.asString()) { pickerOpen = true }
        DateField(
            label = stringResource(R.string.label_entry_date),
            date = draft.date ?: viewModel.today,
            today = viewModel.today,
            error = feedback.error(Field.ENTRY_DATE),
            onDate = viewModel::onDate,
        )
        DropdownField(
            label = stringResource(R.string.label_variety),
            options = varieties,
            selected = varieties.firstOrNull { it.id == draft.varietyId },
            optionLabel = { it.name },
            onSelect = { viewModel.onVariety(it.id) },
            error = feedback.error(Field.VARIETY),
        )
        DropdownField(
            label = stringResource(R.string.label_unit),
            options = units,
            selected = unit,
            optionLabel = { stringResource(R.string.unit_option, it.name, it.piecesPerUnit) },
            onSelect = { viewModel.onUnit(it.id) },
            error = feedback.error(Field.UNIT),
        )
        FormTextField(
            draft.quantity, viewModel::onQuantity, stringResource(R.string.label_quantity),
            error = feedback.error(Field.QUANTITY), keyboardType = KeyboardType.Decimal,
        )
        FormTextField(
            draft.price, viewModel::onPrice,
            unit?.let { stringResource(R.string.label_price_per_unit, it.name) } ?: stringResource(R.string.label_price),
            error = feedback.error(Field.PRICE), keyboardType = KeyboardType.Decimal,
            supportingText = if (state.priceSuggested) stringResource(R.string.trade_price_suggested) else null,
        )
        TotalCard(preview.total, preview.pieces, feedback.error(Field.TOTAL)?.asString())
        FormTextField(
            draft.remarks, viewModel::onRemarks, stringResource(R.string.field_optional, stringResource(R.string.label_remarks)),
            error = feedback.error(Field.REMARKS), singleLine = false,
        )
        if (viewModel.isEdit) {
            FormTextField(
                state.reason, viewModel::onReason, stringResource(R.string.field_optional, stringResource(R.string.label_edit_reason)),
                error = feedback.error(Field.REASON),
            )
            ImpactCard(impact)
        }
        feedback.message?.let { ErrorBanner(it.asString()) }
        PrimaryButton(stringResource(R.string.action_save), onClick = viewModel::save, busy = state.busy, enabled = !state.loading)
    }

    if (pickerOpen) {
        val results by viewModel.pickerResults.collectAsStateWithLifecycle()
        val text by viewModel.pickerText.collectAsStateWithLifecycle()
        CustomerPicker(
            results = results,
            text = text,
            onSearch = viewModel::onPickerSearch,
            onPick = {
                viewModel.onCustomerPicked(it)
                pickerOpen = false
            },
            onAddNew = { text ->
                pickerOpen = false
                onAddCustomer(text)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    state.saved?.takeIf { it.isNew }?.let { SavedDialog(it, onRecordAnother = viewModel::recordAnother, onDone = onClose) }
}

@Composable
private fun editorTitle(isEdit: Boolean, type: EntryType): String = when {
    isEdit -> stringResource(R.string.trade_edit_title, typeName(type))
    type == EntryType.SELL -> stringResource(R.string.trade_sell_title)
    else -> stringResource(R.string.trade_buy_title)
}

/** US3-2: confirmation with "Record another". */
@Composable
private fun SavedDialog(saved: TradeEditorViewModel.Saved, onRecordAnother: () -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.trade_saved_title, saved.ref.reference)) },
        text = { Text(stringResource(R.string.trade_saved_body, saved.customerName, IndianFormatters.money(saved.total))) },
        confirmButton = { TextButton(onClick = onRecordAnother) { Text(stringResource(R.string.trade_record_another)) } },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.trade_done)) } },
    )
}

@Composable
private fun typeName(type: EntryType) =
    stringResource(if (type == EntryType.SELL) R.string.entry_type_sell else R.string.entry_type_buy)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeSwitch(selected: EntryType, onSelect: (EntryType) -> Unit) {
    val options = listOf(EntryType.SELL to R.string.trade_type_sell, EntryType.BUY to R.string.trade_type_buy)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (type, label) ->
            SegmentedButton(
                selected = type == selected,
                onClick = { onSelect(type) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label)) },
            )
        }
    }
}

@Composable
private fun TotalCard(total: Money?, pieces: Long?, error: String?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.trade_total), style = MaterialTheme.typography.labelLarge)
                pieces?.let {
                    Text(stringResource(R.string.trade_pieces, IndianFormatters.group(it)), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                IndianFormatters.money(total ?: Money.ZERO),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(TOTAL_TAG),
            )
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/** FR-033: each affected customer's balance change, shown before saving a correction. */
@Composable
private fun ImpactCard(impact: List<Pair<String, Money>>) {
    if (impact.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.trade_impact_heading), style = MaterialTheme.typography.titleSmall)
            impact.forEach { (name, change) ->
                val text = when (val direction = Balance.of(change)) {
                    is Balance.ToReceive -> stringResource(R.string.trade_impact_up, IndianFormatters.money(direction.amount))
                    is Balance.ToPay -> stringResource(R.string.trade_impact_down, IndianFormatters.money(direction.amount))
                    Balance.Settled -> balanceLabel(direction)
                }
                Text(stringResource(R.string.trade_impact_line, name, text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
