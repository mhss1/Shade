package com.mhss.app.shade.service

import android.graphics.Bitmap
import com.mhss.app.shade.detection.DetectionBox
import com.mhss.app.shade.detection.OverlayView
import com.mhss.app.shade.detection.RawSegmentation
import java.lang.ref.WeakReference

object OverlayManager {

    private var accessibilityOverlayView: WeakReference<OverlayView>? = null

    private var pendingOpacity: Float = 100f
    private var pixelationLevel: Int = 15

    @Volatile
    var isServiceStartingInProgress = false

    fun registerOverlayView(overlayView: OverlayView) {
        accessibilityOverlayView = WeakReference(overlayView)
        overlayView.setOpacity(pendingOpacity)
        overlayView.setPixelationLevel(pixelationLevel)
    }

    fun unregisterOverlayView() {
        accessibilityOverlayView = null
    }

    fun onAccessibilityServiceDisconnected() {
        accessibilityOverlayView = null
    }

    fun updateDetections(boxes: List<DetectionBox>, sourceBitmap: Bitmap) {
        accessibilityOverlayView?.get()?.updateDetections(boxes, sourceBitmap)
    }

    fun updateSegmentations(segmentations: List<RawSegmentation>, sourceBitmap: Bitmap) {
        accessibilityOverlayView?.get()?.updateSegmentations(segmentations, sourceBitmap)
    }

    fun clear() {
        accessibilityOverlayView?.get()?.clear()
    }

    fun setOpacity(opacityPercent: Float) {
        pendingOpacity = opacityPercent.coerceIn(0f, 100f)
        accessibilityOverlayView?.get()?.setOpacity(pendingOpacity)
    }

    fun setPixelationLevel(level: Int) {
        pixelationLevel = level
        accessibilityOverlayView?.get()?.setPixelationLevel(level)
    }
}
