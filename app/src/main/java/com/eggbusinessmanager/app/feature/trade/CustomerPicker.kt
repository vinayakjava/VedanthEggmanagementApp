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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.balanceLabel
import com.eggbusinessmanager.domain.customer.CustomerSummary

// Customer selection shared by the Sell/Buy and payment screens.

/** Looks like a field; opens the customer picker. */
@Composable
fun CustomerField(name: String, error: String?, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(CUSTOMER_FIELD_TAG)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.label_customer), style = MaterialTheme.typography.labelMedium)
                Text(name.ifEmpty { stringResource(R.string.trade_choose_customer) }, style = MaterialTheme.typography.bodyLarge)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/**
 * Searchable list of active customers, recent first, with "Add new customer" (US3-9). A plain
 * Dialog, not AlertDialog: AlertDialog measures its content intrinsically, which lazy lists do not support.
 */
@Composable
fun CustomerPicker(
    results: List<CustomerSummary>,
    text: String,
    onSearch: (String) -> Unit,
    onPick: (Long) -> Unit,
    onAddNew: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.trade_choose_customer), style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = text,
                    onValueChange = onSearch,
                    placeholder = { Text(stringResource(R.string.trade_customer_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                onAddNew?.let { add ->
                    OutlinedButton(onClick = { add(text.trim()) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(painterResource(R.drawable.ic_person_add), contentDescription = null)
                        Text(stringResource(R.string.trade_add_new_customer), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                onClear?.let { clear ->
                    OutlinedButton(onClick = clear, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tx_all_customers)) }
                }
                if (results.isEmpty()) {
                    Text(stringResource(R.string.trade_no_customers), style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results, key = { it.customer.id }) { summary ->
                        ListItem(
                            headlineContent = { Text(summary.customer.name) },
                            supportingContent = { Text(balanceLabel(summary.balance)) },
                            modifier = Modifier.fillMaxWidth().clickable { onPick(summary.customer.id) },
                        )
                        HorizontalDivider()
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}
