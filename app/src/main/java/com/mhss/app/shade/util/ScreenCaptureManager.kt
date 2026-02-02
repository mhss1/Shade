package com.mhss.app.shade.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.mhss.app.shade.service.ScreenCaptureService

class ScreenCaptureManager(
    private val activity: ComponentActivity,
    private val onCaptureStarted: (() -> Unit),
    private val onCapturePermissionDenied: (() -> Unit)
) {

    private var mediaProjectionManager: MediaProjectionManager = 
        activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    
    private var permissionLauncher: ActivityResultLauncher<Intent> = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startScreenCaptureService(result.resultCode, result.data!!)
        } else {
            onCapturePermissionDenied.invoke()
        }
    }

    fun requestScreenCapturePermission() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        permissionLauncher.launch(captureIntent)
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(activity, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        activity.startForegroundService(serviceIntent)
        onCaptureStarted.invoke()
    }

    fun stopScreenCapture() {
        val serviceIntent = Intent(activity, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        activity.startService(serviceIntent)

    }
}