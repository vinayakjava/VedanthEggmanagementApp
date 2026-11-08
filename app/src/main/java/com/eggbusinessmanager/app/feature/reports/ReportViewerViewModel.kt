package com.eggbusinessmanager.app.feature.reports

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.navigation.ReportViewerRoute
import com.eggbusinessmanager.app.ui.error.ErrorMessages
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.domain.customer.CustomerFilter
import com.eggbusinessmanager.domain.customer.CustomerQuery
import com.eggbusinessmanager.domain.customer.CustomerSort
import com.eggbusinessmanager.domain.customer.CustomerSummary
import com.eggbusinessmanager.domain.error.DomainResult
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.ReportShareText
import com.eggbusinessmanager.domain.report.ReportData
import com.eggbusinessmanager.domain.report.ReportKind
import com.eggbusinessmanager.domain.report.ReportRequest
import com.eggbusinessmanager.domain.repository.Clock
import com.eggbusinessmanager.domain.usecase.catalog.ObserveUnitsUseCase
import com.eggbusinessmanager.domain.usecase.catalog.ObserveVarietiesUseCase
import com.eggbusinessmanager.domain.usecase.customer.ObserveCustomerUseCase
import com.eggbusinessmanager.domain.usecase.customer.SearchCustomersUseCase
import com.eggbusinessmanager.domain.usecase.profile.ObserveBusinessProfileUseCase
import com.eggbusinessmanager.domain.usecase.report.LoadReportUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

/** One report (Phase 7): parameters, a live preview built from the database, and export to a picked file. */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ReportViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val loadReport: LoadReportUseCase,
    private val observeProfile: ObserveBusinessProfileUseCase,
    private val observeUnits: ObserveUnitsUseCase,
    private val observeVarieties: ObserveVarietiesUseCase,
    private val observeCustomer: ObserveCustomerUseCase,
    private val searchCustomers: SearchCustomersUseCase,
    private val factory: ReportDocumentFactory,
    private val saver: ReportFileSaver,
    private val sharer: ReportSharer,
    private val clock: Clock,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ReportViewerRoute>()
    val kind: ReportKind = ReportKind.valueOf(route.kind)
    val today: LocalDate = clock.today()

    data class UiState(
        val request: ReportRequest,
        val customerName: String? = null,
        val loading: Boolean = true,
        val data: ReportData? = null,
        val document: ReportDocument? = null,
        val error: UiText? = null,
        val exporting: Boolean = false,
        /** Result of the last export, shown once. */
        val message: UiText? = null,
    )

    private val _state = MutableStateFlow(UiState(defaultRequest()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val pickerQuery = MutableStateFlow("")
    val pickerText: StateFlow<String> = pickerQuery.asStateFlow()
    val pickerResults: StateFlow<List<CustomerSummary>> = pickerQuery
        .flatMapLatest { searchCustomers(CustomerQuery(it, CustomerFilter.ACTIVE, CustomerSort.NAME)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    private var loadJob: Job? = null

    init {
        route.customerId.takeIf { it > 0 }?.let(::onCustomer) ?: reload()
    }

    /** Sensible starting periods: today for daily reports, the last 12 months for monthly, this month otherwise. */
    private fun defaultRequest(): ReportRequest {
        val (from, to) = when (kind) {
            ReportKind.DAILY_SALES, ReportKind.DAILY_PURCHASES -> today to today
            ReportKind.MONTHLY_SALES, ReportKind.MONTHLY_PURCHASES -> today.minusMonths(MONTHS_BACK).withDayOfMonth(1) to today
            ReportKind.RECEIVABLES, ReportKind.PAYABLES -> null to null
            else -> today.withDayOfMonth(1) to today
        }
        return ReportRequest(kind, from, to)
    }

    fun onFrom(date: LocalDate?) = change { copy(from = date) }

    fun onTo(date: LocalDate?) = change { copy(to = date) }

    fun onPickerSearch(text: String) {
        pickerQuery.value = text
    }

    fun onCustomer(id: Long?) {
        pickerQuery.value = ""
        viewModelScope.launch {
            val name = id?.let { observeCustomer(it).first()?.customer?.name }
            _state.update { it.copy(customerName = name) }
            change { copy(customerId = id) }
        }
    }

    private fun change(transform: ReportRequest.() -> ReportRequest) {
        _state.update { it.copy(request = it.request.transform()) }
        reload()
    }

    /** Rebuilds the preview from the current database contents. */
    fun reload() {
        loadJob?.cancel()
        val request = _state.value.request
        if (kind.requiresCustomer && request.customerId == null) {
            _state.update { it.copy(loading = false, data = null, document = null, error = null) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            when (val result = loadReport(request)) {
                is DomainResult.Ok -> {
                    val catalog = Catalog(
                        units = observeUnits(includeInactive = true).first().associateBy { it.id },
                        varieties = observeVarieties(includeInactive = true).first().associateBy { it.id },
                    )
                    val now = LocalDateTime.ofInstant(clock.now(), ZoneId.systemDefault())
                    val document = factory.build(result.value, observeProfile().first(), catalog, now)
                    _state.update { it.copy(loading = false, data = result.value, document = document) }
                }
                is DomainResult.Err -> _state.update {
                    it.copy(loading = false, data = null, document = null, error = ErrorMessages.forError(result.error))
                }
            }
        }
    }

    fun suggestedName(format: ExportFormat): String =
        _state.value.data?.let { factory.fileName(it, format) } ?: "report.${format.extension}"

    /** [uri] comes from the system "save as" picker; null when the owner cancelled. */
    fun export(format: ExportFormat, uri: Uri?) {
        val document = _state.value.document ?: return
        if (uri == null || _state.value.exporting) return
        _state.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            val ok = saver.save(uri, document, format)
            _state.update {
                it.copy(
                    exporting = false,
                    message = if (ok) {
                        UiText.Resource(R.string.rv_saved, listOf(suggestedName(format)))
                    } else {
                        UiText.Resource(R.string.rv_save_failed)
                    },
                )
            }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    /** Sharesheet intents to launch, one per share tap. */
    private val _shareRequests = Channel<Intent>(Channel.BUFFERED)
    val shareRequests: Flow<Intent> = _shareRequests.receiveAsFlow()

    /**
     * Shares the report as a PDF with the text summary as the message (Phase 8). [closing] and
     * [chooserTitle] are passed in already translated.
     */
    fun sharePdf(closing: String, chooserTitle: String) {
        val document = _state.value.document ?: return
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true, message = null) }
        viewModelScope.launch {
            val uri = sharer.preparePdf(document, suggestedName(ExportFormat.PDF))
            _state.update { it.copy(exporting = false, message = if (uri == null) UiText.Resource(R.string.share_failed) else null) }
            if (uri != null) {
                val subject = ReportShareText.subject(document)
                _shareRequests.send(sharer.pdfChooser(uri, subject, ReportShareText.body(document, closing), chooserTitle))
            }
        }
    }

    /** Shares only the readable summary, without an attachment. */
    fun shareText(chooserTitle: String) {
        val document = _state.value.document ?: return
        val text = ReportShareText.body(document, document.generatedAt)
        viewModelScope.launch { _shareRequests.send(sharer.textChooser(ReportShareText.subject(document), text, chooserTitle)) }
    }

    private companion object {
        const val STOP_MS = 5_000L
        const val MONTHS_BACK = 11L
    }
}
