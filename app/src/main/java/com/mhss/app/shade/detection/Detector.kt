package com.mhss.app.shade.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import androidx.core.graphics.createBitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val onEmptyDetections: (sourceBitmap: Bitmap) -> Unit,
    private val onBoxesDetected: (boundingBoxes: List<DetectionBox>, sourceBitmap: Bitmap) -> Unit
) {

    private val isReady = AtomicBoolean(false)

    private var interpreter: Interpreter? = null
    private var delegate: Delegate? = null

    var tensorWidth = 0
    var tensorHeight = 0
    private var numChannel = 0
    private var numPredictions = 0

    private lateinit var outputBuffer: TensorBuffer
    private lateinit var outputFloatBuffer: FloatBuffer
    private lateinit var outputArray: FloatArray

    private var confidenceThreshold = DEFAULT_CONF_THRESHOLD

    private var inputBuffer: ByteBuffer? = null
    private var inputFloatBuffer: FloatBuffer? = null
    private var inputFloatArray: FloatArray? = null
    private var inputPixels: IntArray? = null
    private var resizedBitmap: Bitmap? = null
    private var resizeCanvas: Canvas? = null
    private var resizeRect: Rect? = null

    fun setup(threshold: Float = DEFAULT_CONF_THRESHOLD) {
        isReady.set(false)
        confidenceThreshold = threshold
        val model = FileUtil.loadMappedFile(context, modelPath)
        val options = Interpreter.Options()
        options.numThreads = 4

        val createdDelegate = tryCreateGpuDelegate()

        if (createdDelegate != null) {
            options.addDelegate(createdDelegate)
            delegate = createdDelegate
        } else {
            options.setUseXNNPACK(true)
            Log.d(TAG, "Using CPU with XNNPACK")
        }

        interpreter = Interpreter(model, options)

        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: return
        val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: return

        tensorWidth = inputShape[1]
        tensorHeight = inputShape[2]

        numChannel = outputShape[1]
        numPredictions = outputShape[2]

        val pixelCount = tensorWidth * tensorHeight
        val buffer = ByteBuffer.allocateDirect(pixelCount * INPUT_CHANNELS * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        inputBuffer = buffer
        inputFloatBuffer = buffer.asFloatBuffer()
        inputFloatArray = FloatArray(pixelCount * INPUT_CHANNELS)
        inputPixels = IntArray(pixelCount)
        resizedBitmap = createBitmap(tensorWidth, tensorHeight)
        resizeCanvas = Canvas(resizedBitmap!!)
        resizeRect = Rect(0, 0, tensorWidth, tensorHeight)

        outputBuffer =
            TensorBuffer.createFixedSize(outputShape, IMAGE_TYPE)
        outputFloatBuffer = outputBuffer.buffer.asFloatBuffer()

        outputArray = FloatArray(numChannel * numPredictions)

        isReady.set(true)
    }

    private fun tryCreateGpuDelegate(): GpuDelegate? {
        return try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val options = compatList.bestOptionsForThisDevice
                val cacheDir = File(context.cacheDir, "gpu_delegate_cache")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                val modelToken = modelPath.substringBeforeLast('.').replace('/', '_')
                options.setSerializationParams(cacheDir.absolutePath, modelToken)

                val delegate = GpuDelegate(options)
                Log.d(TAG, "Using GPU delegate")
                delegate
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "GPU not available: ${e.message}")
            null
        }
    }

    fun clear() {
        isReady.set(false)
        interpreter?.close()
        interpreter = null
        delegate?.close()
        delegate = null
        resizedBitmap?.recycle()
        resizedBitmap = null
        resizeCanvas = null
        resizeRect = null
        inputPixels = null
        inputFloatArray = null
        inputFloatBuffer = null
        inputBuffer = null
    }

    fun detect(frameBitmap: Bitmap) {
        if (!isReady.get()) return
        val inBuffer = inputBuffer ?: return
        val floatBuffer = inputFloatBuffer ?: return
        val inputArray = inputFloatArray ?: return
        val pixels = inputPixels ?: return
        val targetBitmap = resizedBitmap ?: return
        val canvas = resizeCanvas ?: return
        val destRect = resizeRect ?: return
        val currentInterpreter = interpreter ?: return

        canvas.drawBitmap(frameBitmap, null, destRect, null)
        targetBitmap.getPixels(pixels, 0, tensorWidth, 0, 0, tensorWidth, tensorHeight)
        fillInputArray(pixels, inputArray)
        floatBuffer.rewind()
        floatBuffer.put(inputArray)
        inBuffer.rewind()

        currentInterpreter.run(inBuffer, outputBuffer.buffer)

        val boxes = extractBoxes()

        if (boxes == null) {
            onEmptyDetections(frameBitmap)
            return
        }

        onBoxesDetected(boxes, frameBitmap)
    }

    fun updateThreshold(threshold: Float) {
        confidenceThreshold = threshold
    }

    private fun fillInputArray(pixels: IntArray, output: FloatArray) {
        var pixelIndex = 0
        var outputIndex = 0
        val invStd = 1f / INPUT_STANDARD_DEVIATION
        val totalPixels = tensorWidth * tensorHeight

        while (pixelIndex < totalPixels) {
            val pixel = pixels[pixelIndex]
            output[outputIndex] = ((pixel shr 16) and 0xFF) * invStd
            output[outputIndex + 1] = ((pixel shr 8) and 0xFF) * invStd
            output[outputIndex + 2] = (pixel and 0xFF) * invStd
            pixelIndex++
            outputIndex += 3
        }
    }

    /**
     * Extract boxes from raw YOLO output (no NMS)
     * Output shape: [1, numChannel, numPredictions] where numChannel = 4 + numClasses
     * Channels: cx, cy, w, h, class_scores...
     * Coordinates are normalized (0-1 range)
     * Apply NMS
     */
    private fun extractBoxes(): List<DetectionBox>? {
        outputBuffer.buffer.rewind()
        outputFloatBuffer.rewind()
        outputFloatBuffer.get(outputArray)

        val out = outputArray
        val scoreOffset = 4 * numPredictions
        val result = ArrayList<DetectionBox>(MAX_DETECTIONS)

        var i = 0
        while (i < numPredictions) {
            if (result.size >= MAX_DETECTIONS) break

            val score = out[scoreOffset + i]
            if (score > confidenceThreshold) {
                val cx = out[i]
                val cy = out[numPredictions + i]
                val w = out[2 * numPredictions + i]
                val h = out[3 * numPredictions + i]

                val x1 = (cx - w * 0.5f).coerceIn(0f, 1f)
                val y1 = (cy - h * 0.5f).coerceIn(0f, 1f)
                val x2 = (cx + w * 0.5f).coerceIn(0f, 1f)
                val y2 = (cy + h * 0.5f).coerceIn(0f, 1f)

                if (x2 > x1 && y2 > y1 && !result.overlapsExisting(x1, y1, x2, y2)) {
                    result.add(DetectionBox(x1, y1, x2, y2))
                }
            }
            i++
        }

        return result.ifEmpty { null }
    }

    private fun List<DetectionBox>.overlapsExisting(
        x1: Float, y1: Float, x2: Float, y2: Float
    ): Boolean {
        val area = (x2 - x1) * (y2 - y1)
        for (j in indices) {
            val b = this[j]

            val interX1 = maxOf(x1, b.x1)
            val interY1 = maxOf(y1, b.y1)
            val interX2 = minOf(x2, b.x2)
            val interY2 = minOf(y2, b.y2)

            if (interX2 <= interX1 || interY2 <= interY1) continue

            val interArea = (interX2 - interX1) * (interY2 - interY1)
            val areaB = (b.x2 - b.x1) * (b.y2 - b.y1)
            val iou = interArea / (area + areaB - interArea)

            if (iou > NMS_IOU_THRESHOLD) return true
        }
        return false
    }
}

private const val TAG = "Detector"
private const val INPUT_STANDARD_DEVIATION = 255f
private const val INPUT_CHANNELS = 3
const val DEFAULT_CONFIDENCE_PERCENT = 60F
private const val DEFAULT_CONF_THRESHOLD = DEFAULT_CONFIDENCE_PERCENT / 100f
private val IMAGE_TYPE = DataType.FLOAT32
private const val MAX_DETECTIONS = 10
private const val NMS_IOU_THRESHOLD = 0.45f
