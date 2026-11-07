package com.eggbusinessmanager.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.app.ui.theme.EggTheme
import com.eggbusinessmanager.domain.ledger.Balance

/** "To receive ₹1,500.00" / "To pay ₹X" / "Settled". Colour is never the only signal: the words say it. */
@Composable
fun balanceLabel(balance: Balance): String = when (balance) {
    is Balance.ToReceive -> stringResource(R.string.balance_to_receive, IndianFormatters.money(balance.amount))
    is Balance.ToPay -> stringResource(R.string.balance_to_pay, IndianFormatters.money(balance.amount))
    Balance.Settled -> stringResource(R.string.balance_settled)
}

@Composable
private fun balanceColors(balance: Balance): Pair<Color, Color> = when (balance) {
    is Balance.ToReceive -> EggTheme.money.receivableContainer to EggTheme.money.onReceivableContainer
    is Balance.ToPay -> EggTheme.money.payableContainer to EggTheme.money.onPayableContainer
    Balance.Settled -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
}

/** Compact balance pill for lists. */
@Composable
fun BalanceChip(balance: Balance, modifier: Modifier = Modifier) {
    val (container, content) = balanceColors(balance)
    Surface(modifier = modifier, color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            balanceLabel(balance),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Large balance text for headers, coloured by direction. */
@Composable
fun BalanceHeadline(balance: Balance, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.headlineSmall) {
    val color = when (balance) {
        is Balance.ToReceive -> EggTheme.money.receivable
        is Balance.ToPay -> EggTheme.money.payable
        Balance.Settled -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(balanceLabel(balance), style = style, color = color, modifier = modifier)
}
