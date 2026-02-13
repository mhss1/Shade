package com.mhss.app.shade.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import androidx.core.graphics.createBitmap

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val onEmptyDetections: (sourceBitmap: Bitmap) -> Unit,
    private val onBoxesDetected: (boundingBoxes: List<DetectionBox>, sourceBitmap: Bitmap) -> Unit
) {

    private val boxes = ArrayList<DetectionBox>(MAX_DETECTIONS)
    private val isReady = AtomicBoolean(false)

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    var tensorWidth = 0
    var tensorHeight = 0
    private var numChannel = 0
    private var maxDetections = 0

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

        val delegate = tryCreateGpuDelegate()

        if (delegate != null) {
            options.addDelegate(delegate)
            gpuDelegate = delegate
        } else {
            options.setUseXNNPACK(true)
            Log.d(TAG, "Using CPU with XNNPACK")
        }

        interpreter = Interpreter(model, options)

        val inputShape = interpreter?.getInputTensor(0)?.shape() ?: return
        val outputShape = interpreter?.getOutputTensor(0)?.shape() ?: return

        tensorWidth = inputShape[1]
        tensorHeight = inputShape[2]

        maxDetections = outputShape[1]
        numChannel = outputShape[2]  // 6 values per detection (x1, y1, x2, y2, conf, class)

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

        outputArray = FloatArray(maxDetections * numChannel)

        isReady.set(true)
    }

    private fun tryCreateGpuDelegate(): GpuDelegate? {
        return try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegate = GpuDelegate(compatList.bestOptionsForThisDevice)
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
        gpuDelegate?.close()
        gpuDelegate = null
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
     * Extract boxes from YOLO26 end-to-end format.
     * Output shape: [1, 300, 6] with x1, y1, x2, y2, conf, class per detection.
     * Coordinates are already normalized (0-1 range).
     * NMS is already applied by the model.
     */
    private inline fun extractBoxes(): List<DetectionBox>? {
        outputBuffer.buffer.rewind()
        outputFloatBuffer.rewind()
        outputFloatBuffer.get(outputArray)

        val currentOutputArray = outputArray

        boxes.clear()

        for (i in 0 until maxDetections) {
            if (boxes.size >= MAX_DETECTIONS) break

            val baseIndex = i * numChannel
            val confidence = currentOutputArray[baseIndex + 4]
            val classId = currentOutputArray[baseIndex + 5]

            // The model outputs the highest confidence first so we can break as soon as we reach a low confidence
            if (confidence <= confidenceThreshold) break
            if (classId != 0.0f) continue

            val x1 = currentOutputArray[baseIndex]
            val y1 = currentOutputArray[baseIndex + 1]
            val x2 = currentOutputArray[baseIndex + 2]
            val y2 = currentOutputArray[baseIndex + 3]

            if (x2 <= x1 || y2 <= y1) continue

            boxes.add(
                DetectionBox(
                    x1.coerceIn(0f, 1f),
                    y1.coerceIn(0f, 1f),
                    x2.coerceIn(0f, 1f),
                    y2.coerceIn(0f, 1f)
                )
            )
        }

        return boxes.ifEmpty { null }?.toList()
    }
}

private const val TAG = "Detector"
private const val INPUT_STANDARD_DEVIATION = 255f
private const val INPUT_CHANNELS = 3
const val DEFAULT_CONFIDENCE_PERCENT = 60F
private const val DEFAULT_CONF_THRESHOLD = DEFAULT_CONFIDENCE_PERCENT / 100f
private val IMAGE_TYPE = DataType.FLOAT32
private const val MAX_DETECTIONS = 15
