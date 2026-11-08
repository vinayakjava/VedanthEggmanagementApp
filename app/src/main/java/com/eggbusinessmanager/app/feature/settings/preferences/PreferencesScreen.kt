package com.eggbusinessmanager.app.feature.settings.preferences

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.ui.components.FormScreen
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.catalog.MeasurementUnit
import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.money.Money
import com.eggbusinessmanager.domain.repository.SettingsRepository
import com.eggbusinessmanager.domain.settings.AppPreferences
import com.eggbusinessmanager.domain.settings.CurrencySymbol
import com.eggbusinessmanager.domain.settings.DateStyle
import com.eggbusinessmanager.domain.settings.DigitGrouping
import com.eggbusinessmanager.domain.settings.ObservePreferencesUseCase
import com.eggbusinessmanager.domain.settings.UpdatePreferencesUseCase
import com.eggbusinessmanager.domain.usecase.catalog.ObserveUnitsUseCase
import com.eggbusinessmanager.domain.usecase.settings.ObserveThemeModeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class PreferencesViewModel @Inject constructor(
    observePreferences: ObservePreferencesUseCase,
    observeTheme: ObserveThemeModeUseCase,
    observeUnits: ObserveUnitsUseCase,
    private val update: UpdatePreferencesUseCase,
    private val settings: SettingsRepository,
) : ViewModel() {
    val preferences: StateFlow<AppPreferences?> =
        observePreferences().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), null)
    val theme: StateFlow<ThemeMode> = observeTheme().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), ThemeMode.SYSTEM)
    val units: StateFlow<List<MeasurementUnit>> =
        observeUnits().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), emptyList())

    fun change(transform: AppPreferences.() -> AppPreferences) {
        val current = preferences.value ?: return
        viewModelScope.launch { update(current.transform()) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}

/** Theme, currency display, date format and the default unit (Phase 11). Changes apply app-wide at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreferencesScreen(onBack: () -> Unit, viewModel: PreferencesViewModel = hiltViewModel()) {
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val p = preferences
    FormScreen(title = stringResource(R.string.more_preferences), onBack = onBack) {
        if (p == null) return@FormScreen
        LanguageSection()
        Section(stringResource(R.string.pref_theme)) {
            val options = listOf(
                ThemeMode.SYSTEM to R.string.pref_theme_system,
                ThemeMode.LIGHT to R.string.pref_theme_light,
                ThemeMode.DARK to R.string.pref_theme_dark,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = theme == mode,
                        onClick = { viewModel.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
        Section(stringResource(R.string.pref_currency)) {
            CurrencySymbol.entries.forEach { symbol ->
                Choice(symbol.text, p.currencySymbol == symbol) { viewModel.change { copy(currencySymbol = symbol) } }
            }
            Text(stringResource(R.string.pref_grouping), style = MaterialTheme.typography.labelLarge)
            Choice(stringResource(R.string.pref_grouping_indian), p.grouping == DigitGrouping.INDIAN) {
                viewModel.change { copy(grouping = DigitGrouping.INDIAN) }
            }
            Choice(stringResource(R.string.pref_grouping_international), p.grouping == DigitGrouping.INTERNATIONAL) {
                viewModel.change { copy(grouping = DigitGrouping.INTERNATIONAL) }
            }
            Text(stringResource(R.string.pref_example, example(p)), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.pref_currency_note), style = MaterialTheme.typography.bodySmall)
        }
        Section(stringResource(R.string.pref_date)) {
            val locale = LocalConfiguration.current.locales[0]
            val today = LocalDate.now()
            DateStyle.entries.forEach { style ->
                Choice(DateTimeFormatter.ofPattern(style.pattern, locale).format(today), p.dateStyle == style) {
                    viewModel.change { copy(dateStyle = style) }
                }
            }
        }
        Section(stringResource(R.string.pref_sell_buy)) {
            val none = stringResource(R.string.pref_default_unit_none)
            DropdownField(
                label = stringResource(R.string.pref_default_unit),
                options = listOf<MeasurementUnit?>(null) + units,
                selected = units.firstOrNull { it.id == p.defaultUnitId },
                optionLabel = { unit -> unit?.let { stringResource(R.string.unit_option, it.name, it.piecesPerUnit) } ?: none },
                onSelect = { unit -> viewModel.change { copy(defaultUnitId = unit?.id) } },
            )
        }
    }
}

/**
 * App language (FR-061a): phone language, English or Hindi. Android applies it at once (the screen is
 * recreated) and remembers it per app; it is a phone setting, so it is not part of backups.
 */
@Composable
private fun LanguageSection() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    Section(stringResource(R.string.pref_language)) {
        listOf(
            "" to R.string.pref_language_system,
            "en" to R.string.pref_language_english,
            "hi" to R.string.pref_language_hindi,
        ).forEach { (tag, label) ->
            Choice(stringResource(label), current == tag) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
        }
    }
}

/** "₹1,23,456.78" in the chosen symbol and grouping, before it is applied. */
private fun example(p: AppPreferences): String = IndianFormatters.money(Money(EXAMPLE_PAISE), p)

private const val EXAMPLE_PAISE = 12_345_678L

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
