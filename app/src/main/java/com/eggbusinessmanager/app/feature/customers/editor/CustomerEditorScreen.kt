package com.eggbusinessmanager.app.feature.customers.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.ledger.OpeningDirection
import com.eggbusinessmanager.domain.validation.CustomerValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Add or edit a customer with an optional opening balance (FR-015). */
@Composable
fun CustomerEditorScreen(
    onSaved: (customerId: Long, isNew: Boolean) -> Unit,
    onOpenCustomer: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: CustomerEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val draft = state.draft
    val feedback = state.feedback
    LaunchedEffect(state.savedId) { state.savedId?.let { onSaved(it, viewModel.isNew) } }

    FormScreen(
        title = stringResource(if (viewModel.isNew) R.string.editor_add_title else R.string.editor_edit_title),
        onBack = onBack,
    ) {
        FormTextField(
            draft.name, viewModel::onName, stringResource(R.string.label_customer_name),
            error = feedback.error(Field.CUSTOMER_NAME),
            supportingText = if (state.duplicateNameWarning) stringResource(R.string.editor_duplicate_name_warning) else null,
        )
        FormTextField(
            draft.mobile, viewModel::onMobile, stringResource(R.string.field_optional, stringResource(R.string.label_mobile)),
            error = feedback.error(Field.MOBILE), keyboardType = KeyboardType.Phone, prefix = stringResource(R.string.hint_mobile_prefix),
        )
        state.existingMobileOwner?.let { owner ->
            OutlinedButton(onClick = { onOpenCustomer(owner.id) }) { Text(stringResource(R.string.action_open_customer, owner.name)) }
        }
        FormTextField(
            draft.address, viewModel::onAddress, stringResource(R.string.field_optional, stringResource(R.string.label_address)),
            error = feedback.error(Field.ADDRESS), singleLine = false,
        )
        FormTextField(
            draft.notes, viewModel::onNotes, stringResource(R.string.field_optional, stringResource(R.string.label_notes)),
            error = feedback.error(Field.NOTES), singleLine = false,
        )

        Text(stringResource(R.string.editor_opening_heading), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.editor_opening_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpeningDirectionPicker(draft.openingDirection, viewModel::onOpeningDirection)
        FormTextField(
            draft.openingAmount, viewModel::onOpeningAmount, stringResource(R.string.label_opening_amount),
            error = feedback.error(Field.OPENING_AMOUNT), keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done, onImeAction = viewModel::save,
        )
        DateField(
            label = stringResource(R.string.label_opening_date),
            date = draft.openingDate ?: viewModel.today,
            today = viewModel.today,
            error = feedback.error(Field.OPENING_DATE),
            onDate = viewModel::onOpeningDate,
        )
        feedback.message?.let { ErrorBanner(it.asString()) }
        PrimaryButton(stringResource(R.string.action_save), onClick = viewModel::save, busy = state.busy, enabled = !state.loading)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpeningDirectionPicker(selected: OpeningDirection, onSelect: (OpeningDirection) -> Unit) {
    val options = listOf(
        OpeningDirection.CUSTOMER_OWES to stringResource(R.string.opening_customer_owes),
        OpeningDirection.SHOP_OWES to stringResource(R.string.opening_shop_owes),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (direction, label) ->
            SegmentedButton(
                selected = direction == selected,
                onClick = { onSelect(direction) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(label) },
            )
        }
    }
}

/** Read-only date field with a picker limited to 2000-01-01 … today (business-date rule). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(label: String, date: LocalDate, today: LocalDate, error: UiText?, onDate: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    FormTextField(
        value = IndianFormatters.date(date, locale),
        onValueChange = {},
        label = label,
        error = error,
        trailingIcon = {
            IconButton(onClick = { open = true }) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = stringResource(R.string.action_choose_date))
            }
        },
        readOnly = true,
    )
    if (open) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMillis(),
            yearRange = CustomerValidator.EARLIEST_DATE.year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return !day.isAfter(today) && !day.isBefore(CustomerValidator.EARLIEST_DATE)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
