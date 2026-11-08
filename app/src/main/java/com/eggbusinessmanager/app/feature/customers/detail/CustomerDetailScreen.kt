package com.eggbusinessmanager.app.feature.customers.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.settings.SettingsRow
import com.eggbusinessmanager.app.ui.components.BalanceHeadline
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.components.balanceLabel
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.EntryType

/** Customer detail (US2): balance header, contact, history, edit, deactivate/activate, delete. */
@Composable
fun CustomerDetailScreen(
    onEdit: (Long) -> Unit,
    onHistory: (Long) -> Unit,
    onTrade: (customerId: Long, type: EntryType) -> Unit,
    onPayment: (customerId: Long, type: EntryType) -> Unit,
    onBack: () -> Unit,
    viewModel: CustomerDetailViewModel = hiltViewModel(),
) {
    val loaded by viewModel.customer.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.deleted) { if (ui.deleted) onBack() }

    when (val state = loaded) {
        CustomerDetailViewModel.Loaded.Loading -> FormScreen(title = "", onBack = onBack) {}
        CustomerDetailViewModel.Loaded.Missing -> FormScreen(title = "", onBack = onBack) {
            EmptyState(title = stringResource(R.string.error_not_found), body = "")
        }
        is CustomerDetailViewModel.Loaded.Ready -> {
            val summary = state.summary
            val customer = summary.customer
            FormScreen(title = customer.name, onBack = onBack) {
                BalanceCard(summary)
                ContactCard(summary)
                ui.message?.let { ErrorBanner(it.asString(), onDismiss = viewModel::dismissMessage) }
                // One customer can be both buyer and supplier: both actions are always offered.
                if (customer.isActive) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onTrade(customer.id, EntryType.SELL) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.customer_sell))
                        }
                        FilledTonalButton(onClick = { onTrade(customer.id, EntryType.BUY) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.customer_buy))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onPayment(customer.id, EntryType.PAYMENT_RECEIVED) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.customer_receive))
                        }
                        OutlinedButton(onClick = { onPayment(customer.id, EntryType.PAYMENT_MADE) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.customer_pay))
                        }
                    }
                }
                SettingsRow(R.drawable.ic_history, stringResource(R.string.detail_transactions), null, onClick = { onHistory(customer.id) })
                SettingsRow(R.drawable.ic_edit, stringResource(R.string.detail_edit), null, onClick = { onEdit(customer.id) })
                if (customer.isActive) {
                    SettingsRow(
                        R.drawable.ic_block, stringResource(R.string.detail_deactivate), null,
                        onClick = viewModel::requestDeactivate,
                    )
                } else {
                    SettingsRow(R.drawable.ic_restore, stringResource(R.string.detail_activate), null, onClick = viewModel::activate)
                }
                SettingsRow(R.drawable.ic_delete, stringResource(R.string.detail_delete), null, onClick = viewModel::requestDelete)
            }
            ui.dialog?.let { CustomerDialog(it, summary, viewModel) }
        }
    }
}

@Composable
private fun BalanceCard(summary: CustomerSummary) {
    val locale = LocalConfiguration.current.locales[0]
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.customer_current_balance), style = MaterialTheme.typography.labelLarge)
            BalanceHeadline(summary.balance, style = MaterialTheme.typography.headlineMedium)
            summary.lastActivity?.let {
                Text(
                    stringResource(R.string.customer_last_activity, IndianFormatters.date(it, locale)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!summary.customer.isActive) {
                Text(
                    stringResource(R.string.customer_deactivated_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ContactCard(summary: CustomerSummary) {
    val c = summary.customer
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.detail_contact), style = MaterialTheme.typography.titleSmall)
            if (c.mobile == null && c.address == null && c.notes == null) {
                Text(stringResource(R.string.detail_no_contact), style = MaterialTheme.typography.bodyMedium)
            }
            c.mobile?.let { ContactLine(R.drawable.ic_phone, "+91 $it") }
            c.address?.let { ContactLine(R.drawable.ic_nav_more, it, label = stringResource(R.string.label_address)) }
            c.notes?.let { ContactLine(R.drawable.ic_edit, it, label = stringResource(R.string.label_notes)) }
        }
    }
}

@Composable
private fun ContactLine(icon: Int, text: String, label: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CustomerDialog(dialog: CustomerDetailViewModel.Dialog, summary: CustomerSummary, viewModel: CustomerDetailViewModel) {
    val name = summary.customer.name
    when (dialog) {
        CustomerDetailViewModel.Dialog.DEACTIVATE -> ConfirmDialog(
            title = stringResource(R.string.deactivate_title, name),
            body = buildList {
                // US2-8: a non-zero balance is stated and needs confirmation.
                if (summary.balance != Balance.Settled) add(stringResource(R.string.deactivate_outstanding, balanceLabel(summary.balance)))
                add(stringResource(R.string.deactivate_body))
            }.joinToString("\n\n"),
            confirm = stringResource(R.string.detail_deactivate),
            onConfirm = viewModel::confirmDeactivate,
            onDismiss = viewModel::dismissDialog,
        )
        CustomerDetailViewModel.Dialog.DELETE -> ConfirmDialog(
            title = stringResource(R.string.delete_title, name),
            body = stringResource(R.string.delete_body),
            confirm = stringResource(R.string.detail_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDialog,
        )
        // US2-6: hard delete is refused; deactivation is offered instead.
        CustomerDetailViewModel.Dialog.DELETE_BLOCKED -> ConfirmDialog(
            title = stringResource(R.string.delete_blocked_title),
            body = stringResource(R.string.delete_blocked_body, name),
            confirm = stringResource(R.string.detail_deactivate),
            onConfirm = if (summary.customer.isActive) viewModel::requestDeactivate else viewModel::dismissDialog,
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
