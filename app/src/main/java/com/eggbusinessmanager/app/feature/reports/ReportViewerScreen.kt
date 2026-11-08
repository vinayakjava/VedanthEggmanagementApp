package com.eggbusinessmanager.app.feature.reports

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.feature.trade.CustomerPicker
import com.eggbusinessmanager.app.ui.components.EmptyState
import com.eggbusinessmanager.app.ui.components.ErrorBanner
import com.eggbusinessmanager.app.ui.components.OptionalDateChip
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.export.Align
import com.eggbusinessmanager.domain.export.Cell
import com.eggbusinessmanager.domain.export.ExportFormat
import com.eggbusinessmanager.domain.export.ReportDocument
import com.eggbusinessmanager.domain.export.Table

const val REPORT_PREVIEW_TAG = "report_preview"

/** A report: its parameters, an on-screen preview and Save as PDF / Excel / CSV to a folder the owner picks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportViewerScreen(onBack: () -> Unit, viewModel: ReportViewerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.messageShown()
        }
    }
    // One "save as" launcher per format: the Storage Access Framework picker lets the owner choose folder and name.
    val launchers = ExportFormat.entries.associateWith { format ->
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mimeType)) { uri -> viewModel.export(format, uri) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(reportTitle(viewModel.kind))) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.document != null) {
                ExportBar(busy = state.exporting) { format -> launchers.getValue(format).launch(viewModel.suggestedName(format)) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Parameters(state, viewModel) { pickerOpen = true }
                state.error?.let { ErrorBanner(it.asString()) }
                val document = state.document
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    viewModel.kind.requiresCustomer && state.request.customerId == null ->
                        EmptyState(title = stringResource(R.string.rv_choose_customer), body = "", iconRes = R.drawable.ic_nav_reports)
                    document != null -> Preview(document)
                }
            }
        }
    }

    if (pickerOpen) {
        val results by viewModel.pickerResults.collectAsStateWithLifecycle()
        val text by viewModel.pickerText.collectAsStateWithLifecycle()
        CustomerPicker(
            results = results,
            text = text,
            onSearch = viewModel::onPickerSearch,
            onPick = {
                viewModel.onCustomer(it)
                pickerOpen = false
            },
            onAddNew = null,
            onDismiss = { pickerOpen = false },
            onClear = if (viewModel.kind.requiresCustomer) {
                null
            } else {
                {
                    viewModel.onCustomer(null)
                    pickerOpen = false
                }
            },
        )
    }
}

@Composable
private fun Parameters(state: ReportViewerViewModel.UiState, viewModel: ReportViewerViewModel, onPickCustomer: () -> Unit) {
    val kind = viewModel.kind
    if (!kind.usesDateRange && !kind.acceptsCustomer) return
    val locale = LocalConfiguration.current.locales[0]
    val r = state.request
    val noCustomer = if (kind.requiresCustomer) R.string.rv_customer else R.string.tx_all_customers
    val customerLabel = state.customerName ?: stringResource(noCustomer)
    val fromLabel = r.from?.let { stringResource(R.string.tx_from, IndianFormatters.date(it, locale)) }
        ?: stringResource(R.string.tx_from_any)
    val toLabel = r.to?.let { stringResource(R.string.tx_to, IndianFormatters.date(it, locale)) } ?: stringResource(R.string.tx_to_any)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (kind.acceptsCustomer) {
            FilterChip(
                selected = r.customerId != null,
                onClick = onPickCustomer,
                label = { Text(customerLabel) },
            )
        }
        if (kind.usesDateRange) {
            OptionalDateChip(
                label = fromLabel,
                date = r.from,
                today = viewModel.today,
                onDate = viewModel::onFrom,
            )
            OptionalDateChip(
                label = toLabel,
                date = r.to,
                today = viewModel.today,
                onDate = viewModel::onTo,
            )
        }
    }
}

@Composable
private fun ExportBar(busy: Boolean, onExport: (ExportFormat) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.rv_export_heading), style = MaterialTheme.typography.labelLarge)
            val formats = listOf(
                ExportFormat.PDF to R.string.rv_export_pdf,
                ExportFormat.XLSX to R.string.rv_export_xlsx,
                ExportFormat.CSV to R.string.rv_export_csv,
            )
            formats.forEach { (format, label) ->
                OutlinedButton(onClick = { onExport(format) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(label))
                }
            }
        }
    }
}

/** The document as it will look in the PDF, at phone size: letterhead, summary, then the tables. */
@Composable
private fun Preview(doc: ReportDocument) {
    Card(
        Modifier.fillMaxWidth().testTag(REPORT_PREVIEW_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            doc.business.firstOrNull()?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
            doc.business.drop(1).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(doc.title, style = MaterialTheme.typography.titleLarge)
            doc.details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(doc.generatedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (doc.summary.isNotEmpty()) Summary(doc.summary)
            doc.tables.forEach { PreviewTable(it) }
            doc.footnote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Summary(items: List<Pair<String, Cell>>) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(
                            value.display,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

/** Wide tables scroll sideways; each column gets a width in proportion to its weight. */
@Composable
private fun PreviewTable(table: Table) {
    table.heading?.let { Text(it, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
    val unit = 64.dp
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        TableRow(table, table.columns.map { Cell.Text(it.title) }, unit, header = true)
        if (table.rows.isEmpty()) {
            table.emptyText?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp)) }
        }
        table.rows.forEach { TableRow(table, it, unit) }
        table.footer?.let { TableRow(table, it, unit, footer = true) }
    }
}

@Composable
private fun TableRow(table: Table, cells: List<Cell>, unit: androidx.compose.ui.unit.Dp, header: Boolean = false, footer: Boolean = false) {
    val background = if (header) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface
    Row(Modifier.background(background)) {
        cells.forEachIndexed { i, cell ->
            val column = table.columns[i]
            Text(
                cell.display,
                modifier = Modifier.width(unit * column.weight).padding(horizontal = 4.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header || footer) FontWeight.Bold else FontWeight.Normal,
                textAlign = if (column.align == Align.END) TextAlign.End else TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}
