package com.mhss.app.shade

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mhss.app.shade.service.OverlayManager
import com.mhss.app.shade.service.ScreenCaptureService
import com.mhss.app.shade.service.ShadeAccessibilityService

class InvisibleStartCaptureActivity : ComponentActivity() {

    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startScreenCaptureService(result.resultCode, result.data!!)
        } else {
            OverlayManager.isServiceStartingInProgress = false
            Toast.makeText(
                this,
                R.string.screen_capture_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
        finish()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestScreenCapturePermission()
        } else {
            Toast.makeText(
                this,
                R.string.notification_permission_required,
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                R.string.accessibility_service_required,
                Toast.LENGTH_LONG
            ).show()
            OverlayManager.isServiceStartingInProgress = false
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                OverlayManager.isServiceStartingInProgress = false
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestScreenCapturePermission()
            }
        } else {
            requestScreenCapturePermission()
        }
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCapturePermissionLauncher.launch(captureIntent)
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        startForegroundService(serviceIntent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val serviceName = ComponentName(this, ShadeAccessibilityService::class.java)
        return enabledServices.any {
            ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == serviceName
        }
    }
}
