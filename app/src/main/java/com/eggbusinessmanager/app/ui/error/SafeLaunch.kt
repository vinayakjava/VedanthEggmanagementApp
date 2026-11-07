package com.eggbusinessmanager.app.ui.error

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.domain.error.DomainError
import com.eggbusinessmanager.domain.logging.AppLogger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches work in [viewModelScope]; any unexpected exception is logged and reported through [onError]
 * instead of crashing the app (plan § System Architecture: "Errors"). Expected failures should come
 * back as `DomainResult.Err` from use cases, not as exceptions.
 */
fun ViewModel.launchSafely(
    logger: AppLogger,
    tag: String,
    onError: (DomainError) -> Unit,
    block: suspend () -> Unit,
): Job {
    val handler = CoroutineExceptionHandler { _, throwable ->
        logger.error(tag, throwable) { "Unexpected error in $tag" }
        onError(DomainError.Unexpected(throwable))
    }
    return viewModelScope.launch(handler) { block() }
}
