package com.eggbusinessmanager.app.feature.customers.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.navigation.CustomerHistoryRoute
import com.eggbusinessmanager.app.ui.components.BalanceHeadline
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.components.balanceLabel
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.ledger.Balance
import com.eggbusinessmanager.domain.ledger.CustomerLedger
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.ledger.LedgerEntry
import com.eggbusinessmanager.domain.ledger.LedgerLine
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerLedgerUseCase
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class CustomerHistoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    observeCustomer: ObserveCustomerUseCase,
    observeLedger: ObserveCustomerLedgerUseCase,
) : ViewModel() {
    private val customerId = savedStateHandle.toRoute<CustomerHistoryRoute>().customerId

    data class UiState(val customer: CustomerSummary? = null, val ledger: CustomerLedger? = null)

    val state: StateFlow<UiState> = combine(observeCustomer(customerId), observeLedger(customerId), ::UiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), UiState())

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/**
 * The customer's transaction history (FR-029): every active entry oldest first, with the running
 * balance after each one. Shows opening balances now; sales, purchases and payments as they arrive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomerHistoryScreen(onOpenEntry: (Long) -> Unit, onBack: () -> Unit, viewModel: CustomerHistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.history_title))
                        state.customer?.let { Text(it.customer.name, style = MaterialTheme.typography.bodyMedium) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val ledger = state.ledger
            when {
                ledger == null -> Unit
                ledger.lines.isEmpty() && ledger.deleted.isEmpty() -> EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                    iconRes = R.drawable.ic_history,
                )
                else -> LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(stringResource(R.string.customer_current_balance), style = MaterialTheme.typography.labelLarge)
                                BalanceHeadline(Balance.of(ledger.balance))
                            }
                        }
                    }
                    // Newest first is easier to scan; the running balance is still computed oldest first.
                    items(ledger.lines.asReversed(), key = { it.entry.id }) { line -> LedgerRow(line, onOpenEntry) }
                    // Deleted entries stay visible, labelled, and never count (FR-034).
                    if (ledger.deleted.isNotEmpty()) {
                        item { Text(stringResource(R.string.history_deleted_heading), style = MaterialTheme.typography.titleSmall) }
                        items(ledger.deleted.asReversed(), key = { "deleted-" + it.id }) { entry -> DeletedRow(entry, onOpenEntry) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeletedRow(entry: LedgerEntry, onOpen: (Long) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Card(onClick = { onOpen(entry.rootId) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(typeLabel(entry.type)), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${IndianFormatters.date(entry.date, locale)} · ${entry.reference} · ${stringResource(R.string.entry_deleted_badge)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                IndianFormatters.money(entry.amount),
                style = MaterialTheme.typography.titleMedium.copy(textDecoration = TextDecoration.LineThrough),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LedgerRow(line: LedgerLine, onOpen: (Long) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val entry = line.entry
    Card(onClick = { onOpen(entry.rootId) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(typeLabel(entry.type)), style = MaterialTheme.typography.titleSmall)
                Text(
                    "${IndianFormatters.date(entry.date, locale)} · ${entry.reference}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.history_balance_after, balanceLabel(Balance.of(line.runningBalance))),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(IndianFormatters.money(entry.amount), style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun typeLabel(type: EntryType) = when (type) {
    EntryType.OPENING_BALANCE -> R.string.entry_type_opening
    EntryType.SELL -> R.string.entry_type_sell
    EntryType.BUY -> R.string.entry_type_buy
    EntryType.PAYMENT_RECEIVED -> R.string.entry_type_payment_received
    EntryType.PAYMENT_MADE -> R.string.entry_type_payment_made
}
