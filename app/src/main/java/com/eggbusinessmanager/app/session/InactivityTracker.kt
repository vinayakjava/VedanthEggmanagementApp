package com.eggbusinessmanager.app.session

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Reports every touch anywhere below this modifier as user activity, without consuming it, so the
 * inactivity auto-lock timer restarts (research R8).
 */
fun Modifier.reportUserActivity(onActivity: () -> Unit): Modifier = pointerInput(onActivity) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            onActivity()
        }
    }
}
