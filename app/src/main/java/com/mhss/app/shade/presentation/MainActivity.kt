package com.mhss.app.shade.presentation

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mhss.app.shade.R
import com.mhss.app.shade.presentation.components.AutoStartAppsCard
import com.mhss.app.shade.presentation.components.CaptureStatusCard
import com.mhss.app.shade.presentation.components.DetectionConfidenceCard
import com.mhss.app.shade.presentation.components.GettingStartedDialog
import com.mhss.app.shade.presentation.components.GitHubFooter
import com.mhss.app.shade.presentation.components.OverlayOpacityCard
import com.mhss.app.shade.presentation.components.PixelationLevelCard
import com.mhss.app.shade.presentation.components.SettingsSectionHeader
import com.mhss.app.shade.presentation.components.SettingsToggleCard
import com.mhss.app.shade.presentation.components.UnsupportedBanner
import com.mhss.app.shade.presentation.components.UnsupportedDialog
import com.mhss.app.shade.service.ShadeAccessibilityService
import com.mhss.app.shade.presentation.theme.ShadeTheme
import com.mhss.app.shade.util.ScreenCaptureManager
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModel()

    private lateinit var screenCaptureManager: ScreenCaptureManager

    private var permissionState by mutableStateOf(PermissionState())

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        updatePermissionStates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        screenCaptureManager = ScreenCaptureManager(
            activity = this,
            onCaptureStarted = { moveToBackground() },
            onCapturePermissionDenied = {
                Toast.makeText(
                    this,
                    R.string.screen_capture_permission_denied,
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        updatePermissionStates()

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            ShadeTheme {
                if (!permissionState.allGranted) {
                    PermissionSetupScreen(
                        permissionState = permissionState,
                        onAccessibilityClick = { openAccessibilitySettings() },
                        onNotificationClick = { requestNotificationPermission() }
                    )
                } else {
                    MainScreen(
                        uiState = uiState,
                        onStartCapture = { startScreenCapture() },
                        onStopCapture = { stopScreenCapture() },
                        onConfidenceChanged = { value -> viewModel.updateConfidence(value) },
                        onPerformanceModeChanged = { enabled -> viewModel.updatePowerMode(enabled) },
                        onOverlayOpacityChanged = { value -> viewModel.updateOverlayOpacity(value) },
                        onPixelationLevelChanged = { value -> viewModel.updatePixelationLevel(value) },
                        onFullScreenModeChanged = { enabled ->
                            viewModel.updateFullScreenMode(
                                enabled
                            )
                        },
                        onAutoStartAppsClick = { viewModel.toggleAppSelectionDialog(true) },
                        onAutoStartAppsChanged = { apps -> viewModel.updateAutoStartApps(apps) },
                        onDismissAppSelectionDialog = { viewModel.toggleAppSelectionDialog(false) },
                        onDismissInitialDialog = { viewModel.dismissInitialDialog() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        permissionState = PermissionState(
            accessibilityGranted = isAccessibilityServiceEnabled(),
            notificationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
        viewModel.updateAccessibilityEnabled(permissionState.accessibilityGranted)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices =
            accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val serviceName = ComponentName(this, ShadeAccessibilityService::class.java)
        return enabledServices.any {
            ComponentName(
                it.resolveInfo.serviceInfo.packageName,
                it.resolveInfo.serviceInfo.name
            ) == serviceName
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun moveToBackground() {
        moveTaskToBack(true)
    }

    private fun startScreenCapture() {
        screenCaptureManager.requestScreenCapturePermission()
    }

    private fun stopScreenCapture() {
        screenCaptureManager.stopScreenCapture()
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onConfidenceChanged: (Float) -> Unit,
    onPerformanceModeChanged: (Boolean) -> Unit,
    onOverlayOpacityChanged: (Float) -> Unit,
    onPixelationLevelChanged: (Int) -> Unit,
    onFullScreenModeChanged: (Boolean) -> Unit,
    onAutoStartAppsClick: () -> Unit,
    onAutoStartAppsChanged: (Set<String>) -> Unit,
    onDismissAppSelectionDialog: () -> Unit,
    onDismissInitialDialog: () -> Unit
) {
    if (uiState.showInitialDialog) {
        if (uiState.isSingleAppRecordingSupported) {
            GettingStartedDialog(onDismiss = onDismissInitialDialog)
        } else {
            UnsupportedDialog(onDismiss = onDismissInitialDialog)
        }
    }

    if (uiState.showAppSelectionDialog) {
        AppSelectionDialog(
            selectedApps = uiState.autoStartApps,
            onDismiss = onDismissAppSelectionDialog,
            onConfirm = onAutoStartAppsChanged
        )
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!uiState.isSingleAppRecordingSupported) {
                UnsupportedBanner()
                Spacer(modifier = Modifier.height(16.dp))
            }

            CaptureStatusCard(
                isCapturing = uiState.isCapturing,
                onStartCapture = onStartCapture,
                onStopCapture = onStopCapture
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSectionHeader(stringResource(R.string.settings_section_detection))

            DetectionConfidenceCard(
                confidence = uiState.confidence,
                onConfidenceChanged = onConfidenceChanged
            )

            OverlayOpacityCard(
                opacity = uiState.overlayOpacity,
                onOpacityChanged = onOverlayOpacityChanged
            )

            PixelationLevelCard(
                pixelationLevel = uiState.pixelationLevel,
                onPixelationLevelChanged = onPixelationLevelChanged
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingsSectionHeader(stringResource(R.string.settings_section_advanced))

            SettingsToggleCard(
                icon = Icons.Outlined.Speed,
                title = stringResource(R.string.power_mode),
                description = stringResource(R.string.power_mode_description),
                checked = uiState.performanceModeEnabled,
                onCheckedChange = onPerformanceModeChanged
            )

            SettingsToggleCard(
                icon = Icons.Outlined.Fullscreen,
                title = stringResource(R.string.full_screen_mode),
                description = stringResource(R.string.full_screen_mode_description),
                checked = uiState.fullScreenModeEnabled,
                onCheckedChange = onFullScreenModeChanged,
                enabled = uiState.isSingleAppRecordingSupported
            )

            AutoStartAppsCard(
                selectedCount = uiState.autoStartApps.size,
                onClick = onAutoStartAppsClick
            )

            Spacer(modifier = Modifier.height(12.dp))

            GitHubFooter()

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}