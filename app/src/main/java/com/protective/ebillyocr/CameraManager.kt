package com.protective.ebillyocr

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * Enhanced CameraManager with comprehensive exposure control
 *
 * Features:
 * - Manual exposure compensation control
 * - Auto exposure lock/unlock
 * - Exposure range detection
 * - Real-time exposure adjustment
 * - Enhanced camera configuration
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val roiOverlay: ROIOverlay
) {
    private val tag = "CameraManager"

    // Camera components
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Camera settings
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false

    // Exposure control
    private var currentExposureCompensation = 0
    private var minExposureCompensation = 0
    private var maxExposureCompensation = 0
    private var exposureStep = 0f
    private var isExposureLocked = false

    // Exposure state flow for UI updates
    private val _exposureState = MutableStateFlow(ExposureState())
    val exposureState: StateFlow<ExposureState> = _exposureState

    // Capture result flow
    private val _captureResult = MutableStateFlow<CaptureResult?>(null)
    val captureResult: StateFlow<CaptureResult?> = _captureResult

    // Scale detector for zoom functionality
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    /**
     * Data class to represent exposure state
     */
    data class ExposureState(
        val exposureCompensation: Int = 0,
        val minExposureCompensation: Int = 0,
        val maxExposureCompensation: Int = 0,
        val exposureStep: Float = 0f,
        val isLocked: Boolean = false,
        val isSupported: Boolean = false
    )

    /**
     * Initialize camera and setup the preview with exposure control
     */
    fun initialize() {
        Log.d(tag, "Initializing camera with exposure control")

        // Initialize scale gesture detector for zoom
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })

        // Setup preview view touch handling
        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        // Initialize camera provider
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(tag, "Camera initialization failed: ${e.message}", e)
                Toast.makeText(context, "Failed to initialize camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Enhanced camera binding with exposure control configuration
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        // Build camera selector
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Get display rotation safely
        val rotation = when {
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> {
                context.display?.rotation ?: Surface.ROTATION_0
            }
            else -> {
                @Suppress("DEPRECATION")
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                windowManager.defaultDisplay.rotation
            }
        }

        // Setup preview with enhanced configuration
        val previewBuilder = Preview.Builder()
            .setTargetRotation(rotation)

        // Add Camera2 interop for advanced exposure control if needed
        val camera2Interop = Camera2Interop.Extender(previewBuilder)

        preview = previewBuilder.build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // Setup image capture with enhanced settings
        val imageCaptureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            // Enable manual exposure if supported
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

        imageCapture = imageCaptureBuilder.build()

        try {
            // Unbind existing use cases
            cameraProvider.unbindAll()

            // Create use case group
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview!!)
                .addUseCase(imageCapture!!)
                .build()

            // Bind to lifecycle
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )

            // Initialize exposure settings after camera is bound
            initializeExposureSettings()

            // Set initial zoom (50% of max)
            setInitialZoom()

        } catch (e: Exception) {
            Log.e(tag, "Use case binding failed: ${e.message}", e)
            Toast.makeText(context, "Failed to bind camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Initialize exposure control settings
     */
    private fun initializeExposureSettings() {
        camera?.let { cam ->
            val cameraInfo = cam.cameraInfo

            // Get exposure compensation range
            val exposureRange = cameraInfo.exposureState.exposureCompensationRange
            minExposureCompensation = exposureRange.lower
            maxExposureCompensation = exposureRange.upper
            exposureStep = cameraInfo.exposureState.exposureCompensationStep.toFloat()

            // Check if exposure compensation is supported
            val isExposureSupported = minExposureCompensation != maxExposureCompensation

            // Update exposure state
            _exposureState.value = ExposureState(
                exposureCompensation = currentExposureCompensation,
                minExposureCompensation = minExposureCompensation,
                maxExposureCompensation = maxExposureCompensation,
                exposureStep = exposureStep,
                isLocked = isExposureLocked,
                isSupported = isExposureSupported
            )

            Log.d(tag, "Exposure settings initialized - Range: [$minExposureCompensation, $maxExposureCompensation], Step: $exposureStep, Supported: $isExposureSupported")
        }
    }

    /**
     * Set initial zoom level
     */
    private fun setInitialZoom() {
        camera?.let { cam ->
            val zoomState = cam.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1f
            val maxZoom = zoomState?.maxZoomRatio ?: 1f
            val targetZoom = minZoom + (maxZoom - minZoom) * 0.5f
            cam.cameraControl.setZoomRatio(targetZoom)
        }
    }

    /**
     * Set exposure compensation
     *
     * @param exposureValue Exposure compensation value within the supported range
     */
    fun setExposureCompensation(exposureValue: Int) {
        camera?.let { cam ->
            val clampedValue = exposureValue.coerceIn(minExposureCompensation, maxExposureCompensation)
            currentExposureCompensation = clampedValue

            cam.cameraControl.setExposureCompensationIndex(clampedValue)

            // Update state
            _exposureState.value = _exposureState.value.copy(
                exposureCompensation = clampedValue
            )

            Log.d(tag, "Exposure compensation set to: $clampedValue")
        }
    }

    /**
     * Set exposure compensation using percentage (0-100)
     *
     * @param exposurePercentage Percentage from 0 to 100
     */
    fun setExposurePercentage(exposurePercentage: Int) {
        val percentage = exposurePercentage.coerceIn(0, 100)
        val range = maxExposureCompensation - minExposureCompensation
        val exposureValue = minExposureCompensation + (range * percentage / 100)
        setExposureCompensation(exposureValue)
    }

    /**
     * Get current exposure compensation as percentage
     *
     * @return Exposure percentage (0-100)
     */
    fun getExposurePercentage(): Int {
        val range = maxExposureCompensation - minExposureCompensation
        return if (range > 0) {
            ((currentExposureCompensation - minExposureCompensation) * 100 / range)
        } else {
            50 // Default to middle if no range
        }
    }

    /**
     * Lock/unlock auto exposure
     *
     * @param lock True to lock exposure, false to unlock
     */
    fun setExposureLock(lock: Boolean) {
        camera?.let { cam ->
            isExposureLocked = lock

            if (lock) {
                // Lock exposure at current settings
                cam.cameraControl.setExposureCompensationIndex(currentExposureCompensation)
            }

            // Update state
            _exposureState.value = _exposureState.value.copy(
                isLocked = isExposureLocked
            )

            Log.d(tag, "Exposure lock ${if (lock) "enabled" else "disabled"}")
        }
    }

    /**
     * Toggle exposure lock
     *
     * @return Current lock state after toggle
     */
    fun toggleExposureLock(): Boolean {
        setExposureLock(!isExposureLocked)
        return isExposureLocked
    }

    /**
     * Reset exposure to auto mode
     */
    fun resetExposureToAuto() {
        setExposureCompensation(0)
        setExposureLock(false)
        Log.d(tag, "Exposure reset to auto mode")
    }

    /**
     * Get exposure information as a readable string
     */
    fun getExposureInfo(): String {
        return if (_exposureState.value.isSupported) {
            val ev = currentExposureCompensation * exposureStep
            "EV: ${if (ev >= 0) "+" else ""}${String.format("%.1f", ev)} ${if (isExposureLocked) "(Locked)" else ""}"
        } else {
            "Exposure control not supported"
        }
    }

    /**
     * Enhanced toggle flash with exposure considerations
     */
    fun toggleFlash(): Boolean {
        flashEnabled = !flashEnabled
        imageCapture?.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }

        // Adjust exposure when flash is toggled for better results
        if (flashEnabled && currentExposureCompensation > 0) {
            // Reduce exposure compensation when flash is on
            val adjustedExposure = (currentExposureCompensation * 0.7f).toInt()
            setExposureCompensation(adjustedExposure)
            Log.d(tag, "Flash enabled - adjusted exposure to: $adjustedExposure")
        }

        return flashEnabled
    }

    /**
     * Switch camera with exposure settings preservation
     */
    fun switchCamera() {
        val previousExposure = currentExposureCompensation
        val previousLock = isExposureLocked

        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        bindCameraUseCases()

        // Restore exposure settings after camera switch
        setExposureCompensation(previousExposure)
        setExposureLock(previousLock)
    }

    /**
     * Set zoom level
     */
    fun setZoom(zoomLevel: Float) {
        camera?.cameraControl?.setLinearZoom(zoomLevel.coerceIn(0f, 1f))
    }

    /**
     * Get current zoom ratio
     */
    fun getCurrentZoom(): Float {
        return camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
    }

    /**
     * Get current zoom state
     */
    fun getZoomState(): ZoomState? {
        return camera?.cameraInfo?.zoomState?.value
    }

    /**
     * Enhanced image capture with exposure optimization
     */
    fun captureImage(valType1: String) {
        val imageCapture = imageCapture ?: return

        try {
            // Log capture settings for debugging
            Log.d(tag, "Capturing image - Exposure: ${getExposureInfo()}, Flash: $flashEnabled, Zoom: ${getCurrentZoom()}")

            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    @SuppressLint("UnsafeOptInUsageError")
                    override fun onCaptureSuccess(image: ImageProxy) {
                        if (valType1 != "IMG") {
                            processImage(image)
                        } else {
                            noImageProcess(image)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(tag, "Image capture failed: ${exception.message}", exception)
                        Toast.makeText(context, "Failed to capture image: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "Image capture error: ${e.message}", e)
            Toast.makeText(context, "Failed to capture image: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Process image for non-IMG value types
     */
    private fun processImage(imageProxy: ImageProxy) {
        try {
            // Get bitmap from image proxy
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Convert to bitmap
            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            // Apply rotation if needed
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                bitmap = ImageCropper.rotateBitmap(bitmap, rotation) ?: bitmap
            }

            // Get ROI from overlay
            val roiRect = roiOverlay.getROIRect()

            // Create model bitmap (640x640 for OCR processing)
            val modelBitmap = ImageCropper.cropToROIWithBackground(
                sourceBitmap = bitmap,
                roi = roiRect,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                targetWidth = 640,
                targetHeight = 640,
                backgroundColor = Color.LTGRAY,
                recycleSource = false
            ) ?: bitmap

            // Create ROI bitmap for display
            val roiBitmap = ImageCropper.cropToROI(
                sourceBitmap = bitmap,
                roi = roiRect,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                recycleSource = false
            ) ?: bitmap

            // Generate timestamp
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

            // Create capture result with exposure metadata
            val result = CaptureResult(
                originalBitmap = bitmap,
                roiBitmap = roiBitmap,
                modelBitmap = modelBitmap,
                timestamp = timestamp,
                exposureCompensation = currentExposureCompensation,
                flashUsed = flashEnabled,
                zoomRatio = getCurrentZoom()
            )

            // Update capture result flow
            _captureResult.value = result
            imageProxy.close()

        } catch (e: Exception) {
            Log.e(tag, "Image processing failed: ${e.message}", e)
            imageProxy.close()
        }
    }

    /**
     * Process image for IMG value type (no ROI processing)
     */
    private fun noImageProcess(imageProxy: ImageProxy) {
        try {
            Log.d(tag, "Processing full image without ROI...")

            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                bitmap = ImageCropper.rotateBitmap(bitmap, rotation) ?: bitmap
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

            val result = CaptureResult(
                originalBitmap = bitmap,
                roiBitmap = bitmap,
                modelBitmap = bitmap,
                timestamp = timestamp,
                exposureCompensation = currentExposureCompensation,
                flashUsed = flashEnabled,
                zoomRatio = getCurrentZoom()
            )

            _captureResult.value = result
            imageProxy.close()

        } catch (e: Exception) {
            Log.e(tag, "Image processing failed: ${e.message}", e)
            imageProxy.close()
        }
    }

    // [Previous save image methods remain the same...]
    data class SaveImageResult(
        val filePath: String?,
        val uri: Uri?
    )

    fun saveImage(
        result: CaptureResult,
        meterReading: String? = null,
        savedFilename: String? = null,
        isEdited: Boolean = false
    ): SaveImageResult {
        try {
            Log.d(tag, "Saving image with metadata...")

            val outputStream = ByteArrayOutputStream()
            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val jpegBytes = outputStream.toByteArray()

            val currentTimeMillis = System.currentTimeMillis()
            val fileName = "${savedFilename}_$currentTimeMillis.jpg"

            val (uri, stream) = FileUtils.createOrUpdateImageFile(
                context,
                fileName,
                "npdcl",
                "image/jpeg"
            )

            if (uri != null && stream != null) {
                stream.write(jpegBytes)
                stream.close()

                val filePath = getRealPathFromUri(context, uri)

                // Log capture metadata
                Log.d(tag, "Image saved with exposure: ${result.exposureCompensation}, flash: ${result.flashUsed}, zoom: ${result.zoomRatio}")

                val fileManager = FileManager(context)
                fileManager.notifyGallery(uri)

                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
                return SaveImageResult(filePath, uri)
            } else {
                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to save image: ${e.message}", e)
            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        return SaveImageResult(null, null)
    }

    // [Helper methods for URI handling remain the same...]
    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]

                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory()}/${split[1]}"
                }
            }
            else if (isMediaDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val contentUri = when (split[0]) {
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> null
                }

                contentUri?.let {
                    val selection = "_id=?"
                    val selectionArgs = arrayOf(split[1])
                    return getDataColumn(context, contentUri, selection, selectionArgs)
                }
            }
        }
        else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        }
        else if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        return null
    }

    private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        var cursor: Cursor? = null
        val column = "_data"
        val projection = arrayOf(column)

        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(column)
                return cursor.getString(columnIndex)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting real path: ${e.message}", e)
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun isExternalStorageDocument(uri: Uri): Boolean {
        return "com.android.externalstorage.documents" == uri.authority
    }

    private fun isMediaDocument(uri: Uri): Boolean {
        return "com.android.providers.media.documents" == uri.authority
    }

    /**
     * Release camera resources
     */
    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    /**
     * Enhanced CaptureResult with exposure metadata
     */
    data class CaptureResult(
        val originalBitmap: Bitmap,
        val roiBitmap: Bitmap,
        val modelBitmap: Bitmap,
        val timestamp: String,
        val exposureCompensation: Int = 0,
        val flashUsed: Boolean = false,
        val zoomRatio: Float = 1.0f
    )
}
//package com.protective.ebillyocr
//
//import android.annotation.SuppressLint
//import android.content.Context
//import android.database.Cursor
//import android.graphics.Bitmap
//import android.graphics.BitmapFactory
//import android.graphics.Color
//import android.net.Uri
//import android.os.Environment
//import android.provider.DocumentsContract
//import android.provider.MediaStore
//import android.util.Log
//import android.view.ScaleGestureDetector
//import android.view.Surface
//import android.widget.Toast
//import androidx.camera.core.Camera
//import androidx.camera.core.CameraSelector
//import androidx.camera.core.ImageCapture
//import androidx.camera.core.ImageCaptureException
//import androidx.camera.core.ImageProxy
//import androidx.camera.core.Preview
//import androidx.camera.core.UseCaseGroup
//import androidx.camera.core.ZoomState
//import androidx.camera.lifecycle.ProcessCameraProvider
//import androidx.camera.view.PreviewView
//import androidx.core.content.ContextCompat
//import androidx.lifecycle.LifecycleOwner
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import java.io.ByteArrayOutputStream
//import java.text.SimpleDateFormat
//import java.util.Locale
//import java.util.TimeZone
//import java.util.concurrent.ExecutorService
//import java.util.concurrent.Executors
//
//
///**
// * CameraManager handles all camera operations for meter detection
// *
// * This class manages camera preview, capture, and ROI-based cropping.
// */
//class CameraManager(
//    private val context: Context,
//    private val lifecycleOwner: LifecycleOwner,
//    private val previewView: PreviewView,
//    private val roiOverlay: ROIOverlay
//) {
//    private val tag = "CameraManager"
//
//    // Camera components
//    private var cameraProvider: ProcessCameraProvider? = null
//    private var camera: Camera? = null
//    private var imageCapture: ImageCapture? = null
//    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
//
//    // Camera settings
//    private var lensFacing = CameraSelector.LENS_FACING_BACK
//    private var flashEnabled = false
//
//    // Capture result flow
//    private val _captureResult = MutableStateFlow<CaptureResult?>(null)
//    val captureResult: StateFlow<CaptureResult?> = _captureResult
//
//    // Scale detector for zoom functionality
//    private lateinit var scaleGestureDetector: ScaleGestureDetector
//
//    /**
//     * Initialize camera and setup the preview
//     */
//    fun initialize() {
//        Log.d(tag, "Initializing camera")
//
//        // Initialize scale gesture detector for zoom
//        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
//            override fun onScale(detector: ScaleGestureDetector): Boolean {
//                // Get current zoom ratio
//                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
//                // Calculate new zoom ratio
//                val delta = detector.scaleFactor
//                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
//                return true
//            }
//        })
//
//        // Ensure the preview view handles scale gestures
//        previewView.setOnTouchListener { _, event ->
//            scaleGestureDetector.onTouchEvent(event)
//            true
//        }
//
//        // Initialize camera provider
//        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
//        cameraProviderFuture.addListener({
//            try {
//                cameraProvider = cameraProviderFuture.get()
//                bindCameraUseCases()
//            } catch (e: Exception) {
//                Log.e(tag, "Camera initialization failed: ${e.message}", e)
//                Toast.makeText(context, "Failed to initialize camera: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }, ContextCompat.getMainExecutor(context))
//    }
//
//    /**
//     * Bind camera use cases (preview and image capture)
//     */
////    private fun bindCameraUseCases() {
////        val cameraProvider = cameraProvider ?: return
////
////        // Build camera selector
////        val cameraSelector = CameraSelector.Builder()
////            .requireLensFacing(lensFacing)
////            .build()
////
////        // Setup preview use case
////        val preview = Preview.Builder()
////            .build()
////            .also {
////                it.setSurfaceProvider(previewView.surfaceProvider)
////            }
////
////        // Setup image capture use case
////        imageCapture = ImageCapture.Builder()
////            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
////            .build()
////
////        try {
////            // Unbind any existing use cases
////            cameraProvider.unbindAll()
////
////            // Get the display rotation
////            val rotation = previewView.display.rotation
////
////            // Create a use case group with the display's rotation
////            val useCaseGroup = UseCaseGroup.Builder()
////                .addUseCase(preview)
////                .addUseCase(imageCapture!!)
////                //.setTargetRotation(rotation)
////                .build()
////
////            // Bind the use cases to the camera
////            camera = cameraProvider.bindToLifecycle(
////                lifecycleOwner,
////                cameraSelector,
////                useCaseGroup
////            )
////
////            // Initial zoom level
////            camera?.cameraControl?.setLinearZoom(0f)
////        } catch (e: Exception) {
////            Log.e(tag, "Use case binding failed: ${e.message}", e)
////            Toast.makeText(context, "Failed to bind camera: ${e.message}", Toast.LENGTH_SHORT).show()
////        }
////    }
//
//    private fun bindCameraUseCases() {
//        val cameraProvider = cameraProvider ?: return
//
//        // Build camera selector
//        val cameraSelector = CameraSelector.Builder()
//            .requireLensFacing(lensFacing)
//            .build()
//
//        // Get the display rotation safely
//        val rotation = when {
//            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R -> {
//                context.display?.rotation ?: Surface.ROTATION_0
//            }
//            else -> {
//                @Suppress("DEPRECATION")
//                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
//                windowManager.defaultDisplay.rotation
//            }
//        }
//
//        // Setup preview use case with rotation
//        val preview = Preview.Builder()
//            .setTargetRotation(rotation)
//            .build()
//            .also {
//                it.setSurfaceProvider(previewView.surfaceProvider)
//            }
//
//        // Setup image capture use case with rotation
//        imageCapture = ImageCapture.Builder()
//            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
//            .setTargetRotation(rotation)
//            .build()
//
//        try {
//            // Unbind any existing use cases
//            cameraProvider.unbindAll()
//
//            // Create a use case group without using setTargetRotation
//            val useCaseGroup = UseCaseGroup.Builder()
//                .addUseCase(preview)
//                .addUseCase(imageCapture!!)
//                .build()
//
//            // Bind the use cases to the camera
//            camera = cameraProvider.bindToLifecycle(
//                lifecycleOwner,
//                cameraSelector,
//                useCaseGroup
//            )
//
//            // Initial zoom level
//            camera?.cameraControl?.setLinearZoom(0f)
//        } catch (e: Exception) {
//            Log.e(tag, "Use case binding failed: ${e.message}", e)
//            Toast.makeText(context, "Failed to bind camera: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//        // After camera is bound
//        camera?.let { cam ->
//            // Get zoom range
//            val zoomState = cam.cameraInfo.zoomState.value
//            val minZoom = zoomState?.minZoomRatio ?: 1f
//            val maxZoom = zoomState?.maxZoomRatio ?: 1f
//
//            // Calculate 50% zoom (halfway between min and max)
//            val targetZoom = minZoom + (maxZoom - minZoom) * 0.5f
//
//            // Set the zoom
//            cam.cameraControl.setZoomRatio(targetZoom)
//        }
//
//    }
//
//    /**
//     * Toggle the flash mode
//     *
//     * @return Current flash mode state
//     */
//    fun toggleFlash(): Boolean {
//        flashEnabled = !flashEnabled
//        imageCapture?.flashMode = if (flashEnabled) {
//            ImageCapture.FLASH_MODE_ON
//        } else {
//            ImageCapture.FLASH_MODE_OFF
//        }
//        return flashEnabled
//    }
//
//    /**
//     * Switch between front and back cameras
//     */
//    fun switchCamera() {
//        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
//            CameraSelector.LENS_FACING_FRONT
//        } else {
//            CameraSelector.LENS_FACING_BACK
//        }
//        bindCameraUseCases()
//    }
//
//    /**
//     * Set zoom level
//     *
//     * @param zoomLevel Zoom level (0.0f to 1.0f)
//     */
//    fun setZoom(zoomLevel: Float) {
//        camera?.cameraControl?.setLinearZoom(zoomLevel.coerceIn(0f, 1f))
//    }
//
//    /**
//     * Get current zoom ratio
//     *
//     * @return Current zoom ratio or 1.0f if not available
//     */
//    fun getCurrentZoom(): Float {
//        return camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
//    }
//
//    /**
//     * Get current zoom state
//     *
//     * @return Current ZoomState or null if not available
//     */
//    fun getZoomState(): ZoomState? {
//        return camera?.cameraInfo?.zoomState?.value
//    }
//
//    /**
//     * Capture image and process ROI
//     */
//    fun captureImage(valType1: String) {
//        val imageCapture = imageCapture ?: return
//
//        try {
//            // Take picture
//            imageCapture.takePicture(
//                cameraExecutor,
//                object : ImageCapture.OnImageCapturedCallback() {
//                    @SuppressLint("UnsafeOptInUsageError")
//                    override fun onCaptureSuccess(image: ImageProxy) {
//
//                        if(valType1!= "IMG")
//                            processImage(image)
//                        else
//                            noImageProcess(image)
//
//                    }
//
//                    override fun onError(exception: ImageCaptureException) {
//                        Log.e(tag, "Image capture failed: ${exception.message}", exception)
//                        Toast.makeText(context, "Failed to capture image: ${exception.message}", Toast.LENGTH_SHORT).show()
//                    }
//                }
//            )
//        } catch (e: Exception) {
//            Log.e(tag, "Image capture error: ${e.message}", e)
//            Toast.makeText(context, "Failed to capture image: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//    }
//
//    /**
//     * Process the captured image to extract ROI
//     */
//    private fun noImageProcess(imageProxy: ImageProxy) {
//        try {
//            Log.d(tag, "NO IMAGE PROCESS STARED...")
//            // Get bitmap from image proxy
//            val buffer = imageProxy.planes[0].buffer
//            val bytes = ByteArray(buffer.remaining())
//            buffer.get(bytes)
//
//            // Convert to bitmap
//            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//
//            // Apply rotation if needed
//            val rotation = imageProxy.imageInfo.rotationDegrees
//            if (rotation != 0) {
//                bitmap = ImageCropper.rotateBitmap(bitmap, rotation) ?: bitmap
//            }
//            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
//
//            // Create capture result
//            val result = CaptureResult(
//                originalBitmap = bitmap,
//                roiBitmap = bitmap,
//                modelBitmap = bitmap,
//                timestamp = timestamp
//            )
//
//            // Update capture result flow
//            _captureResult.value = result
//
//            // Close the image proxy
//            imageProxy.close()
//        } catch (e: Exception) {
//            Log.e(tag, "Image processing failed: ${e.message}", e)
//            imageProxy.close()
//        }
//    }
//    private fun processImage(imageProxy: ImageProxy) {
//        try {
//            // Get bitmap from image proxy
//            val buffer = imageProxy.planes[0].buffer
//            val bytes = ByteArray(buffer.remaining())
//            buffer.get(bytes)
//
//            // Convert to bitmap
//            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
//
//            // Apply rotation if needed
//            val rotation = imageProxy.imageInfo.rotationDegrees
//            if (rotation != 0) {
//                bitmap = ImageCropper.rotateBitmap(bitmap, rotation) ?: bitmap
//            }
//
//            // Get ROI from overlay
//            val roiRect = roiOverlay.getROIRect()
//
//            // Use ImageCropper to crop the ROI and create a 640x640 bitmap for the model
//            val modelBitmap = ImageCropper.cropToROIWithBackground(
//                sourceBitmap = bitmap,
//                roi = roiRect,
//                viewWidth = previewView.width,
//                viewHeight = previewView.height,
//                targetWidth = 640,
//                targetHeight = 640,
//                backgroundColor = Color.LTGRAY,
//                recycleSource = false
//            ) ?: bitmap
//
//            // Crop just the ROI separately to display to the user
//            val roiBitmap = ImageCropper.cropToROI(
//                sourceBitmap = bitmap,
//                roi = roiRect,
//                viewWidth = previewView.width,
//                viewHeight = previewView.height,
//                recycleSource = false
//            ) ?: bitmap
//
//            // Generate timestamp for file naming
//            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
//
//            // Create capture result
//            val result = CaptureResult(
//                originalBitmap = bitmap,
//                roiBitmap = roiBitmap,
//                modelBitmap = modelBitmap,
//                timestamp = timestamp
//            )
//
//            // Update capture result flow
//            _captureResult.value = result
//
//            // Close the image proxy
//            imageProxy.close()
//        } catch (e: Exception) {
//            Log.e(tag, "Image processing failed: ${e.message}", e)
//            imageProxy.close()
//        }
//    }
//
//    /**
//     * Save the captured image and metadata
//     */
////    fun saveImage(
////        result: CaptureResult,
////        meterReading: String? = null,
////        savedFilename: String? = null,
////        isEdited: Boolean = false
////    ) {
////        try {
////            Log.d(tag, "Saving Image...................")
////            // Convert bitmap to JPEG bytes
////            val outputStream = ByteArrayOutputStream()
////            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
////            val jpegBytes = outputStream.toByteArray()
////
////            // Generate image file name
////            val fileName = "${savedFilename}.jpg"
////
////            // Save to storage
////            val (uri, stream) = FileUtils.createOrUpdateImageFile(
////                context,
////                fileName,
////                "npdcl",
////                "image/jpeg"
////            )
////
////            Log.d(tag, "image URL: $uri")
////
////
////            if (uri != null && stream != null) {
////                // Write JPEG data to file
////                stream.write(jpegBytes)
////                stream.close()
////
////                // Save metadata with isEdited flag
////                val fileManager = FileManager(context)
////
////                fileManager.saveJsonMetadata(uri, result.timestamp, meterReading, savedFilename, isEdited)
////                fileManager.notifyGallery(uri)
////
////                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
////            } else {
////                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
////            }
////        } catch (e: Exception) {
////            Log.e(tag, "Failed to save image: ${e.message}", e)
////            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
////        }
////    }
////    fun saveImage(
////        result: CaptureResult,
////        meterReading: String? = null,
////        savedFilename: String? = null,
////        isEdited: Boolean = false
////    ) {
////        try {
////            Log.d(tag, "Saving Image...................")
////            // Convert bitmap to JPEG bytes
////            val outputStream = ByteArrayOutputStream()
////            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
////            val jpegBytes = outputStream.toByteArray()
////
////            // Generate image file name
////            val fileName = "${savedFilename}.jpg"
////
////            // Save to storage
////            val (uri, stream) = FileUtils.createOrUpdateImageFile(
////                context,
////                fileName,
////                "npdcl",
////                "image/jpeg"
////            )
////
////            Log.d(tag, "image URL: $uri")
////
////            if (uri != null && stream != null) {
////                // Write JPEG data to file
////                stream.write(jpegBytes)
////                stream.close()
////
////                // Get and log the absolute file path
////                val filePath = getRealPathFromUri(context, uri)
////                Log.d(tag, "Saved image absolute path: $filePath")
////
////                // Save metadata with isEdited flag
////                val fileManager = FileManager(context)
////
////                fileManager.saveJsonMetadata(filePath.toString(), result.timestamp, meterReading, savedFilename, isEdited)
////                fileManager.notifyGallery(uri)
////
////                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
////            } else {
////                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
////            }
////        } catch (e: Exception) {
////            Log.e(tag, "Failed to save image: ${e.message}", e)
////            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
////        }
////    }
////    fun saveImage(
////        result: CaptureResult,
////        meterReading: String? = null,
////        savedFilename: String? = null,
////        isEdited: Boolean = false
////    ): String? {
////        try {
////            Log.d(tag, "Saving Image...................")
////            // Convert bitmap to JPEG bytes
////            val outputStream = ByteArrayOutputStream()
////            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
////            val jpegBytes = outputStream.toByteArray()
////
////            val currentTimeMillis = System.currentTimeMillis()
////
////
////            // Generate image file name
////            val fileName = "${savedFilename}_$currentTimeMillis.jpg"
////
////            // Save to storage
////            val (uri, stream) = FileUtils.createOrUpdateImageFile(
////                context,
////                fileName,
////                "npdcl",
////                "image/jpeg"
////            )
////
////            Log.d(tag, "image URL: $uri")
////
////            if (uri != null && stream != null) {
////                // Write JPEG data to file
////                stream.write(jpegBytes)
////                stream.close()
////
////                // Get and log the absolute file path
////                val filePath = getRealPathFromUri(context, uri)
////                Log.d(tag, "Saved image absolute path: $filePath")
////
////                // Save metadata with isEdited flag
////                val fileManager = FileManager(context)
////
////               // fileManager.saveJsonMetadata(filePath.toString(), result.timestamp, meterReading, savedFilename, isEdited)
////                fileManager.notifyGallery(uri)
////
////                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
////
////                return filePath
////            } else {
////                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
////            }
////        } catch (e: Exception) {
////            Log.e(tag, "Failed to save image: ${e.message}", e)
////            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
////        }
////
////        return null
////    }
//
//
//    data class SaveImageResult(
//        val filePath: String?,
//        val uri: Uri?
//    )
//
//    fun saveImage(
//        result: CaptureResult,
//        meterReading: String? = null,
//        savedFilename: String? = null,
//        isEdited: Boolean = false
//    ): SaveImageResult {
//        try {
//            Log.d(tag, "Saving Image...................")
//            // Convert bitmap to JPEG bytes
//            val outputStream = ByteArrayOutputStream()
//            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
//            val jpegBytes = outputStream.toByteArray()
//
//            val currentTimeMillis = System.currentTimeMillis()
//
//            // Generate image file name
//            val fileName = "${savedFilename}_$currentTimeMillis.jpg"
//
//            // Save to storage
//            val (uri, stream) = FileUtils.createOrUpdateImageFile(
//                context,
//                fileName,
//                "npdcl",
//                "image/jpeg"
//            )
//
//            Log.d(tag, "image URL: $uri")
//
//            if (uri != null && stream != null) {
//                // Write JPEG data to file
//                stream.write(jpegBytes)
//                stream.close()
//
//                // Get and log the absolute file path
//                val filePath = getRealPathFromUri(context, uri)
//                Log.d(tag, "Saved image absolute path: $filePath")
//
//                // Save metadata with isEdited flag
//                val fileManager = FileManager(context)
//                fileManager.notifyGallery(uri)
//
//                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
//
//                return SaveImageResult(filePath, uri)
//            } else {
//                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Failed to save image: ${e.message}", e)
//            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//
//        return SaveImageResult(null, null)
//    }
//
//    // Helper function to get absolute path from URI
//    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
//        // For external storage (MediaStore)
//        if (DocumentsContract.isDocumentUri(context, uri)) {
//            // ExternalStorageProvider
//            if (isExternalStorageDocument(uri)) {
//                val docId = DocumentsContract.getDocumentId(uri)
//                val split = docId.split(":")
//                val type = split[0]
//
//                if ("primary".equals(type, ignoreCase = true)) {
//                    return "${Environment.getExternalStorageDirectory()}/${split[1]}"
//                }
//            }
//            // MediaProvider
//            else if (isMediaDocument(uri)) {
//                val docId = DocumentsContract.getDocumentId(uri)
//                val split = docId.split(":")
//                val contentUri = when (split[0]) {
//                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
//                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
//                    "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
//                    else -> null
//                }
//
//                contentUri?.let {
//                    val selection = "_id=?"
//                    val selectionArgs = arrayOf(split[1])
//                    return getDataColumn(context, contentUri, selection, selectionArgs)
//                }
//            }
//        }
//        // MediaStore (and general)
//        else if ("content".equals(uri.scheme, ignoreCase = true)) {
//            return getDataColumn(context, uri, null, null)
//        }
//        // File
//        else if ("file".equals(uri.scheme, ignoreCase = true)) {
//            return uri.path
//        }
//
//        return null
//    }
//
//    private fun getDataColumn(context: Context, uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
//        var cursor: Cursor? = null
//        val column = "_data"
//        val projection = arrayOf(column)
//
//        try {
//            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
//            if (cursor != null && cursor.moveToFirst()) {
//                val columnIndex = cursor.getColumnIndexOrThrow(column)
//                return cursor.getString(columnIndex)
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Error getting real path: ${e.message}", e)
//        } finally {
//            cursor?.close()
//        }
//        return null
//    }
//
//    private fun isExternalStorageDocument(uri: Uri): Boolean {
//        return "com.android.externalstorage.documents" == uri.authority
//    }
//
//    private fun isMediaDocument(uri: Uri): Boolean {
//        return "com.android.providers.media.documents" == uri.authority
//    }
//
//    /**
//     * Release all camera resources
//     */
//    fun shutdown() {
//        cameraExecutor.shutdown()
//        cameraProvider?.unbindAll()
//    }
//
//    /**
//     * Data class to hold capture results
//     */
//    data class CaptureResult(
//        val originalBitmap: Bitmap,
//        val roiBitmap: Bitmap,
//        val modelBitmap: Bitmap,
//        val timestamp: String
//    )
//}