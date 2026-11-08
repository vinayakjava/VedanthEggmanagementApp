package com.eggbusinessmanager.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.StartupState
import com.eggbusinessmanager.domain.model.ThemeMode
import com.eggbusinessmanager.domain.settings.AppPreferences
import com.eggbusinessmanager.domain.settings.ObservePreferencesUseCase
import com.eggbusinessmanager.domain.usecase.settings.ObserveThemeModeUseCase
import com.eggbusinessmanager.domain.usecase.startup.PrepareAppUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide state: storage preparation (drives the splash screen) and the theme preference. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val prepareApp: PrepareAppUseCase,
    observeThemeMode: ObserveThemeModeUseCase,
    observePreferences: ObservePreferencesUseCase,
    private val logger: AppLogger,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Done(val startup: StartupState) : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Theme is read from the database only after storage is ready. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val themeMode: StateFlow<ThemeMode> = uiState
        .flatMapLatest { state ->
            if (state is UiState.Done && state.startup == StartupState.Ready) observeThemeMode() else flowOf(ThemeMode.SYSTEM)
        }
        .catch { error ->
            logger.warn(TAG, error) { "Theme preference unavailable; using system theme" }
            emit(ThemeMode.SYSTEM)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    /** Display preferences; null until storage is ready and they are read (the splash stays meanwhile). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val preferences: StateFlow<AppPreferences?> = uiState
        .flatMapLatest { state ->
            if (state is UiState.Done && state.startup == StartupState.Ready) observePreferences() else flowOf(null)
        }
        .catch { error ->
            logger.warn(TAG, error) { "Preferences unavailable; using defaults" }
            emit(AppPreferences())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        prepare()
    }

    fun retry() {
        if (_uiState.value is UiState.Loading) return
        _uiState.value = UiState.Loading
        prepare()
    }

    private fun prepare() {
        viewModelScope.launch { _uiState.value = UiState.Done(prepareApp()) }
    }

    private companion object {
        const val TAG = "AppViewModel"
    }
}
