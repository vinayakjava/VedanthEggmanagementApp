package com.eggbusinessmanager.app.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.BrandMark

/** Quick actions for the daily work. Period totals and dues arrive with US6. */
@Composable
fun DashboardScreen(onSell: () -> Unit, onBuy: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BrandMark(size = 96.dp)
            Text(stringResource(R.string.dashboard_quick_actions), style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSell, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) {
                    Text(stringResource(R.string.trade_sell_title), style = MaterialTheme.typography.titleMedium)
                }
                FilledTonalButton(onClick = onBuy, modifier = Modifier.weight(1f).heightIn(min = 72.dp)) {
                    Text(stringResource(R.string.trade_buy_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                stringResource(R.string.dashboard_more_soon),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
