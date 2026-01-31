package com.mhss.app.shade.detection

import android.graphics.Bitmap
import android.graphics.RectF

/**
 * Holds a pre-pixelated bitmap along with its destination bounds for efficient drawing.
 * usedWidth/usedHeight track actual content size within the (potentially larger) pooled bitmap.
 */
class PixelatedRegion(
    @JvmField val bitmap: Bitmap,
    @JvmField val bounds: RectF,
    @JvmField val sourceBox: DetectionBox,
    @JvmField val contentWidth: Int,
    @JvmField val contentHeight: Int
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PixelatedRegion) return false
        return sourceBox == other.sourceBox
    }

    override fun hashCode(): Int = sourceBox.hashCode()
}
