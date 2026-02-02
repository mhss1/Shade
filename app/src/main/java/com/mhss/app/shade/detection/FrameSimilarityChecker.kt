package com.mhss.app.shade.detection

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * Efficient frame comparison to detect if the scene changed outside detection boxes.
 *
 * Used in full-screen capture mode to decide whether to keep the overlay when
 * the detector returns empty (because it sees the pixelated overlay instead of the target).
 *
 * Approach: Sample pixels on a grid across the frame, skip pixels inside detection boxes,
 * and compare against the previous frame.
 */
class FrameSimilarityChecker {

    private var previousFrame: Bitmap? = null
    private var previousBoxes: List<DetectionBox> = emptyList()

    private var prevRowBuffer: IntArray? = null
    private var currRowBuffer: IntArray? = null

    @Synchronized
    fun onDetectionSuccess(frame: Bitmap, boxes: List<DetectionBox>): List<DetectionBox> {
        val mergedBoxes = (previousBoxes + boxes).let {
            if (it.size > 5) it.takeLast(5) else it
        }

        previousFrame?.recycle()
        previousFrame = frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
        previousBoxes = mergedBoxes

        return mergedBoxes
    }

    /**
     * Check if the scene outside detection boxes is similar to previous frame.
     * @return true to keep overlay, false to clear
     */
    @Synchronized
    fun shouldKeepOverlay(currentFrame: Bitmap): Boolean {
        val prevFrame = previousFrame
        if (prevFrame == null || previousBoxes.isEmpty()) return false

        val boxes = previousBoxes

        val w = prevFrame.width
        val h = prevFrame.height

        if (w != currentFrame.width || h != currentFrame.height) return false

        val stepX = (w / GRID_SIZE).coerceAtLeast(1)
        val stepY = (h / GRID_SIZE).coerceAtLeast(1)

        var matches = 0
        var samplesOutsideBoxes = 0
        var samplesInsideBoxes = 0

        if (prevRowBuffer?.size != w) {
            prevRowBuffer = IntArray(w)
            currRowBuffer = IntArray(w)
        }
        val prevRowPixels = prevRowBuffer!!
        val currRowPixels = currRowBuffer!!

        val maxMismatches = ((1 - SIMILARITY_THRESHOLD) * GRID_SIZE * GRID_SIZE).toInt()

        var y = stepY / 2
        while (y < h) {
            prevFrame.getPixels(prevRowPixels, 0, w, 0, y, w, 1)
            currentFrame.getPixels(currRowPixels, 0, w, 0, y, w, 1)

            var x = stepX / 2
            while (x < w) {
                if (boxes.contains(x, y, w, h)) {
                    samplesInsideBoxes++
                } else {
                    samplesOutsideBoxes++
                    if (prevRowPixels[x] isSimilarTo currRowPixels[x]) matches++
                }
                x += stepX
            }
            // return early if too many mismatches
            if (samplesOutsideBoxes - matches > maxMismatches) break
            y += stepY
        }

        val totalSamples = samplesInsideBoxes + samplesOutsideBoxes
        val coverage = if (totalSamples > 0) samplesInsideBoxes.toFloat() / totalSamples else 0f

        if (coverage > MAX_BOX_COVERAGE) return false

        if (samplesOutsideBoxes < MIN_SAMPLES) return false

        val similarity = matches.toFloat() / samplesOutsideBoxes
        if (similarity < SIMILARITY_THRESHOLD) return false

        return true
    }

    private fun List<DetectionBox>.contains(x: Int, y: Int, w: Int, h: Int): Boolean {
        val normalizedX = x.toFloat() / w
        val normalizedY = y.toFloat() / h

        for (box in this) {
            if (
                normalizedX >= (box.x1 - BOX_MARGIN) && normalizedX <= (box.x2 + BOX_MARGIN) &&
                normalizedY >= (box.y1 - BOX_MARGIN) && normalizedY <= (box.y2 + BOX_MARGIN)
            ) {
                return true
            }
        }
        return false
    }

    private inline infix fun Pixel.isSimilarTo(p2: Pixel): Boolean {
        val p1 = this

        // Fast path
        // If upper 4 bits of each RGB channel match, max diff per channel is 15, total max 45 which is below our threshold
        if ((p1 xor p2) and 0x00F0F0F0 == 0) return true

        val r1 = (p1 shr 16) and 0xFF
        val g1 = (p1 shr 8) and 0xFF
        val b1 = p1 and 0xFF

        val r2 = (p2 shr 16) and 0xFF
        val g2 = (p2 shr 8) and 0xFF
        val b2 = p2 and 0xFF

        val dist = abs(r1 - r2) + abs(g1 - g2) + abs(b1 - b2)
        return dist <= PIXEL_THRESHOLD
    }

    @Synchronized
    fun clear() {
        previousFrame?.recycle()
        previousFrame = null
        previousBoxes = emptyList()
        prevRowBuffer = null
        currRowBuffer = null
    }

    companion object {

        // Grid size for sampling
        private const val GRID_SIZE = 36

        // Percentage of compared pixels that must match
        private const val SIMILARITY_THRESHOLD = 0.6f

        // Max color distance (R+G+B) for pixels to be "similar"
        private const val PIXEL_THRESHOLD = 75

        // Margin around boxes to exclude
        private const val BOX_MARGIN = 0.03f

        // Minimum samples needed (if too many are inside boxes) - 10% of grid
        private const val MIN_SAMPLES = (GRID_SIZE * GRID_SIZE) / 10

        // If boxes cover more than this, don't try to compare
        private const val MAX_BOX_COVERAGE = 0.70f
    }
}

typealias Pixel = Int