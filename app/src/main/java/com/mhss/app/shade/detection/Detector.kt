package com.mhss.app.shade.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import android.util.Log
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean

class Detector(
    private val context: Context,
    private val modelPath: String,
    private val segmentationMode: Boolean,
    private val onEmptyDetections: (sourceBitmap: Bitmap) -> Unit,
    private val onBoxesDetected: ((boundingBoxes: List<DetectionBox>, sourceBitmap: Bitmap) -> Unit)? = null,
    private val onSegmentationResult: ((segmentations: List<RawSegmentation>, sourceBitmap: Bitmap) -> Unit)? = null
) {

    private val isReady = AtomicBoolean(false)

    private var interpreter: Interpreter? = null
    private var delegate: Delegate? = null

    var tensorWidth = 0
    var tensorHeight = 0
    private var numChannel = 0
    private var numPredictions = 0

    private var numMaskCoeffs = 0
    private var protoHeight = 0
    private var protoWidth = 0
    private var protoChannels = 0
    private val detectionOutputIndex = 0
    private val protoOutputIndex = 1

    private var segmentationMaskCoeffs = FloatArray(0)

    private lateinit var outputBuffer: TensorBuffer
    private lateinit var outputFloatBuffer: FloatBuffer
    private lateinit var outputArray: FloatArray

    private lateinit var detectionBuffer: TensorBuffer
    private lateinit var detectionFloatBuffer: FloatBuffer
    private lateinit var detectionArray: FloatArray

    private lateinit var protoBuffer: TensorBuffer
    private lateinit var protoFloatBuffer: FloatBuffer
    private lateinit var protoArray: FloatArray

    private val segmentationOutputs = HashMap<Int, Any>()

    // Reused per frame: selected boxes and their source prediction indices.
    private val segmentationCandidateBoxes = ArrayList<DetectionBox>(MAX_DETECTIONS)
    private val segmentationCandidatePredictionIndices = IntArray(MAX_DETECTIONS)

    private var confidenceThreshold = DEFAULT_CONF_THRESHOLD

    private var inputBuffer: ByteBuffer? = null
    private var inputFloatBuffer: FloatBuffer? = null
    private var inputFloatArray: FloatArray? = null
    private var inputPixels: IntArray? = null
    private var resizedBitmap: Bitmap? = null
    private var resizeCanvas: Canvas? = null
    private var resizeRect: Rect? = null

    fun setup(threshold: Float) {
        isReady.set(false)
        confidenceThreshold = threshold

        if (segmentationMode) {
            setupSegmentation()
        } else {
            setupDetection()
        }
    }

    private fun setupDetection() {
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

        val inputShape = interpreter?.getInputTensor(0)?.shape()
            ?: throw IllegalStateException("Failed to get input tensor shape for $modelPath")
        val outputShape = interpreter?.getOutputTensor(0)?.shape()
            ?: throw IllegalStateException("Failed to get output tensor shape for $modelPath")

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

    private fun setupSegmentation() {
        try {
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

            Log.d(TAG, "Creating interpreter...")
            interpreter = Interpreter(model, options)
            Log.d(TAG, "Interpreter created successfully")

            val inputShape = interpreter?.getInputTensor(0)?.shape()
                ?: throw IllegalStateException("Failed to get input tensor shape for $modelPath")
            Log.d(TAG, "Input shape: ${inputShape.contentToString()}")

            tensorWidth = inputShape[1]
            tensorHeight = inputShape[2]

            val detectionShape =
                interpreter?.getOutputTensor(detectionOutputIndex)?.shape()
                    ?: throw IllegalStateException("Failed to get detection output tensor shape for $modelPath")
            val protoShape =
                interpreter?.getOutputTensor(protoOutputIndex)?.shape()
                    ?: throw IllegalStateException("Failed to get proto output tensor shape for $modelPath")

            numChannel = detectionShape[1]
            numPredictions = detectionShape[2]

            protoHeight = protoShape[1]
            protoWidth = protoShape[2]
            protoChannels = protoShape[3]

            numMaskCoeffs = protoChannels
            segmentationMaskCoeffs = FloatArray(numMaskCoeffs)

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

            detectionBuffer = TensorBuffer.createFixedSize(detectionShape, IMAGE_TYPE)
            detectionFloatBuffer = detectionBuffer.buffer.asFloatBuffer()
            detectionArray = FloatArray(numPredictions * numChannel)

            protoBuffer = TensorBuffer.createFixedSize(protoShape, IMAGE_TYPE)
            protoFloatBuffer = protoBuffer.buffer.asFloatBuffer()
            protoArray = FloatArray(protoHeight * protoWidth * protoChannels)

            segmentationOutputs[protoOutputIndex] = protoBuffer.buffer
            segmentationOutputs[detectionOutputIndex] = detectionBuffer.buffer

            isReady.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Setup failed with exception: ${e.message}", e)
            throw e
        }
    }

    private fun tryCreateGpuDelegate(): GpuDelegate? {
        return try {
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val options = compatList.bestOptionsForThisDevice
                val modelToken = modelPath.substringBeforeLast('.').replace('/', '_')
                val cacheDir = File(context.filesDir, "gpu_delegate_cache/$modelToken")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                options.setSerializationParams(cacheDir.absolutePath, modelToken)
                options.isPrecisionLossAllowed = true

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
        runCatching {
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

        if (segmentationMode) {
            currentInterpreter.runForMultipleInputsOutputs(arrayOf(inBuffer), segmentationOutputs)

            val segmentations = extractSegmentations()

            if (segmentations == null) {
                onEmptyDetections(frameBitmap)
                return
            }

            onSegmentationResult?.invoke(segmentations, frameBitmap)
        } else {
            currentInterpreter.run(inBuffer, outputBuffer.buffer)

            val boxes = extractBoxes()

            if (boxes == null) {
                onEmptyDetections(frameBitmap)
                return
            }

            onBoxesDetected?.invoke(boxes, frameBitmap)
        }
    }

    fun updateThreshold(threshold: Float) {
        confidenceThreshold = threshold
    }

    fun isSetupComplete(): Boolean = isReady.get()

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

        for (i in 0 until numPredictions) {
            if (result.size >= MAX_DETECTIONS) break

            val score = out[scoreOffset + i]
            if (score < confidenceThreshold) continue

            val cx = out[i]
            val cy = out[numPredictions + i]
            val w = out[2 * numPredictions + i]
            val h = out[3 * numPredictions + i]

            val x1 = (cx - w * 0.5f).coerceIn(0f, 1f)
            val y1 = (cy - h * 0.5f).coerceIn(0f, 1f)
            val x2 = (cx + w * 0.5f).coerceIn(0f, 1f)
            val y2 = (cy + h * 0.5f).coerceIn(0f, 1f)

            if (x2 <= x1 || y2 <= y1) continue

            result.applyNms(x1, y1, x2, y2, score)
        }

        return result.ifEmpty { null }
    }

    /**
     * Applies Non-Maximum Suppression (NMS) to filter overlapping detection boxes.
     * Not the typical NMS, this is simplified to optimize for maximum processing performance that keeps the higher confidence when overlaps occur.
     */
    private fun MutableList<DetectionBox>.applyNms(
        x1: Float, y1: Float, x2: Float, y2: Float, confidence: Float
    ) {
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

            if (iou > NMS_IOU_THRESHOLD) {
                if (confidence > b.confidence) {
                    this[j] = DetectionBox(x1, y1, x2, y2, confidence)
                }
                return
            }
        }
        add(DetectionBox(x1, y1, x2, y2, confidence))
    }

    private fun extractSegmentations(): List<RawSegmentation>? {
        detectionBuffer.buffer.rewind()
        detectionFloatBuffer.rewind()
        detectionFloatBuffer.get(detectionArray)

        protoBuffer.buffer.rewind()
        protoFloatBuffer.rewind()
        protoFloatBuffer.get(protoArray)

        val det = detectionArray
        val scoreOffset = 4 * numPredictions

        return try {
            // choose unique candidate boxes only with NMS (no mask generation yet)
            generateSegmentationCandidateBoxes(det, scoreOffset)
            if (segmentationCandidateBoxes.isEmpty()) return null

            // build masks only for selected boxes
            val segmentations = buildSegmentations()

            segmentations.ifEmpty { null }
        } finally {
            segmentationCandidateBoxes.clear()
        }
    }

    private fun generateSegmentationCandidateBoxes(detectionValues: FloatArray, scoreOffset: Int) {
        for (i in 0 until numPredictions) {
            if (segmentationCandidateBoxes.size >= MAX_DETECTIONS) break

            val score = detectionValues[scoreOffset + i]
            if (score <= confidenceThreshold) continue

            val cx = detectionValues[i]
            val cy = detectionValues[numPredictions + i]
            val w = detectionValues[2 * numPredictions + i]
            val h = detectionValues[3 * numPredictions + i]

            val x1 = (cx - w * 0.5f).coerceIn(0f, 1f)
            val y1 = (cy - h * 0.5f).coerceIn(0f, 1f)
            val x2 = (cx + w * 0.5f).coerceIn(0f, 1f)
            val y2 = (cy + h * 0.5f).coerceIn(0f, 1f)
            if (x2 <= x1 || y2 <= y1) continue

            val area = (x2 - x1) * (y2 - y1)
            var overlapIndex = -1

            val box = DetectionBox(x1, y1, x2, y2, score)
            for (j in segmentationCandidateBoxes.indices) {
                val existing = segmentationCandidateBoxes[j]
                val interX1 = maxOf(x1, existing.x1)
                val interY1 = maxOf(y1, existing.y1)
                val interX2 = minOf(x2, existing.x2)
                val interY2 = minOf(y2, existing.y2)
                if (interX2 <= interX1 || interY2 <= interY1) continue

                val interArea = (interX2 - interX1) * (interY2 - interY1)
                val areaB = (existing.x2 - existing.x1) * (existing.y2 - existing.y1)
                val iou = interArea / (area + areaB - interArea)
                if (iou > NMS_IOU_THRESHOLD) {
                    overlapIndex = j
                    break
                }
            }

            if (overlapIndex >= 0) {
                // For overlapping candidates, keep the higher-confidence box.
                if (score > segmentationCandidateBoxes[overlapIndex].confidence) {
                    segmentationCandidateBoxes[overlapIndex] = box
                    segmentationCandidatePredictionIndices[overlapIndex] = i
                }
                continue
            }

            val insertIndex = segmentationCandidateBoxes.size
            segmentationCandidateBoxes.add(box)
            segmentationCandidatePredictionIndices[insertIndex] = i
        }
    }

    private fun buildSegmentations(): List<RawSegmentation> {
        val maskCoeffsStartChannel = numChannel - numMaskCoeffs
        val detectionValues = detectionArray
        val segmentations = ArrayList<RawSegmentation>(segmentationCandidateBoxes.size)

        for (index in segmentationCandidateBoxes.indices) {
            val box = segmentationCandidateBoxes[index]
            val predictionIndex = segmentationCandidatePredictionIndices[index]

            // Recover this detection's mask coefficients, then apply them over the proto grid.
            val maskCoeffs = segmentationMaskCoeffs
            for (j in 0 until numMaskCoeffs) {
                maskCoeffs[j] =
                    detectionValues[(maskCoeffsStartChannel + j) * numPredictions + predictionIndex]
            }

            val protoBoxLeft = (box.x1 * protoWidth).toInt().coerceIn(0, protoWidth - 1)
            val protoBoxTop = (box.y1 * protoHeight).toInt().coerceIn(0, protoHeight - 1)
            val protoBoxRight = (box.x2 * protoWidth).toInt().coerceIn(protoBoxLeft + 1, protoWidth)
            val protoBoxBottom =
                (box.y2 * protoHeight).toInt().coerceIn(protoBoxTop + 1, protoHeight)

            val maskWidth = protoBoxRight - protoBoxLeft
            val maskHeight = protoBoxBottom - protoBoxTop
            if (maskWidth <= 0 || maskHeight <= 0) continue

            val mask = BooleanArray(maskWidth * maskHeight)
            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    val protoX = protoBoxLeft + x
                    val protoY = protoBoxTop + y
                    var maskValue = 0f
                    for (c in 0 until numMaskCoeffs) {
                        maskValue +=
                            protoArray[(protoY * protoWidth + protoX) * protoChannels + c] * maskCoeffs[c]
                    }
                    mask[y * maskWidth + x] = maskValue > 0f
                }
            }

            segmentations.add(RawSegmentation(box, mask, maskWidth, maskHeight))
        }

        return segmentations
    }
}

private const val TAG = "Detector"
private const val INPUT_STANDARD_DEVIATION = 255f
private const val INPUT_CHANNELS = 3
const val DEFAULT_CONFIDENCE_PERCENT = 70F
private const val DEFAULT_CONF_THRESHOLD = DEFAULT_CONFIDENCE_PERCENT / 100f
private val IMAGE_TYPE = DataType.FLOAT32
private const val MAX_DETECTIONS = 12
private const val NMS_IOU_THRESHOLD = 0.45f
