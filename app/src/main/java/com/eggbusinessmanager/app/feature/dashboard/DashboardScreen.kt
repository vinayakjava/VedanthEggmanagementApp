package com.eggbusinessmanager.app.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.transactions.TransactionItem
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.report.DashboardPeriod
import com.eggbusinessmanager.domain.report.DashboardSummary
import com.eggbusinessmanager.domain.report.TradeTotals
import com.eggbusinessmanager.domain.usecase.report.ObserveBackupReminderUseCase
import com.eggbusinessmanager.domain.usecase.report.ObserveDashboardUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Test tags: one per figure, so UI tests can read a value without depending on layout. */
object DashboardTags {
    const val PERIOD_SALES = "dash_period_sales"
    const val PERIOD_PURCHASES = "dash_period_purchases"
    const val NET_CASH = "dash_net_cash"
    const val TOP_CUSTOMERS = "dash_top_customers"
    const val BACKUP_BANNER = "dash_backup_banner"
    const val RECEIVABLES = "dash_receivables"
    const val PAYABLES = "dash_payables"
    const val TOTAL_SALES = "dash_total_sales"
    const val TOTAL_PURCHASES = "dash_total_purchases"
    const val CUSTOMERS = "dash_customers"
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    observeDashboard: ObserveDashboardUseCase,
    observeBackupReminder: ObserveBackupReminderUseCase,
) : ViewModel() {
    private val _period = MutableStateFlow(DashboardPeriod.TODAY)
    val period: StateFlow<DashboardPeriod> = _period.asStateFlow()

    /** Null until the first figures arrive from the database. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val summary: StateFlow<DashboardSummary?> = _period.flatMapLatest { observeDashboard(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)

    /** US6-5: no successful backup in the last 7 days. */
    val backupDue: StateFlow<Boolean> = observeBackupReminder()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), false)

    fun onPeriod(period: DashboardPeriod) {
        _period.value = period
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Quick actions and live business figures, all computed from the database (Phase 6). */
@Composable
fun DashboardScreen(
    onSell: () -> Unit,
    onBuy: () -> Unit,
    onReceive: () -> Unit,
    onPay: () -> Unit,
    onAddCustomer: () -> Unit,
    onOpenCustomer: (Long) -> Unit,
    onBackup: () -> Unit,
    onOpenEntry: (Long) -> Unit,
    onSeeAll: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val backupDue by viewModel.backupDue.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (backupDue) BackupBanner(onBackup)
            val newAccount = summary?.totalCustomers == 0
            if (newAccount) FirstCustomerGuide(onAddCustomer)
            // While the guide shows its own "Add customer", the quick action would only repeat it.
            QuickActions(onSell, onBuy, onReceive, onPay, onAddCustomer.takeUnless { newAccount })
            summary?.let { d ->
                PeriodFigures(d, period, viewModel::onPeriod)
                Dues(d, onOpenCustomer)
                Figures(d, onOpenEntry, onSeeAll)
            }
        }
    }
}

/** US6-5: a non-blocking reminder with "Back up now". */
@Composable
private fun BackupBanner(onBackup: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag(DashboardTags.BACKUP_BANNER),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dash_backup_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.dash_backup_body), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onBackup, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.dash_backup_now)) }
        }
    }
}

/** US6-6: a short guide on a brand-new account. */
@Composable
private fun FirstCustomerGuide(onAddCustomer: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.dash_first_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.dash_first_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAddCustomer, modifier = Modifier.padding(top = 4.dp)) { Text(stringResource(R.string.customers_add)) }
        }
    }
}

/** US6-1: the selected period's sales, purchases, payments and net cash. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodFigures(d: DashboardSummary, period: DashboardPeriod, onPeriod: (DashboardPeriod) -> Unit) {
    val options = listOf(
        DashboardPeriod.TODAY to R.string.dash_today,
        DashboardPeriod.WEEK to R.string.dash_week,
        DashboardPeriod.MONTH to R.string.dash_month,
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == period,
                onClick = { onPeriod(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(stringResource(label)) },
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TradeStat(R.string.dash_sales, d.periodSales, DashboardTags.PERIOD_SALES, Modifier.weight(1f))
        TradeStat(R.string.dash_purchases, d.periodPurchases, DashboardTags.PERIOD_PURCHASES, Modifier.weight(1f))
    }
    val net = d.netCash
    Stat(
        stringResource(R.string.dash_net_cash),
        IndianFormatters.money(net),
        stringResource(R.string.dash_cash_detail, IndianFormatters.money(d.periodReceived), IndianFormatters.money(d.periodPaid)),
        DashboardTags.NET_CASH,
        Modifier.fillMaxWidth(),
        valueColor = if (net.isNegative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

/** US6-2: dues in both directions (never netted) and who owes the most. */
@Composable
private fun Dues(d: DashboardSummary, onOpenCustomer: (Long) -> Unit) {
    Section(stringResource(R.string.dash_dues))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat(
            stringResource(R.string.dash_receivables), IndianFormatters.money(d.receivables),
            stringResource(R.string.dash_customers_count, d.receivableCustomers), DashboardTags.RECEIVABLES, Modifier.weight(1f),
            valueColor = MaterialTheme.colorScheme.primary,
        )
        Stat(
            stringResource(R.string.dash_payables), IndianFormatters.money(d.payables),
            stringResource(R.string.dash_customers_count, d.payableCustomers), DashboardTags.PAYABLES, Modifier.weight(1f),
            valueColor = MaterialTheme.colorScheme.error,
        )
    }
    if (d.topReceivables.isEmpty()) return
    Card(Modifier.fillMaxWidth().testTag(DashboardTags.TOP_CUSTOMERS)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.dash_top_customers),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            d.topReceivables.forEach { top ->
                ListItem(
                    headlineContent = { Text(top.customer.name) },
                    trailingContent = {
                        Text(IndianFormatters.money(top.netBalance), color = MaterialTheme.colorScheme.primary)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onOpenCustomer(top.customer.id) },
                )
            }
        }
    }
}

@Composable
private fun QuickActions(onSell: () -> Unit, onBuy: () -> Unit, onReceive: () -> Unit, onPay: () -> Unit, onAddCustomer: (() -> Unit)?) {
    Text(stringResource(R.string.dashboard_quick_actions), style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onSell, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
            Text(stringResource(R.string.trade_sell_title), style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalButton(onClick = onBuy, modifier = Modifier.weight(1f).heightIn(min = 64.dp)) {
            Text(stringResource(R.string.trade_buy_title), style = MaterialTheme.typography.titleMedium)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onReceive, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.payment_receive_title), textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = onPay, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
            Text(stringResource(R.string.payment_pay_title), textAlign = TextAlign.Center)
        }
    }
    onAddCustomer?.let { TextButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.customers_add)) } }
}

@Composable
private fun Figures(d: DashboardSummary, onOpenEntry: (Long) -> Unit, onSeeAll: () -> Unit) {
    Section(stringResource(R.string.dash_all_time))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TradeStat(R.string.dash_total_sales, d.sales, DashboardTags.TOTAL_SALES, Modifier.weight(1f))
        TradeStat(R.string.dash_total_purchases, d.purchases, DashboardTags.TOTAL_PURCHASES, Modifier.weight(1f))
    }
    Stat(
        stringResource(R.string.dash_total_customers), IndianFormatters.group(d.totalCustomers.toLong()),
        stringResource(R.string.dash_active_customers, d.activeCustomers), DashboardTags.CUSTOMERS, Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.dash_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onSeeAll) { Text(stringResource(R.string.dash_see_all)) }
    }
    if (d.recent.isEmpty()) {
        Text(
            stringResource(R.string.dash_no_recent),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    d.recent.forEach { TransactionItem(it, onOpenEntry) }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

/** Value, count and eggs of sales or purchases. */
@Composable
private fun TradeStat(title: Int, totals: TradeTotals, tag: String, modifier: Modifier) {
    Stat(
        stringResource(title),
        IndianFormatters.money(totals.value),
        stringResource(R.string.dash_trade_detail, IndianFormatters.group(totals.count.toLong()), IndianFormatters.group(totals.eggs)),
        tag,
        modifier,
    )
}

@Composable
private fun Stat(title: String, value: String, detail: String, tag: String, modifier: Modifier, valueColor: Color = Color.Unspecified) {
    Card(modifier.testTag(tag), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
