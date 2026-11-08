package com.eggbusinessmanager.app.feature.receipt

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R

/** "Share receipt" on a save confirmation (FR-042a). */
@Composable
fun ShareReceiptButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.padding(top = 8.dp)) {
        Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.receipt_share), Modifier.padding(start = 8.dp))
    }
}
