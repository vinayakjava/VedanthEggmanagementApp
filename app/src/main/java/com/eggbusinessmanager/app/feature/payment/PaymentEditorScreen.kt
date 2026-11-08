package com.eggbusinessmanager.app.feature.payment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.trade.CustomerField
import com.eggbusinessmanager.app.feature.trade.CustomerPicker
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
import com.eggbusinessmanager.domain.ledger.PaymentMode
import com.eggbusinessmanager.domain.payment.PaymentImpact

/** Test tag for the live impact card. */
const val PAYMENT_IMPACT_TAG = "payment_impact"

/**
 * Receive payment / Pay customer (US4), and editing a saved payment (US5-4). [newCustomerId] is a
 * customer just added from this screen; the typed payment is kept.
 */
@Composable
fun PaymentEditorScreen(
    newCustomerId: Long?,
    onNewCustomerConsumed: () -> Unit,
    onAddCustomer: (prefill: String) -> Unit,
    onClose: () -> Unit,
    viewModel: PaymentEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val impact by viewModel.impact.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val draft = state.draft
    val feedback = state.feedback

    LaunchedEffect(newCustomerId) {
        newCustomerId?.let {
            viewModel.onCustomerPicked(it)
            onNewCustomerConsumed()
        }
    }
    LaunchedEffect(state.saved) { if (state.saved?.isNew == false) onClose() }

    FormScreen(title = editorTitle(viewModel.isEdit, draft.type), onBack = onClose) {
        if (viewModel.isEdit) DirectionSwitch(draft.type, viewModel::onType)
        CustomerField(state.customerName, feedback.error(Field.CUSTOMER)?.asString()) { pickerOpen = true }
        DateField(
            label = stringResource(R.string.label_entry_date),
            date = draft.date ?: viewModel.today,
            today = viewModel.today,
            error = feedback.error(Field.ENTRY_DATE),
            onDate = viewModel::onDate,
        )
        FormTextField(
            draft.amount, viewModel::onAmount, stringResource(R.string.label_amount),
            error = feedback.error(Field.AMOUNT), keyboardType = KeyboardType.Decimal, prefix = "₹ ",
        )
        val outstanding = impact?.outstanding
        if (outstanding != null && outstanding.isPositive) {
            TextButton(onClick = viewModel::onFullAmount) {
                Text(stringResource(R.string.payment_full_amount, IndianFormatters.money(outstanding)))
            }
        }
        impact?.let { ImpactCard(draft.type, it) }
        DropdownField(
            label = stringResource(R.string.label_payment_mode),
            options = PaymentMode.entries,
            selected = draft.mode,
            optionLabel = { stringResource(modeLabel(it)) },
            onSelect = viewModel::onMode,
        )
        FormTextField(
            draft.reference, viewModel::onReference,
            stringResource(R.string.field_optional, stringResource(R.string.label_payment_reference)),
            error = feedback.error(Field.PAYMENT_REFERENCE),
            supportingText = stringResource(R.string.payment_reference_hint),
        )
        FormTextField(
            draft.remarks, viewModel::onRemarks, stringResource(R.string.field_optional, stringResource(R.string.label_remarks)),
            error = feedback.error(Field.REMARKS), singleLine = false,
        )
        if (viewModel.isEdit) {
            FormTextField(
                state.reason, viewModel::onReason, stringResource(R.string.field_optional, stringResource(R.string.label_edit_reason)),
                error = feedback.error(Field.REASON),
            )
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
            onAddNew = { prefill ->
                pickerOpen = false
                onAddCustomer(prefill)
            },
            onDismiss = { pickerOpen = false },
        )
    }
    PaymentDialogs(state, viewModel, onClose)
}

/** Advance confirmation (FR-030) and the saved confirmation with "Record another". */
@Composable
private fun PaymentDialogs(state: PaymentEditorViewModel.UiState, viewModel: PaymentEditorViewModel, onClose: () -> Unit) {
    val draft = state.draft
    state.confirmAdvance?.let { advance ->
        val body = when (draft.type) {
            EntryType.PAYMENT_RECEIVED -> R.string.payment_advance_received_body
            else -> R.string.payment_advance_made_body
        }
        AlertDialog(
            onDismissRequest = viewModel::cancelAdvance,
            title = { Text(stringResource(R.string.payment_advance_title)) },
            text = {
                Text(stringResource(body, IndianFormatters.money(advance), state.customerName))
            },
            confirmButton = { TextButton(onClick = viewModel::confirmAdvance) { Text(stringResource(R.string.payment_advance_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::cancelAdvance) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    state.saved?.takeIf { it.isNew }?.let { saved ->
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.trade_saved_title, saved.ref.reference)) },
            text = { Text(stringResource(R.string.trade_saved_body, saved.customerName, IndianFormatters.money(saved.amount))) },
            confirmButton = { TextButton(onClick = viewModel::recordAnother) { Text(stringResource(R.string.trade_record_another)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.trade_done)) } },
        )
    }
}

@Composable
private fun editorTitle(isEdit: Boolean, type: EntryType): String = when {
    isEdit -> stringResource(R.string.payment_edit_title)
    type == EntryType.PAYMENT_RECEIVED -> stringResource(R.string.payment_receive_title)
    else -> stringResource(R.string.payment_pay_title)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectionSwitch(selected: EntryType, onSelect: (EntryType) -> Unit) {
    val options = listOf(EntryType.PAYMENT_RECEIVED to R.string.payment_receive_title, EntryType.PAYMENT_MADE to R.string.payment_pay_title)
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

/** Live: what is owed in this direction, the advance if any, and the balance after saving (FR-030). */
@Composable
private fun ImpactCard(type: EntryType, impact: PaymentImpact) {
    val outstandingLabel = if (type == EntryType.PAYMENT_RECEIVED) R.string.payment_customer_owes else R.string.payment_shop_owes
    Card(
        Modifier.fillMaxWidth().testTag(PAYMENT_IMPACT_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ImpactLine(stringResource(outstandingLabel), IndianFormatters.money(impact.outstanding))
            if (impact.createsAdvance) {
                ImpactLine(stringResource(R.string.payment_advance), IndianFormatters.money(impact.advance), highlight = true)
            }
            ImpactLine(stringResource(R.string.payment_balance_after), balanceLabel(Balance.of(impact.resultingBalance)))
        }
    }
}

@Composable
private fun ImpactLine(label: String, value: String, highlight: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (highlight) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

fun modeLabel(mode: PaymentMode): Int = when (mode) {
    PaymentMode.CASH -> R.string.payment_mode_cash
    PaymentMode.UPI -> R.string.payment_mode_upi
    PaymentMode.BANK_TRANSFER -> R.string.payment_mode_bank
    PaymentMode.CHEQUE -> R.string.payment_mode_cheque
    PaymentMode.OTHER -> R.string.payment_mode_other
}
