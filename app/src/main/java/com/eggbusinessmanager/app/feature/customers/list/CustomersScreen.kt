package com.eggbusinessmanager.app.feature.customers.list

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
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.BalanceChip
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary

/** Test tag of the search box. */
const val SEARCH_TAG = "customer_search"

/** Test tag of the "Add customer" button. */
const val ADD_CUSTOMER_TAG = "add_customer"

/** The Customers tab (US2). */
@Composable
fun CustomersScreen(
    onOpenCustomer: (Long) -> Unit,
    onAddCustomer: (prefill: String) -> Unit,
    viewModel: CustomersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.queryText.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onAddCustomer("") },
                modifier = Modifier.testTag(ADD_CUSTOMER_TAG),
                icon = { Icon(painterResource(R.drawable.ic_person_add), contentDescription = null) },
                text = { Text(stringResource(R.string.customers_add)) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                OutlinedTextField(
                    value = query.text,
                    onValueChange = viewModel::onSearch,
                    placeholder = { Text(stringResource(R.string.customers_search_hint)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag(SEARCH_TAG),
                )
                FilterRow(query.filter, query.sort, viewModel::onFilter, viewModel::onSort)
                when {
                    state.loading -> Unit
                    state.customers.isEmpty() && query.text.isNotBlank() -> NoResults(query.text) { onAddCustomer(query.text.trim()) }
                    state.customers.isEmpty() && query.filter == CustomerFilter.ACTIVE -> EmptyState(
                        title = stringResource(R.string.customers_empty_title),
                        body = stringResource(R.string.customers_empty_body),
                        iconRes = R.drawable.ic_nav_customers,
                    )
                    state.customers.isEmpty() -> EmptyState(title = stringResource(R.string.customers_none_found), body = "")
                    else -> CustomerList(state.customers, onOpenCustomer)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: CustomerFilter,
    sort: CustomerSort,
    onFilter: (CustomerFilter) -> Unit,
    onSort: (CustomerSort) -> Unit,
) {
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CustomerFilter.entries.forEach { option ->
            FilterChip(selected = option == filter, onClick = { onFilter(option) }, label = { Text(filterLabel(option)) })
        }
        Box {
            TextButton(onClick = { sortOpen = true }) {
                Icon(painterResource(R.drawable.ic_sort), contentDescription = null)
                Text(sortLabel(sort), modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                CustomerSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(sortLabel(option)) }, onClick = {
                        onSort(option)
                        sortOpen = false
                    })
                }
            }
        }
    }
}

@Composable
private fun CustomerList(customers: List<CustomerSummary>, onOpen: (Long) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(customers, key = { it.customer.id }) { summary ->
            Card(onClick = { onOpen(summary.customer.id) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            summary.customer.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val details = listOfNotNull(
                            summary.customer.mobile,
                            summary.lastActivity?.let {
                                stringResource(R.string.customer_last_activity, IndianFormatters.date(it, locale))
                            },
                        )
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!summary.customer.isActive) {
                            Text(
                                stringResource(R.string.customer_deactivated_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    BalanceChip(summary.balance, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun NoResults(text: String, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.customers_none_found), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.customers_add_typed, text.trim())) }
    }
}

@Composable
private fun filterLabel(filter: CustomerFilter) = stringResource(
    when (filter) {
        CustomerFilter.ACTIVE -> R.string.filter_active
        CustomerFilter.TO_RECEIVE -> R.string.filter_to_receive
        CustomerFilter.TO_PAY -> R.string.filter_to_pay
        CustomerFilter.SETTLED -> R.string.filter_settled
        CustomerFilter.DEACTIVATED -> R.string.filter_deactivated
    },
)

@Composable
private fun sortLabel(sort: CustomerSort) = stringResource(
    when (sort) {
        CustomerSort.NAME -> R.string.sort_name
        CustomerSort.HIGHEST_RECEIVABLE -> R.string.sort_receivable
        CustomerSort.HIGHEST_PAYABLE -> R.string.sort_payable
        CustomerSort.LAST_ACTIVITY -> R.string.sort_last_activity
    },
)
