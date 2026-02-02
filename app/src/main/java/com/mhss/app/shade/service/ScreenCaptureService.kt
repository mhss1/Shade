package com.mhss.app.shade.service

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import android.os.Handler
import android.os.HandlerThread
import com.mhss.app.shade.R
import com.mhss.app.shade.detection.DetectionBox
import com.mhss.app.shade.detection.Detector
import com.mhss.app.shade.detection.FrameSimilarityChecker
import com.mhss.app.shade.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import com.mhss.app.shade.detection.DEFAULT_CONFIDENCE_PERCENT
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.koin.android.ext.android.inject
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(FlowPreview::class)
class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenDensity: Int = 0
    private var screenWidth: Int = 1
    private var screenHeight: Int = 1
    private var isCapturing = false

    private var isProcessing = AtomicBoolean(false)
    private var detector: Detector? = null

    private val detectorLock = Any()
    private var lastDetectedFrameNumber = 0
    private var currentFrameNumber = 0

    private var bufferBitmap = createBitmap(SMALL_MODEL_IMGZ, SMALL_MODEL_IMGZ)
    private var inputBitmap: Bitmap? = null
    private val inputCanvas = Canvas()

    private var isTargetAppVisible = true
    private val preferenceManager: PreferenceManager by inject()
    private var confidenceThreshold: Float = DEFAULT_CONFIDENCE_PERCENT / 100f
    private var overlayOpacity: Float = 100f
    private var fullScreenModeEnabled: Boolean = false
    private var confidenceUpdatesJob: Job? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var currentRotation: Int = Surface.ROTATION_0
    private var windowManager: WindowManager? = null

    private val frameSimilarityChecker = FrameSimilarityChecker()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_DATA)
            }

            if (resultCode != 0 && data != null) {
                startForeground(NOTIFICATION_ID, createCaptureServiceNotification())
                Toast.makeText(this, R.string.detection_starting, Toast.LENGTH_LONG).show()
                val metrics = resources.displayMetrics
                screenDensity = metrics.densityDpi

                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

                lastDetectedFrameNumber = 0
                currentFrameNumber = 0

                serviceScope.launch {
                    confidenceThreshold = preferenceManager.getConfidencePercent() / 100f
                    overlayOpacity = preferenceManager.getOverlayOpacity()
                    fullScreenModeEnabled = preferenceManager.isFullScreenModeEnabled()
                    val performanceModeEnabled = preferenceManager.isPerformanceModeEnabled()
                    val pixelationLevel = preferenceManager.getPixelationLevel()

                    withContext(Dispatchers.Main) {
                        OverlayManager.setOpacity(overlayOpacity)
                        OverlayManager.setPixelationLevel(pixelationLevel)
                    }

                    val detectorReady = setupDetector(performanceModeEnabled, confidenceThreshold)

                    if (!detectorReady) {
                        OverlayManager.isServiceStartingInProgress = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@launch
                    }

                    confidenceUpdatesJob?.cancel()
                    confidenceUpdatesJob = launch {
                        launch {
                            preferenceManager.confidencePercentFlow.debounce(500)
                                .collectLatest { value ->
                                    val threshold = (value / 100f).coerceIn(0f, 1f)
                                    confidenceThreshold = threshold
                                    detector?.updateThreshold(threshold)
                                }
                        }

                        launch {
                            preferenceManager.performanceModeFlow.drop(1).collectLatest { enabled ->
                                rebuildDetector(enabled)
                            }
                        }

                        launch {
                            preferenceManager.overlayOpacityFlow.debounce(500)
                                .collectLatest { value ->
                                    overlayOpacity = value
                                    withContext(Dispatchers.Main) {
                                        OverlayManager.setOpacity(value)
                                    }
                                }
                        }

                        launch {
                            preferenceManager.fullScreenModeFlow.collectLatest { enabled ->
                                fullScreenModeEnabled = enabled
                                if (!enabled) frameSimilarityChecker.clear()
                            }
                        }

                        launch {
                            preferenceManager.pixelationLevelFlow.debounce(300)
                                .collectLatest { level ->
                                    withContext(Dispatchers.Main) {
                                        OverlayManager.setPixelationLevel(level)
                                    }
                                }
                        }
                    }

                    val currentDetector = detector ?: return@launch
                    screenWidth = currentDetector.tensorWidth
                    screenHeight =
                        (currentDetector.tensorHeight * (metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat())).toInt()

                    withContext(Dispatchers.Main) { startProjection(resultCode, data) }
                }
            }
        } else if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (intent?.action == ACTION_CLEAR) {
            clearDetections()
            frameSimilarityChecker.clear()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun startProjection(resultCode: Int, data: Intent) {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        mediaProjection?.registerCallback(mediaProjectionCallback, null)

        bufferBitmap = createBitmap(SMALL_MODEL_IMGZ, SMALL_MODEL_IMGZ)
        startBackgroundThread()
        updateVirtualDisplayConfig(force = true)
        setupVirtualDisplay()
        startDisplayListener()
        startCapturing()
    }

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }


        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            isTargetAppVisible = isVisible
            if (!isVisible) clearDetections()
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("ImageReaderBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (_: InterruptedException) {
        }
    }

    private fun setupVirtualDisplay() {
        if (screenWidth < 1 || screenHeight < 1) return
        imageReader = createImageReader(screenWidth, screenHeight)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        Log.d(TAG, "Virtual display created: width=$screenWidth, height=$screenHeight")
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        ).apply {
            setOnImageAvailableListener(
                { reader ->
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) processImage(image)
                    } catch (_: Throwable) {
                    }
                },
                backgroundHandler
            )
        }
    }

    private fun startDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DISPLAY_SERVICE) as DisplayManager
        displayManager = dm
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                updateVirtualDisplayConfig()
            }
        }
        dm.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        displayListener = listener
    }

    private fun stopDisplayListener() {
        val dm = displayManager ?: return
        val listener = displayListener ?: return
        dm.unregisterDisplayListener(listener)
        displayListener = null
    }

    private fun updateVirtualDisplayConfig(force: Boolean = false) {
        val detector = detector ?: return
        val display = windowManager?.defaultDisplay ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val windowMetrics = wm.currentWindowMetrics
        val bounds = windowMetrics.bounds
        val rotation = display.rotation
        val newScreenDensity = resources.configuration.densityDpi
        val displayW = bounds.width().coerceAtLeast(1)
        val displayH = bounds.height().coerceAtLeast(1)

        val newWidth = detector.tensorWidth.coerceAtLeast(1)
        val baseHeight = detector.tensorHeight.coerceAtLeast(1)
        val newHeight = ((baseHeight.toFloat() * displayH.toFloat()) / displayW.toFloat()).toInt()
            .coerceAtLeast(1)

        val changed = force ||
                newWidth != screenWidth ||
                newHeight != screenHeight ||
                rotation != currentRotation

        if (!changed) return

        if (mediaProjection != null && backgroundHandler != null && virtualDisplay != null && imageReader != null) {
            reconfigureVirtualDisplay(
                newScreenDensity = newScreenDensity,
                newWidth = newWidth,
                newHeight = newHeight,
                newRotation = rotation
            )
            clearDetections()
        } else {
            screenDensity = newScreenDensity
            screenWidth = newWidth
            screenHeight = newHeight
            currentRotation = rotation
        }
    }

    private fun reconfigureVirtualDisplay(
        newScreenDensity: Int,
        newWidth: Int,
        newHeight: Int,
        newRotation: Int
    ) {
        val virtualDisplay = virtualDisplay ?: return
        val oldReader = imageReader
        screenDensity = newScreenDensity
        screenWidth = newWidth
        screenHeight = newHeight
        currentRotation = newRotation

        val newReader = createImageReader(newWidth, newHeight)
        try {
            virtualDisplay.resize(newWidth, newHeight, newScreenDensity)
            virtualDisplay.surface = newReader.surface
            imageReader = newReader
        } catch (_: Throwable) {
            try {
                newReader.setOnImageAvailableListener(null, null)
                newReader.close()
            } catch (_: Throwable) {
            }
            return
        }

        try {
            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
        } catch (_: Throwable) {
        }
    }

    private fun processImage(image: Image) {
        try {
            if (isProcessing.compareAndSet(false, true)) {
                currentFrameNumber++
                val imageWidth = image.width.coerceAtLeast(1)
                val imageHeight = image.height.coerceAtLeast(1)
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * imageWidth

                val bitmapWidth = imageWidth + (rowPadding / pixelStride)

                if (
                    bufferBitmap.width != bitmapWidth ||
                    bufferBitmap.height != imageHeight
                ) {
                    bufferBitmap.recycle()
                    bufferBitmap = createBitmap(bitmapWidth, imageHeight)
                }
                bufferBitmap.copyPixelsFromBuffer(buffer)

                val inputBitmap = getInputBitmap(imageWidth, imageHeight)
                inputCanvas.setBitmap(inputBitmap)
                inputCanvas.drawBitmap(bufferBitmap, 0f, 0f, null)

                serviceScope.launch {
                    try {
                        synchronized(detectorLock) {
                            try {
                                detector?.detect(inputBitmap)
                            } catch (_: Throwable) {
                                tryRecycleBitmap(inputBitmap)
                            }
                        }
                    } catch (_: Throwable) {
                        tryRecycleBitmap(inputBitmap)
                    } finally {
                        isProcessing.set(false)
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    private fun startCapturing() {
        if (isCapturing) return
        isCapturing = true
        _isRunningFlow.value = true
        OverlayManager.isServiceStartingInProgress = false

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, R.string.detection_started, Toast.LENGTH_SHORT).show()
        }

        Log.d(TAG, "Screen capture started")
    }

    private fun setupDetector(useBigModel: Boolean, threshold: Float): Boolean {
        synchronized(detectorLock) {
            try {
                detector?.clear()

                val modelPath =
                    if (useBigModel) LARGE_MODEL_PATH else SMALL_MODEL_PATH

                val tempDetector = Detector(
                    context = this@ScreenCaptureService,
                    modelPath = modelPath,
                    onBoxesDetected = { boundingBoxes, sourceBitmap ->
                        if (isTargetAppVisible) {
                            val boxesToShow = if (fullScreenModeEnabled) {
                                frameSimilarityChecker.onDetectionSuccess(
                                    sourceBitmap,
                                    boundingBoxes
                                )
                            } else {
                                boundingBoxes
                            }
                            updateDetections(boxesToShow, sourceBitmap)
                        } else {
                            tryRecycleBitmap(sourceBitmap)
                        }
                        lastDetectedFrameNumber = currentFrameNumber
                    },
                    onEmptyDetections = { currentFrame ->
                        val framesSinceLastDetection = currentFrameNumber - lastDetectedFrameNumber

                        if (fullScreenModeEnabled) {
                            if (framesSinceLastDetection < FULLSCREEN_EMPTY_FRAMES_THRESHOLD) {
                                tryRecycleBitmap(currentFrame)
                                return@Detector
                            }

                            if (frameSimilarityChecker.shouldKeepOverlay(currentFrame)) {
                                // if frame frames are identical, consider this frame as a successful detection
                                lastDetectedFrameNumber = currentFrameNumber
                                tryRecycleBitmap(currentFrame)
                                return@Detector
                            }

                            frameSimilarityChecker.clear()
                        } else {
                            if (framesSinceLastDetection < EMPTY_FRAMES_THRESHOLD) {
                                tryRecycleBitmap(currentFrame)
                                return@Detector
                            }
                        }

                        clearDetections()
                        tryRecycleBitmap(currentFrame)
                    },
                ).also {
                    it.setup(threshold)
                }

                detector = tempDetector

                Log.d(TAG, "Detector setup complete with model: $modelPath, threshold: $threshold")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up detector: ${e.message}")
                detector = null
                return false
            }
        }
    }

    private fun rebuildDetector(usePowerModel: Boolean) {
        val rebuilt = setupDetector(usePowerModel, confidenceThreshold)
        if (!rebuilt) return
        serviceScope.launch(Dispatchers.Main) { updateVirtualDisplayConfig() }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopCapture()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun updateDetections(boxes: List<DetectionBox>, sourceBitmap: Bitmap) {
        mainHandler.post {
            OverlayManager.updateDetections(boxes, sourceBitmap)
            tryRecycleBitmap(sourceBitmap)
        }
    }

    private fun getInputBitmap(width: Int, height: Int): Bitmap {
        val existing = inputBitmap
        if (existing != null &&
            !existing.isRecycled &&
            existing.isMutable &&
            existing.width == width &&
            existing.height == height
        ) {
            return existing
        }
        existing?.recycle()
        val newBitmap = createBitmap(width, height)
        inputBitmap = newBitmap
        return newBitmap
    }

    private inline fun tryRecycleBitmap(bitmap: Bitmap) {
        if (bitmap != inputBitmap && !bitmap.isRecycled && bitmap.isMutable) bitmap.recycle()
    }

    private inline fun clearDetections() {
        mainHandler.post { OverlayManager.clearDetections() }
    }

    private fun stopCapture() {
        isCapturing = false
        _isRunningFlow.value = false
        OverlayManager.isServiceStartingInProgress = false
        stopDisplayListener()
        stopBackgroundThread()

        try {
            virtualDisplay?.release()
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
            mediaProjection?.unregisterCallback(mediaProjectionCallback)
            mediaProjection?.stop()
            clearDetections()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping capture: ${e.message}")
        }

        virtualDisplay = null
        imageReader = null
        mediaProjection = null

        synchronized(detectorLock) {
            detector?.clear()
        }
        frameSimilarityChecker.clear()
        confidenceUpdatesJob?.cancel()

        bufferBitmap.recycle()
        inputBitmap?.recycle()
        inputBitmap = null

        Log.d(TAG, "Screen capture stopped")
    }


    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.mhss.app.shade.service.ACTION_START"
        const val ACTION_STOP = "com.mhss.app.shade.service.ACTION_STOP"
        const val ACTION_CLEAR = "com.mhss.app.shade.service.ACTION_CLEAR"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        private const val LARGE_MODEL_PATH = "shade_large.tflite"
        private const val SMALL_MODEL_PATH = "shade_small.tflite"

        private const val SMALL_MODEL_IMGZ = 416

        private const val EMPTY_FRAMES_THRESHOLD = 3
        private const val FULLSCREEN_EMPTY_FRAMES_THRESHOLD = 4

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        val isRunning: Boolean get() = _isRunningFlow.value
    }
}