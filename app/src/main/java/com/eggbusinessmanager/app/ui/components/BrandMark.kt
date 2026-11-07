package com.eggbusinessmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R

/** The egg-and-yolk brand mark, the same artwork the splash screen shows. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 120.dp) {
    Image(
        painter = painterResource(R.drawable.ic_splash_mark),
        contentDescription = stringResource(R.string.cd_brand_mark),
        modifier = modifier.size(size),
    )
}
