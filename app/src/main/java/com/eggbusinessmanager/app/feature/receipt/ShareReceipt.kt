package com.eggbusinessmanager.app.feature.receipt

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.reports.Catalog
import com.eggbusinessmanager.app.feature.reports.ReportSharer
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.receipt.BuildReceiptUseCase
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.catalog.ObserveUnitsUseCase
import com.eggbusinessmanager.domain.usecase.catalog.ObserveVarietiesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/**
 * "Share receipt" (FR-042a): builds the one-page PDF receipt for an entry and opens the Sharesheet
 * (WhatsApp, Gmail, Bluetooth, …) through the same private FileProvider as statements. PDF only.
 */
@HiltViewModel
class ShareReceiptViewModel @Inject constructor(
    private val buildReceipt: BuildReceiptUseCase,
    private val factory: ReceiptDocumentFactory,
    private val sharer: ReportSharer,
    private val observeUnits: ObserveUnitsUseCase,
    private val observeVarieties: ObserveVarietiesUseCase,
    private val clock: Clock,
) : ViewModel() {

    /** A Sharesheet to open, or null when the receipt could not be prepared. */
    private val _requests = Channel<Intent?>(Channel.BUFFERED)
    val requests: Flow<Intent?> = _requests.receiveAsFlow()

    private var busy = false

    fun share(rootId: Long, chooserTitle: String) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                _requests.send(prepare(rootId, chooserTitle))
            } finally {
                busy = false
            }
        }
    }

    private suspend fun prepare(rootId: Long, chooserTitle: String): Intent? {
        val receipt = (buildReceipt(rootId) as? DomainResult.Ok)?.value ?: return null
        val catalog = Catalog(
            units = observeUnits(includeInactive = true).first().associateBy { it.id },
            varieties = observeVarieties(includeInactive = true).first().associateBy { it.id },
        )
        val now = LocalDateTime.ofInstant(clock.now(), ZoneId.systemDefault())
        val document = factory.build(receipt, catalog, now)
        val uri = sharer.preparePdf(document, factory.fileName(receipt)) ?: return null
        val subject = "${document.title} ${receipt.entry.reference} · ${document.business.firstOrNull().orEmpty()}"
        return sharer.pdfOnlyChooser(uri, subject, chooserTitle)
    }
}

/** Returns the action that shares the receipt for a root id, and launches the Sharesheet when it is ready. */
@Composable
fun rememberShareReceipt(viewModel: ShareReceiptViewModel = hiltViewModel()): (Long) -> Unit {
    val context = LocalContext.current
    val chooserTitle = stringResource(R.string.receipt_share)
    val failed = stringResource(R.string.receipt_share_failed)
    val noApp = stringResource(R.string.share_no_app)
    LaunchedEffect(viewModel) {
        viewModel.requests.collect { intent ->
            if (intent == null) {
                Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            } else {
                try {
                    context.startActivity(intent)
                } catch (ignored: ActivityNotFoundException) {
                    // Handled: tell the owner instead of crashing.
                    Toast.makeText(context, noApp, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    return { rootId -> viewModel.share(rootId, chooserTitle) }
}
