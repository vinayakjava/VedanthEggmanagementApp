package com.eggbusinessmanager.app.ui.error

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

/** Text a ViewModel can hand to the UI without holding a Context. Resolved in the current language. */
@Immutable
sealed interface UiText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    // stringResource takes varargs; the spread is unavoidable and the arrays are tiny.
    @Suppress("SpreadOperator")
    @Composable
    fun asString(): String = when (this) {
        is Resource -> stringResource(id, *args.toTypedArray())
    }
}
