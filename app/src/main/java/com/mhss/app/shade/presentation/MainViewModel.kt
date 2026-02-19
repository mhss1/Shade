package com.mhss.app.shade.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mhss.app.shade.util.PreferenceManager
import android.os.Build
import com.mhss.app.shade.detection.DEFAULT_CONFIDENCE_PERCENT
import com.mhss.app.shade.detection.DEFAULT_DOWNSAMPLE_FACTOR
import com.mhss.app.shade.service.CaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.mhss.app.shade.service.ScreenCaptureService
import org.koin.android.annotation.KoinViewModel

data class MainUiState(
    val captureState: CaptureState = CaptureState.IDLE,
    val confidence: Float = DEFAULT_CONFIDENCE_PERCENT,
    val performanceModeEnabled: Boolean = false,
    val detailedModeEnabled: Boolean = false,
    val overlayOpacity: Float = 100f,
    val fullScreenModeEnabled: Boolean = false,
    val pixelationLevel: Int = DEFAULT_DOWNSAMPLE_FACTOR,
    val isAccessibilityEnabled: Boolean = false,
    val isSingleAppRecordingSupported: Boolean = true,
    val showOnboardingFlow: Boolean = false,
    val showUnsupportedDeviceDialog: Boolean = false,
    val shouldShowSingleAppCaptureTipOnStart: Boolean = false,
    val showSingleAppCaptureTipDialog: Boolean = false,
    val autoStartApps: Set<String> = emptySet(),
    val showAppSelectionDialog: Boolean = false
)

@KoinViewModel
class MainViewModel(
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    init {
        val isSingleAppRecordingSupported = isSingleAppRecordingSupported()
        _uiState.update { it.copy(isSingleAppRecordingSupported = isSingleAppRecordingSupported) }

        if (!isSingleAppRecordingSupported) {
            updateFullScreenMode(true)
        }

        viewModelScope.launch {
            val hasCompletedOnboarding = preferenceManager.hasCompletedOnboardingFlow()
            val hasShownUnsupportedDialog = preferenceManager.hasShownUnsupportedDeviceDialog()
            val hasSeenSingleAppCaptureTip = preferenceManager.hasSeenSingleAppCaptureTip()
            _uiState.update {
                it.copy(
                    showOnboardingFlow = !hasCompletedOnboarding,
                    showUnsupportedDeviceDialog = !isSingleAppRecordingSupported && !hasShownUnsupportedDialog,
                    shouldShowSingleAppCaptureTipOnStart = isSingleAppRecordingSupported && !hasSeenSingleAppCaptureTip
                )
            }
        }

        viewModelScope.launch {
            preferenceManager.confidencePercentFlow.collectLatest { value ->
                _uiState.update { state -> state.copy(confidence = value) }
            }
        }

        viewModelScope.launch {
            preferenceManager.performanceModeFlow.collectLatest { enabled ->
                _uiState.update { state -> state.copy(performanceModeEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            preferenceManager.overlayOpacityFlow.collectLatest { value ->
                _uiState.update { state -> state.copy(overlayOpacity = value) }
            }
        }

        viewModelScope.launch {
            preferenceManager.fullScreenModeFlow.collectLatest { enabled ->
                _uiState.update { state -> state.copy(fullScreenModeEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            preferenceManager.autoStartAppsFlow.collectLatest { apps ->
                _uiState.update { state -> state.copy(autoStartApps = apps) }
            }
        }

        viewModelScope.launch {
            preferenceManager.pixelationLevelFlow.collectLatest { level ->
                _uiState.update { state -> state.copy(pixelationLevel = level) }
            }
        }

        viewModelScope.launch {
            preferenceManager.detailedModeFlow.collectLatest { enabled ->
                _uiState.update { state -> state.copy(detailedModeEnabled = enabled) }
            }
        }

        viewModelScope.launch {
            ScreenCaptureService.captureStateFlow.collectLatest { captureState ->
                _uiState.update { state -> state.copy(captureState = captureState) }
            }
        }
    }

    fun updateConfidence(value: Float) {
        _uiState.update { it.copy(confidence = value) }
        viewModelScope.launch {
            preferenceManager.saveConfidencePercent(value)
        }
    }

    fun updatePowerMode(enabled: Boolean) {
        _uiState.update { it.copy(performanceModeEnabled = enabled) }
        viewModelScope.launch {
            preferenceManager.setPowerMode(enabled)
        }
    }

    fun updateOverlayOpacity(value: Float) {
        _uiState.update { it.copy(overlayOpacity = value) }
        viewModelScope.launch {
            preferenceManager.setOverlayOpacity(value)
        }
    }

    fun updateFullScreenMode(enabled: Boolean) {
        _uiState.update { it.copy(fullScreenModeEnabled = enabled) }
        viewModelScope.launch {
            preferenceManager.setFullScreenMode(enabled)
        }
    }

    fun updateAccessibilityEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isAccessibilityEnabled = enabled) }
    }

    fun completeOnboardingFlow() {
        _uiState.update { it.copy(showOnboardingFlow = false) }
        viewModelScope.launch {
            preferenceManager.setHasCompletedOnboardingFlow(true)
        }
    }

    fun dismissUnsupportedDeviceDialog() {
        _uiState.update { it.copy(showUnsupportedDeviceDialog = false) }
        viewModelScope.launch {
            preferenceManager.setHasShownUnsupportedDeviceDialog(true)
        }
    }

    fun showSingleAppCaptureTipDialog() {
        _uiState.update { it.copy(showSingleAppCaptureTipDialog = true) }
    }

    fun acknowledgeSingleAppCaptureTip() {
        _uiState.update {
            it.copy(
                showSingleAppCaptureTipDialog = false,
                shouldShowSingleAppCaptureTipOnStart = false
            )
        }
        viewModelScope.launch {
            preferenceManager.setHasSeenSingleAppCaptureTip(true)
        }
    }

    fun dismissSingleAppCaptureTip() {
        _uiState.update {
            it.copy(
                showSingleAppCaptureTipDialog = false,
                shouldShowSingleAppCaptureTipOnStart = false
            )
        }
    }

    fun updateAutoStartApps(apps: Set<String>) {
        viewModelScope.launch {
            preferenceManager.setAutoStartApps(apps)
        }
        _uiState.update { it.copy(showAppSelectionDialog = false) }
    }

    fun toggleAppSelectionDialog(show: Boolean) {
        _uiState.update { it.copy(showAppSelectionDialog = show) }
    }

    fun updatePixelationLevel(value: Int) {
        _uiState.update { it.copy(pixelationLevel = value) }
        viewModelScope.launch {
            preferenceManager.setPixelationLevel(value)
        }
    }

    fun updateDetailedMode(enabled: Boolean) {
        _uiState.update { it.copy(detailedModeEnabled = enabled) }
        viewModelScope.launch {
            preferenceManager.setDetailedMode(enabled)
        }
    }

    private fun isSingleAppRecordingSupported(): Boolean {
        return Build.VERSION.SDK_INT >= 35 ||
                (Build.VERSION.SDK_INT == 34 && Build.MANUFACTURER.equals("Google", ignoreCase = true))
    }
}
