package com.eggbusinessmanager.app.feature.customers.editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.DateField
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.components.PrimaryButton
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.ledger.OpeningDirection

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
