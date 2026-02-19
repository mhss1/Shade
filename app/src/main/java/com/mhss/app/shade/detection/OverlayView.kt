package com.mhss.app.shade.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.Display
import android.view.Surface
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

    private var tempNewRegions = ArrayList<PixelatedRegion>()
    private val tempReusedFromCache = ArrayList<PixelatedRegion>()

    private val drawSrcRect = Rect()

    private val pixelateSrcRect = Rect()
    private val pixelateDstRect = Rect()
    private val pixelateCanvas = Canvas()

    private val bitmapPool = ArrayDeque<Bitmap>(MAX_BITMAP_POOL_SIZE)

    private var currentSegRegions = ArrayList<PixelatedRegion>()
    private var tempSegNewRegions = ArrayList<PixelatedRegion>()

    private var segSourcePixels = IntArray(0)
    private var segMaskPixels = IntArray(0)

    private var screenWidthPx = 0f
    private var screenHeightPx = 0f
    private var viewOffsetX = 0f
    private var viewOffsetY = 0f

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
        val hadRegions = currentRegions.isNotEmpty()
        val hadSegmentations = currentSegRegions.isNotEmpty()

        if (!hadRegions && !hadSegmentations) return

        currentRegions.forEach { returnBitmapToPool(it.bitmap) }
        currentRegions = emptyList()

        cachedRegions.forEach { returnBitmapToPool(it.bitmap) }
        cachedRegions.clear()

        currentSegRegions.forEach { returnBitmapToPool(it.bitmap) }
        currentSegRegions.clear()
        tempSegNewRegions.clear()

        invalidate()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        currentRegions.forEach { region ->
            if (!region.bitmap.isRecycled) {
                drawSrcRect.set(0, 0, region.contentWidth, region.contentHeight)
                canvas.drawBitmap(region.bitmap, drawSrcRect, region.bounds, paint)
            }
        }

        if (currentSegRegions.isNotEmpty()) {
            currentSegRegions.forEach { region ->
                if (!region.bitmap.isRecycled) {
                    drawSrcRect.set(0, 0, region.contentWidth, region.contentHeight)
                    canvas.drawBitmap(region.bitmap, drawSrcRect, region.bounds, paint)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateScreenParams()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (changed) updateScreenParams()
    }

    private fun updateScreenParams() {
        val display: Display? = display
        val rotation = display?.rotation ?: Surface.ROTATION_0
        var displayW = display?.mode?.physicalWidth ?: width
        var displayH = display?.mode?.physicalHeight ?: height
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            val temp = displayW
            displayW = displayH
            displayH = temp
        }
        val location = IntArray(2)
        getLocationOnScreen(location)
        viewOffsetX = location[0].toFloat()
        viewOffsetY = location[1].toFloat()
        screenWidthPx = displayW.toFloat()
        screenHeightPx = displayH.toFloat()
    }

    fun updateDetections(boxes: List<DetectionBox>, sourceBitmap: Bitmap) {
        if (currentSegRegions.isNotEmpty()) {
            currentSegRegions.forEach { returnBitmapToPool(it.bitmap) }
            currentSegRegions.clear()
        }

        tempNewRegions.clear()
        tempReusedFromCache.clear()

        val sourceWidth = sourceBitmap.width
        val sourceHeight = sourceBitmap.height

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
                val pixelatedRegion =
                    createPixelatedRegion(box, sourceBitmap, sourceWidth, sourceHeight)
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

    fun updateSegmentations(segmentations: List<RawSegmentation>, sourceBitmap: Bitmap) {
        if (cachedRegions.isNotEmpty()) {
            cachedRegions.forEach { returnBitmapToPool(it.bitmap) }
            cachedRegions.clear()
            currentRegions = emptyList()
            tempNewRegions.clear()
            tempReusedFromCache.clear()
        }

        tempSegNewRegions.clear()

        val sourceWidth = sourceBitmap.width
        val sourceHeight = sourceBitmap.height

        for (seg in segmentations) {
            val region = createSegmentedRegion(seg, sourceBitmap, sourceWidth, sourceHeight)
            if (region != null) tempSegNewRegions.add(region)
        }

        currentSegRegions.forEach { returnBitmapToPool(it.bitmap) }

        val temp = currentSegRegions
        currentSegRegions = tempSegNewRegions
        tempSegNewRegions = temp

        invalidate()
    }

    private fun createSegmentedRegion(
        seg: RawSegmentation,
        source: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int
    ): PixelatedRegion? {
        try {
            val box = seg.box
            val srcLeft = (box.x1 * sourceWidth).toInt().coerceIn(0, sourceWidth - 1)
            val srcTop = (box.y1 * sourceHeight).toInt().coerceIn(0, sourceHeight - 1)
            val srcRight = (box.x2 * sourceWidth).toInt().coerceIn(srcLeft + 1, sourceWidth)
            val srcBottom = (box.y2 * sourceHeight).toInt().coerceIn(srcTop + 1, sourceHeight)

            val regionWidth = srcRight - srcLeft
            val regionHeight = srcBottom - srcTop

            if (regionWidth <= 0 || regionHeight <= 0) return null

            val contentWidth = (regionWidth / downsampleFactor).coerceAtLeast(1)
            val contentHeight = (regionHeight / downsampleFactor).coerceAtLeast(1)

            val bitmapWidth = contentWidth.nextPowerOf2()
            val bitmapHeight = contentHeight.nextPowerOf2()

            val bitmap =
                getBitmapFromPool(bitmapWidth, bitmapHeight) ?: createBitmap(
                    bitmapWidth,
                    bitmapHeight
                )

            val totalSourcePixels = regionWidth * regionHeight
            if (segSourcePixels.size < totalSourcePixels) {
                segSourcePixels = IntArray(totalSourcePixels)
            }
            source.getPixels(
                segSourcePixels,
                0,
                regionWidth,
                srcLeft,
                srcTop,
                regionWidth,
                regionHeight
            )

            val totalMaskPixels = bitmapWidth * bitmapHeight
            if (segMaskPixels.size < totalMaskPixels) {
                segMaskPixels = IntArray(totalMaskPixels)
            }

            val mask = seg.mask
            val maskW = seg.maskWidth
            val maskH = seg.maskHeight

            for (y in 0 until contentHeight) {
                for (x in 0 until contentWidth) {
                    val srcX = (x * downsampleFactor).coerceIn(0, regionWidth - 1)
                    val srcY = (y * downsampleFactor).coerceIn(0, regionHeight - 1)
                    val sourcePixel = segSourcePixels[srcY * regionWidth + srcX]

                    val maskX = (x * maskW / contentWidth).coerceIn(0, maskW - 1)
                    val maskY = (y * maskH / contentHeight).coerceIn(0, maskH - 1)

                    val pixelIndex = y * bitmapWidth + x
                    if (mask[maskY * maskW + maskX]) {
                        segMaskPixels[pixelIndex] = sourcePixel or 0xFF000000.toInt()
                    } else {
                        segMaskPixels[pixelIndex] = 0x00000000
                    }
                }
            }

            bitmap.setPixels(segMaskPixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val displayW = if (screenWidthPx > 0f) screenWidthPx else viewWidth
            val displayH = if (screenHeightPx > 0f) screenHeightPx else viewHeight
            val offsetX = viewOffsetX
            val offsetY = viewOffsetY

            return PixelatedRegion(
                bitmap = bitmap,
                bounds = RectF(
                    box.x1 * displayW - offsetX,
                    box.y1 * displayH - offsetY,
                    box.x2 * displayW - offsetX,
                    box.y2 * displayH - offsetY
                ),
                sourceBox = box,
                contentWidth = contentWidth,
                contentHeight = contentHeight
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun createPixelatedRegion(
        box: DetectionBox,
        source: Bitmap,
        sourceWidth: Int,
        sourceHeight: Int
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
                getBitmapFromPool(bitmapWidth, bitmapHeight) ?: createBitmap(
                    bitmapWidth,
                    bitmapHeight
                )

            pixelateSrcRect.set(srcLeft, srcTop, srcRight, srcBottom)
            pixelateDstRect.set(0, 0, contentWidth, contentHeight)

            // Draw source region into the bitmap. Automatically downsamples, creating pixelation.
            pixelateCanvas.setBitmap(bitmap)
            pixelateCanvas.drawBitmap(source, pixelateSrcRect, pixelateDstRect, null)

            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val displayW = if (screenWidthPx > 0f) screenWidthPx else viewWidth
            val displayH = if (screenHeightPx > 0f) screenHeightPx else viewHeight
            val offsetX = viewOffsetX
            val offsetY = viewOffsetY

            return PixelatedRegion(
                bitmap = bitmap,
                bounds = RectF(
                    box.x1 * displayW - offsetX,
                    box.y1 * displayH - offsetY,
                    box.x2 * displayW - offsetX,
                    box.y2 * displayH - offsetY
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
        pixelateCanvas.setBitmap(null)
        bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
        bitmapPool.clear()
    }


    private inline fun Int.nextPowerOf2(): Int {
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
const val BOX_SIMILARITY_THRESHOLD = 0.015f
