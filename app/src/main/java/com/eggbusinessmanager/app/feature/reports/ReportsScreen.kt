package com.eggbusinessmanager.app.feature.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.report.ReportKind

private fun description(kind: ReportKind): Int = when (kind) {
    ReportKind.CUSTOMER_STATEMENT -> R.string.rk_statement_desc
    ReportKind.DAILY_SALES -> R.string.rk_daily_sales_desc
    ReportKind.DAILY_PURCHASES -> R.string.rk_daily_purchases_desc
    ReportKind.MONTHLY_SALES -> R.string.rk_monthly_sales_desc
    ReportKind.MONTHLY_PURCHASES -> R.string.rk_monthly_purchases_desc
    ReportKind.RECEIVABLES -> R.string.rk_receivables_desc
    ReportKind.PAYABLES -> R.string.rk_payables_desc
    ReportKind.PAYMENT_HISTORY -> R.string.rk_payments_desc
    ReportKind.TRANSACTIONS -> R.string.rk_transactions_desc
}

/** The Reports tab: every report, each opening its viewer. */
@Composable
fun ReportsScreen(onOpen: (ReportKind) -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ReportKind.entries) { kind ->
                Card(onClick = { onOpen(kind) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(stringResource(reportTitle(kind))) },
                        supportingContent = { Text(stringResource(description(kind))) },
                        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
