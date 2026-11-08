package com.eggbusinessmanager.app.feature.transactions

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.trade.CustomerPicker
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.report.TransactionFilter
import com.eggbusinessmanager.domain.report.TransactionSort
import com.eggbusinessmanager.domain.report.TransactionTotals
import com.eggbusinessmanager.domain.validation.CustomerValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

const val TX_SEARCH_TAG = "tx_search"
const val TX_TOTALS_TAG = "tx_totals"

/** Transactions (Phase 6): every sale, purchase and payment, filtered and sorted, with filtered totals. */
@Composable
fun TransactionsScreen(onOpenEntry: (Long) -> Unit, viewModel: TransactionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val customerName by viewModel.customerName.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = filter.search,
                    onValueChange = viewModel::onSearch,
                    placeholder = { Text(stringResource(R.string.tx_search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TX_SEARCH_TAG),
                )
            }
            item { DateAndCustomerRow(filter, customerName, viewModel) { pickerOpen = true } }
            item { TypeRow(filter.types, viewModel::onToggleType) }
            item { TotalsCard(state.list.totals) }
            val rows = state.list.rows
            if (!state.loading && rows.isEmpty()) {
                item {
                    EmptyState(title = stringResource(R.string.tx_empty_title), body = stringResource(R.string.tx_empty_body))
                    TextButton(onClick = viewModel::reset) { Text(stringResource(R.string.tx_reset)) }
                }
            }
            items(rows, key = { it.entry.id }) { TransactionItem(it, onOpenEntry) }
        }
    }

    if (pickerOpen) {
        val results by viewModel.pickerResults.collectAsStateWithLifecycle()
        val text by viewModel.pickerText.collectAsStateWithLifecycle()
        CustomerPicker(
            results = results,
            text = text,
            onSearch = viewModel::onPickerSearch,
            onPick = {
                viewModel.onCustomer(it)
                pickerOpen = false
            },
            onAddNew = null,
            onDismiss = { pickerOpen = false },
            onClear = {
                viewModel.onCustomer(null)
                pickerOpen = false
            },
        )
    }
}

@Composable
private fun DateAndCustomerRow(
    filter: TransactionFilter,
    customerName: String?,
    viewModel: TransactionsViewModel,
    onPickCustomer: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val fromLabel = filter.from?.let { stringResource(R.string.tx_from, IndianFormatters.date(it, locale)) }
    val toLabel = filter.to?.let { stringResource(R.string.tx_to, IndianFormatters.date(it, locale)) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OptionalDateChip(
            label = fromLabel ?: stringResource(R.string.tx_from_any),
            date = filter.from,
            today = viewModel.today,
            onDate = viewModel::onFrom,
        )
        OptionalDateChip(
            label = toLabel ?: stringResource(R.string.tx_to_any),
            date = filter.to,
            today = viewModel.today,
            onDate = viewModel::onTo,
        )
        FilterChip(
            selected = filter.customerId != null,
            onClick = onPickCustomer,
            label = { Text(customerName ?: stringResource(R.string.tx_all_customers)) },
        )
        Box {
            TextButton(onClick = { sortOpen = true }) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = null)
                Text(sortLabel(filter.sort), modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                TransactionSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(sortLabel(option)) }, onClick = {
                        viewModel.onSort(option)
                        sortOpen = false
                    })
                }
            }
        }
    }
}

/** Sell / Buy / Receive / Pay, any combination. */
@Composable
private fun TypeRow(selected: Set<EntryType>, onToggle: (EntryType) -> Unit) {
    val options = listOf(
        EntryType.SELL to R.string.tx_type_sell,
        EntryType.BUY to R.string.tx_type_buy,
        EntryType.PAYMENT_RECEIVED to R.string.tx_type_receive,
        EntryType.PAYMENT_MADE to R.string.tx_type_pay,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (type, label) ->
            FilterChip(selected = type in selected, onClick = { onToggle(type) }, label = { Text(stringResource(label)) })
        }
    }
}

/** Totals of exactly the listed transactions. */
@Composable
private fun TotalsCard(totals: TransactionTotals) {
    Card(
        Modifier.fillMaxWidth().testTag(TX_TOTALS_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.tx_count, IndianFormatters.group(totals.count.toLong())),
                style = MaterialTheme.typography.titleSmall,
            )
            TotalLine(R.string.ledger_sales, IndianFormatters.money(totals.sales))
            TotalLine(R.string.ledger_purchases, IndianFormatters.money(totals.purchases))
            TotalLine(R.string.ledger_received, IndianFormatters.money(totals.received))
            TotalLine(R.string.ledger_paid, IndianFormatters.money(totals.paid))
        }
    }
}

@Composable
private fun TotalLine(label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun sortLabel(sort: TransactionSort): String = stringResource(
    when (sort) {
        TransactionSort.NEWEST -> R.string.tx_sort_newest
        TransactionSort.OLDEST -> R.string.tx_sort_oldest
        TransactionSort.AMOUNT_HIGH -> R.string.tx_sort_amount_high
        TransactionSort.AMOUNT_LOW -> R.string.tx_sort_amount_low
        TransactionSort.CUSTOMER -> R.string.tx_sort_customer
    },
)

/** A chip that opens a date picker; "Clear" sets the date back to "any". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionalDateChip(label: String, date: LocalDate?, today: LocalDate, onDate: (LocalDate?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
    )
    if (!open) return
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = CustomerValidator.EARLIEST_DATE.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcTimeMillis.toUtcDate().isAfter(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onDate(it.toUtcDate()) }
                open = false
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onDate(null)
                open = false
            }) { Text(stringResource(R.string.tx_clear)) }
        },
    ) { DatePicker(state = pickerState) }
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
