package com.mhss.app.shade.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import androidx.core.graphics.createBitmap
import java.util.ArrayList
import kotlin.math.abs


/**
 * Overlay view that draws pixelated regions over detected content.
 * Handles pixelation computation, bitmap pooling, and caching.
 */
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var currentRegions: List<PixelatedRegion> = emptyList()
    private var cachedRegions = ArrayList<PixelatedRegion>()

    private var downsampleFactor: Int = DEFAULT_DOWNSAMPLE_FACTOR

    // Reusable lists to avoid allocations in updateDetections (swapped each frame)
    private var tempNewRegions = ArrayList<PixelatedRegion>()
    private val tempReusedFromCache = ArrayList<PixelatedRegion>()

    // Reusable Rect for draw()
    private val drawSrcRect = Rect()

    // Reusable Rects for createPixelatedRegion()
    private val pixelateSrcRect = Rect()
    private val pixelateDstRect = Rect()

    private val bitmapPool = ArrayDeque<Bitmap>(MAX_BITMAP_POOL_SIZE)

    private val paint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }
    private var opacity: Int = 255

    fun setOpacity(opacityPercent: Float) {
        val percent = opacityPercent.coerceIn(0f, 100f)
        opacity = ((percent / 100f) * 255).toInt()
        paint.alpha = opacity
        invalidate()
    }

    fun setPixelationLevel(level: Int) {
        downsampleFactor = level.coerceIn(MIN_DOWNSAMPLE_FACTOR, MAX_DOWNSAMPLE_FACTOR)
        cachedRegions.forEach { returnBitmapToPool(it.bitmap) }
        cachedRegions.clear()
        invalidate()
    }

    fun clear() {
        if (currentRegions.isEmpty()) return

        currentRegions.forEach { returnBitmapToPool(it.bitmap) }
        currentRegions = emptyList()

        cachedRegions.forEach { returnBitmapToPool(it.bitmap) }
        cachedRegions.clear()

        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        currentRegions.forEach { region ->
            if (!region.bitmap.isRecycled) {
                // Only draw the used portion of the potentially larger bitmap
                drawSrcRect.set(0, 0, region.contentWidth, region.contentHeight)
                canvas.drawBitmap(region.bitmap, drawSrcRect, region.bounds, paint)
            }
        }
    }

    fun updateDetections(boxes: List<DetectionBox>, sourceBitmap: Bitmap) {
        tempNewRegions.clear()
        tempReusedFromCache.clear()

        val sourceWidth = sourceBitmap.width
        val sourceHeight = sourceBitmap.height
        val viewWidth = width
        val viewHeight = height

        for (box in boxes) {
            // Check if we can reuse a cached region (box hasn't moved much).
            // Can this reuse a region even if it's content has completely changed since the last frame?
            // Yes, but I don't care. CPU happiness > UX :)
            val cachedRegion = cachedRegions.find { region ->
                region isSimilarTo box && region !in tempReusedFromCache
            }

            if (cachedRegion != null) {
                Log.d(TAG, "CACHE HIT: Reusing cached pixelated region")
                tempNewRegions.add(cachedRegion)
                tempReusedFromCache.add(cachedRegion)
            } else {
                Log.d(TAG, "CACHE MISS: Creating new pixelated region")
                val pixelatedRegion = createPixelatedRegion(
                    box, sourceBitmap, sourceWidth, sourceHeight, viewWidth, viewHeight
                )
                if (pixelatedRegion != null) tempNewRegions.add(pixelatedRegion)
            }
        }

        // Return old bitmaps to pool for reuse
        cachedRegions.forEach { old ->
            if (old !in tempReusedFromCache) {
                returnBitmapToPool(old.bitmap)
            }
        }

        currentRegions = tempNewRegions

        // Swap lists to avoid clear+addAll
        val temp = cachedRegions
        cachedRegions = tempNewRegions
        tempNewRegions = temp

        invalidate()
    }

    private fun createPixelatedRegion(
        box: DetectionBox,
        source: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int
    ): PixelatedRegion? {
        try {
            val srcLeft = (box.x1 * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
            val srcTop = (box.y1 * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
            val srcRight = (box.x2 * sourceWidth).toInt().coerceIn(srcLeft + 1, sourceWidth)
            val srcBottom = (box.y2 * sourceHeight).toInt().coerceIn(srcTop + 1, sourceHeight)

            val regionWidth = srcRight - srcLeft
            val regionHeight = srcBottom - srcTop

            if (regionWidth <= 0 || regionHeight <= 0) return null

            val contentWidth = (regionWidth / downsampleFactor).coerceAtLeast(1)
            val contentHeight = (regionHeight / downsampleFactor).coerceAtLeast(1)

            // Rounding to power of 2 to get better hit rate
            val bitmapWidth = contentWidth.nextPowerOf2()
            val bitmapHeight = contentHeight.nextPowerOf2()

            val bitmap =
                getBitmapFromPool(bitmapWidth, bitmapHeight) ?: createBitmap(bitmapWidth, bitmapHeight)

            pixelateSrcRect.set(srcLeft, srcTop, srcRight, srcBottom)
            pixelateDstRect.set(0, 0, contentWidth, contentHeight)

            // Draw source region into the bitmap. Automatically downsamples, creating pixelation
            Canvas(bitmap).drawBitmap(source, pixelateSrcRect, pixelateDstRect, null)

            return PixelatedRegion(
                bitmap = bitmap,
                bounds = RectF(
                    box.x1 * viewWidth,
                    box.y1 * viewHeight,
                    box.x2 * viewWidth,
                    box.y2 * viewHeight
                ),
                sourceBox = box,
                contentWidth = contentWidth,
                contentHeight = contentHeight
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun getBitmapFromPool(width: Int, height: Int): Bitmap? {
        for (i in bitmapPool.indices) {
            val bitmap = bitmapPool[i]
            if (!bitmap.isRecycled && bitmap.isMutable &&
                bitmap.width == width && bitmap.height == height
            ) {
                bitmapPool.removeAt(i)
                Log.d(TAG, "POOL HIT: Reusing bitmap ${width}x${height}")
                return bitmap
            }
        }
        Log.d(TAG, "POOL MISS: didn't find ${width}x${height} bitmap")
        return null
    }

    private fun returnBitmapToPool(bitmap: Bitmap) {
        if (bitmap.isRecycled || !bitmap.isMutable) return

        if (bitmapPool.size >= MAX_BITMAP_POOL_SIZE) {
            val oldest = bitmapPool.removeFirst()
            if (!oldest.isRecycled) oldest.recycle()
        }
        bitmapPool.addLast(bitmap)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clear()
        bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
        bitmapPool.clear()
    }

    private fun Int.nextPowerOf2(): Int {
        if (this <= MIN_BITMAP_DIMENSION) return MIN_BITMAP_DIMENSION
        return 1 shl (32 - Integer.numberOfLeadingZeros(this - 1))
    }

    inline infix fun PixelatedRegion.isSimilarTo(other: DetectionBox): Boolean {
        return abs(sourceBox.x1 - other.x1) < BOX_SIMILARITY_THRESHOLD &&
                abs(sourceBox.y1 - other.y1) < BOX_SIMILARITY_THRESHOLD &&
                abs(sourceBox.x2 - other.x2) < BOX_SIMILARITY_THRESHOLD &&
                abs(sourceBox.y2 - other.y2) < BOX_SIMILARITY_THRESHOLD
    }
}

private const val TAG = "OverlayView"
const val DEFAULT_DOWNSAMPLE_FACTOR = 15
const val MIN_DOWNSAMPLE_FACTOR = 5
const val MAX_DOWNSAMPLE_FACTOR = 30
private const val MAX_BITMAP_POOL_SIZE = 10
private const val MIN_BITMAP_DIMENSION = 8
const val BOX_SIMILARITY_THRESHOLD = 0.025f