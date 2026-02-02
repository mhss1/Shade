package com.mhss.app.shade.detection

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import android.util.Log
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

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

    private var tensorImage: TensorImage = TensorImage(IMAGE_TYPE)

    private lateinit var outputBuffer: TensorBuffer
    private lateinit var outputFloatBuffer: FloatBuffer
    private lateinit var outputArray: FloatArray

    private lateinit var imageProcessor: ImageProcessor

    private var confidenceThreshold = DEFAULT_CONF_THRESHOLD

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

        imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(tensorHeight, tensorWidth, ResizeMethod.NEAREST_NEIGHBOR))
            .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
            .add(CastOp(IMAGE_TYPE))
            .build()

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
    }

    fun detect(frameBitmap: Bitmap) {
        if (!isReady.get()) return
        val currentInterpreter = interpreter ?: return

        tensorImage.load(frameBitmap)
        val processedImage = imageProcessor.process(tensorImage)

        currentInterpreter.run(processedImage.buffer, outputBuffer.buffer)

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
private const val INPUT_MEAN = 0f
private const val INPUT_STANDARD_DEVIATION = 255f
const val DEFAULT_CONFIDENCE_PERCENT = 60F
private const val DEFAULT_CONF_THRESHOLD = DEFAULT_CONFIDENCE_PERCENT / 100f
private val IMAGE_TYPE = DataType.FLOAT32
private const val MAX_DETECTIONS = 15
