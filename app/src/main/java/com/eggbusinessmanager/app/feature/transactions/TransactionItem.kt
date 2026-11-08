package com.eggbusinessmanager.app.feature.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.payment.modeLabel
import com.eggbusinessmanager.app.feature.trade.entryTypeLabel
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.ledger.EntryType
import com.eggbusinessmanager.domain.report.TransactionRow

/** One transaction in a list: type and customer, date · reference · detail, amount. Opens its detail. */
@Composable
fun TransactionItem(row: TransactionRow, onOpen: (Long) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val entry = row.entry
    val typeName = stringResource(entryTypeLabel(entry.type))
    val detail = entry.trade?.let { stringResource(R.string.trade_pieces, IndianFormatters.group(it.pieces)) }
        ?: entry.payment?.let { stringResource(modeLabel(it.mode)) }
    Card(onClick = { onOpen(entry.rootId) }, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.customerName, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(typeName, IndianFormatters.date(entry.date, locale), entry.reference, detail).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(IndianFormatters.money(entry.amount), style = MaterialTheme.typography.titleMedium, color = amountColor(entry.type))
        }
    }
}

/** Money coming in (sales, receipts) in the primary colour; money going out in the default text colour. */
@Composable
private fun amountColor(type: EntryType): Color = when (type) {
    EntryType.SELL, EntryType.PAYMENT_RECEIVED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurface
}
