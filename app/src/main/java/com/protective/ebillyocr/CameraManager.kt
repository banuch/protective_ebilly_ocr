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
import android.view.ScaleGestureDetector
import android.view.Surface
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * CameraManager handles all camera operations for meter detection
 *
 * This class manages camera preview, capture, and ROI-based cropping.
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
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Camera settings
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashEnabled = false

    // Capture result flow
    private val _captureResult = MutableStateFlow<CaptureResult?>(null)
    val captureResult: StateFlow<CaptureResult?> = _captureResult

    // Scale detector for zoom functionality
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    /**
     * Initialize camera and setup the preview
     */
    fun initialize() {
        Log.d(tag, "Initializing camera")

        // Initialize scale gesture detector for zoom
        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Get current zoom ratio
                val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                // Calculate new zoom ratio
                val delta = detector.scaleFactor
                camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })

        // Ensure the preview view handles scale gestures
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
     * Bind camera use cases (preview and image capture)
     */
//    private fun bindCameraUseCases() {
//        val cameraProvider = cameraProvider ?: return
//
//        // Build camera selector
//        val cameraSelector = CameraSelector.Builder()
//            .requireLensFacing(lensFacing)
//            .build()
//
//        // Setup preview use case
//        val preview = Preview.Builder()
//            .build()
//            .also {
//                it.setSurfaceProvider(previewView.surfaceProvider)
//            }
//
//        // Setup image capture use case
//        imageCapture = ImageCapture.Builder()
//            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
//            .build()
//
//        try {
//            // Unbind any existing use cases
//            cameraProvider.unbindAll()
//
//            // Get the display rotation
//            val rotation = previewView.display.rotation
//
//            // Create a use case group with the display's rotation
//            val useCaseGroup = UseCaseGroup.Builder()
//                .addUseCase(preview)
//                .addUseCase(imageCapture!!)
//                //.setTargetRotation(rotation)
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
//    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        // Build camera selector
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        // Get the display rotation safely
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

        // Setup preview use case with rotation
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Setup image capture use case with rotation
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        try {
            // Unbind any existing use cases
            cameraProvider.unbindAll()

            // Create a use case group without using setTargetRotation
            val useCaseGroup = UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(imageCapture!!)
                .build()

            // Bind the use cases to the camera
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                useCaseGroup
            )

            // Initial zoom level
            camera?.cameraControl?.setLinearZoom(0f)
        } catch (e: Exception) {
            Log.e(tag, "Use case binding failed: ${e.message}", e)
            Toast.makeText(context, "Failed to bind camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        // After camera is bound
        camera?.let { cam ->
            // Get zoom range
            val zoomState = cam.cameraInfo.zoomState.value
            val minZoom = zoomState?.minZoomRatio ?: 1f
            val maxZoom = zoomState?.maxZoomRatio ?: 1f

            // Calculate 50% zoom (halfway between min and max)
            val targetZoom = minZoom + (maxZoom - minZoom) * 0.5f

            // Set the zoom
            cam.cameraControl.setZoomRatio(targetZoom)
        }

    }

    /**
     * Toggle the flash mode
     *
     * @return Current flash mode state
     */
    fun toggleFlash(): Boolean {
        flashEnabled = !flashEnabled
        imageCapture?.flashMode = if (flashEnabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
        return flashEnabled
    }

    /**
     * Switch between front and back cameras
     */
    fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    /**
     * Set zoom level
     *
     * @param zoomLevel Zoom level (0.0f to 1.0f)
     */
    fun setZoom(zoomLevel: Float) {
        camera?.cameraControl?.setLinearZoom(zoomLevel.coerceIn(0f, 1f))
    }

    /**
     * Get current zoom ratio
     *
     * @return Current zoom ratio or 1.0f if not available
     */
    fun getCurrentZoom(): Float {
        return camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1.0f
    }

    /**
     * Get current zoom state
     *
     * @return Current ZoomState or null if not available
     */
    fun getZoomState(): ZoomState? {
        return camera?.cameraInfo?.zoomState?.value
    }

    /**
     * Capture image and process ROI
     */
    fun captureImage(valType1: String) {
        val imageCapture = imageCapture ?: return

        try {
            // Take picture
            imageCapture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    @SuppressLint("UnsafeOptInUsageError")
                    override fun onCaptureSuccess(image: ImageProxy) {

                        if(valType1!= "IMG")
                            processImage(image)
                        else
                            noImageProcess(image)

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
     * Process the captured image to extract ROI
     */
    private fun noImageProcess(imageProxy: ImageProxy) {
        try {
            Log.d(tag, "NO IMAGE PROCESS STARED...")
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
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

            // Create capture result
            val result = CaptureResult(
                originalBitmap = bitmap,
                roiBitmap = bitmap,
                modelBitmap = bitmap,
                timestamp = timestamp
            )

            // Update capture result flow
            _captureResult.value = result

            // Close the image proxy
            imageProxy.close()
        } catch (e: Exception) {
            Log.e(tag, "Image processing failed: ${e.message}", e)
            imageProxy.close()
        }
    }
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

            // Use ImageCropper to crop the ROI and create a 640x640 bitmap for the model
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

            // Crop just the ROI separately to display to the user
            val roiBitmap = ImageCropper.cropToROI(
                sourceBitmap = bitmap,
                roi = roiRect,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                recycleSource = false
            ) ?: bitmap

            // Generate timestamp for file naming
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())

            // Create capture result
            val result = CaptureResult(
                originalBitmap = bitmap,
                roiBitmap = roiBitmap,
                modelBitmap = modelBitmap,
                timestamp = timestamp
            )

            // Update capture result flow
            _captureResult.value = result

            // Close the image proxy
            imageProxy.close()
        } catch (e: Exception) {
            Log.e(tag, "Image processing failed: ${e.message}", e)
            imageProxy.close()
        }
    }

    /**
     * Save the captured image and metadata
     */
//    fun saveImage(
//        result: CaptureResult,
//        meterReading: String? = null,
//        savedFilename: String? = null,
//        isEdited: Boolean = false
//    ) {
//        try {
//            Log.d(tag, "Saving Image...................")
//            // Convert bitmap to JPEG bytes
//            val outputStream = ByteArrayOutputStream()
//            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
//            val jpegBytes = outputStream.toByteArray()
//
//            // Generate image file name
//            val fileName = "${savedFilename}.jpg"
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
//
//            if (uri != null && stream != null) {
//                // Write JPEG data to file
//                stream.write(jpegBytes)
//                stream.close()
//
//                // Save metadata with isEdited flag
//                val fileManager = FileManager(context)
//
//                fileManager.saveJsonMetadata(uri, result.timestamp, meterReading, savedFilename, isEdited)
//                fileManager.notifyGallery(uri)
//
//                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
//            } else {
//                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Failed to save image: ${e.message}", e)
//            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//    }
//    fun saveImage(
//        result: CaptureResult,
//        meterReading: String? = null,
//        savedFilename: String? = null,
//        isEdited: Boolean = false
//    ) {
//        try {
//            Log.d(tag, "Saving Image...................")
//            // Convert bitmap to JPEG bytes
//            val outputStream = ByteArrayOutputStream()
//            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
//            val jpegBytes = outputStream.toByteArray()
//
//            // Generate image file name
//            val fileName = "${savedFilename}.jpg"
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
//
//                fileManager.saveJsonMetadata(filePath.toString(), result.timestamp, meterReading, savedFilename, isEdited)
//                fileManager.notifyGallery(uri)
//
//                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
//            } else {
//                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Failed to save image: ${e.message}", e)
//            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//    }
//    fun saveImage(
//        result: CaptureResult,
//        meterReading: String? = null,
//        savedFilename: String? = null,
//        isEdited: Boolean = false
//    ): String? {
//        try {
//            Log.d(tag, "Saving Image...................")
//            // Convert bitmap to JPEG bytes
//            val outputStream = ByteArrayOutputStream()
//            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
//            val jpegBytes = outputStream.toByteArray()
//
//            val currentTimeMillis = System.currentTimeMillis()
//
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
//
//               // fileManager.saveJsonMetadata(filePath.toString(), result.timestamp, meterReading, savedFilename, isEdited)
//                fileManager.notifyGallery(uri)
//
//                Toast.makeText(context, "Image saved successfully", Toast.LENGTH_SHORT).show()
//
//                return filePath
//            } else {
//                Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: Exception) {
//            Log.e(tag, "Failed to save image: ${e.message}", e)
//            Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//
//        return null
//    }


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
            Log.d(tag, "Saving Image...................")
            // Convert bitmap to JPEG bytes
            val outputStream = ByteArrayOutputStream()
            result.modelBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val jpegBytes = outputStream.toByteArray()

            val currentTimeMillis = System.currentTimeMillis()

            // Generate image file name
            val fileName = "${savedFilename}_$currentTimeMillis.jpg"

            // Save to storage
            val (uri, stream) = FileUtils.createOrUpdateImageFile(
                context,
                fileName,
                "npdcl",
                "image/jpeg"
            )

            Log.d(tag, "image URL: $uri")

            if (uri != null && stream != null) {
                // Write JPEG data to file
                stream.write(jpegBytes)
                stream.close()

                // Get and log the absolute file path
                val filePath = getRealPathFromUri(context, uri)
                Log.d(tag, "Saved image absolute path: $filePath")

                // Save metadata with isEdited flag
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

    // Helper function to get absolute path from URI
    private fun getRealPathFromUri(context: Context, uri: Uri): String? {
        // For external storage (MediaStore)
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                val docId = DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                val type = split[0]

                if ("primary".equals(type, ignoreCase = true)) {
                    return "${Environment.getExternalStorageDirectory()}/${split[1]}"
                }
            }
            // MediaProvider
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
        // MediaStore (and general)
        else if ("content".equals(uri.scheme, ignoreCase = true)) {
            return getDataColumn(context, uri, null, null)
        }
        // File
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
     * Release all camera resources
     */
    fun shutdown() {
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll()
    }

    /**
     * Data class to hold capture results
     */
    data class CaptureResult(
        val originalBitmap: Bitmap,
        val roiBitmap: Bitmap,
        val modelBitmap: Bitmap,
        val timestamp: String
    )
}