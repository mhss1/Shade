package com.mhss.app.shade.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import com.mhss.app.shade.InvisibleStartCaptureActivity
import com.mhss.app.shade.detection.OverlayView
import com.mhss.app.shade.util.PreferenceManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

@SuppressLint("AccessibilityPolicy")
class ShadeAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null
    private var frameLayout: FrameLayout? = null
    private val preferenceManager: PreferenceManager by inject()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Exception in coroutine: ${throwable.message}", throwable)
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob() + exceptionHandler)

    private val autoStartApps = preferenceManager
        .autoStartAppsFlow
        .flowOn(Dispatchers.IO)
        .stateIn(
            serviceScope,
            started = SharingStarted.Eagerly,
            initialValue = emptySet()
        )

    private var autoStartJob: Job? = null
    private var lastPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        initializeOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (packageName == "com.android.systemui") {
            overlayView?.clear()
            return
        }

        if (packageName == lastPackageName) return

        lastPackageName = packageName
        if (packageName !in autoStartApps.value) return

        if (ScreenCaptureService.isRunning) return
        if (OverlayManager.isServiceStartingInProgress) return

        autoStartJob?.cancel()
        autoStartJob = serviceScope.launch {
            delay(1000)

            val intent =
                Intent(
                    this@ShadeAccessibilityService,
                    InvisibleStartCaptureActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            startActivity(intent)
            OverlayManager.isServiceStartingInProgress = true
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service destroyed")
        OverlayManager.unregisterOverlayView()
        OverlayManager.onAccessibilityServiceDisconnected()
        destroyOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun initializeOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        frameLayout = FrameLayout(this)
        overlayView = OverlayView(this, null).also { view ->
            OverlayManager.registerOverlayView(view)
        }
        frameLayout?.addView(overlayView)


        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        try {
            windowManager?.addView(frameLayout, params)
            Log.d(TAG, "Accessibility overlay view added successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add accessibility overlay view: ${e.message}")
        }
    }

    private fun destroyOverlay() {
        try {
            overlayView?.clear()
            if (overlayView != null) frameLayout?.removeView(overlayView)
            if (frameLayout != null) windowManager?.removeView(frameLayout)
            frameLayout = null
            overlayView = null
            windowManager = null
        } catch (e: Exception) {
            Log.e(TAG, "Error removing accessibility overlay view: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ShadeAccessibilityService"
    }
}
