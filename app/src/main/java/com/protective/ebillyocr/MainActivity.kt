package com.protective.ebillyocr

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.content.ContentResolver
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.RelativeSizeSpan
import android.graphics.Typeface
import androidx.appcompat.app.AlertDialog
import kotlin.math.sqrt

/**
 * Enhanced MainActivity for Meter Reading OCR Application with Exposure Control
 * Default processing mode: COLOR (not grayscale)
 *
 * Features:
 * - Camera capture with ROI overlay
 * - Advanced exposure control with real-time adjustment
 * - OCR processing with multiple enhancement modes
 * - Manual input capability
 * - Adaptive image processing
 * - Enhanced error handling and logging
 * - Comprehensive metadata tracking
 */
class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {

    companion object {
        private const val TAG = "MainActivity_OCR"

        private const val MAX_READING_LENGTH = 20
        private const val TAP_COUNT_THRESHOLD = 3
        private const val PROCESSING_DELAY_MS = 1000L

        // Processing Settings
        private const val DEFAULT_USE_COLOR_PROCESSING = true  // Changed to COLOR default

        // Result Codes
        const val OCR_KWH_RESULT_CODE = 666
        const val OCR_KVAH_RESULT_CODE = 667
        const val OCR_RMD_RESULT_CODE = 668
        const val OCR_LT_RESULT_CODE = 669
        const val OCR_IMG_RESULT_CODE = 770
        const val OCR_SKWH_RESULT_CODE = 771
        const val OCR_SKVAH_RESULT_CODE = 772
        const val OCR_INVALID_RESULT_CODE = 773
    }

    // ===========================================
    // UI Components - Using Existing Layout Structure
    // ===========================================

    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay: ROIOverlay
    private lateinit var captureButton: Button
    private lateinit var flashButton: ImageButton
    private lateinit var switchButton: ImageButton
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var exposureSeekBar: SeekBar
    private lateinit var progressBar: ProgressBar
    private lateinit var decimalCheckBox: CheckBox
    private lateinit var resultLayout: LinearLayout
    private lateinit var resultImageView: ImageView
    private lateinit var readingTextView: TextView
    private lateinit var decimaltextView: TextView
    private lateinit var titleTextView: TextView

    private lateinit var serviceIdTextView: TextView
    private lateinit var valueTypeTextView: TextView
    private lateinit var resultServiceIdTextView: TextView
    private lateinit var resultValueTypeTextView: TextView

    // Control panel components
    private lateinit var controlPanel: LinearLayout
    private lateinit var controlsContainer: androidx.constraintlayout.widget.ConstraintLayout

    private lateinit var saveButton: Button
    private lateinit var retakeButton: Button
    private lateinit var processButton: Button

    // ===========================================
    // Core Components
    // ===========================================

    private lateinit var permissionManager: PermissionManager
    private lateinit var cameraManager: CameraManager
    private lateinit var meterDetector: MeterDetector
    private lateinit var appConfig: AppConfig

    // ===========================================
    // State Variables
    // ===========================================

    private var currentState: AppState = AppState.Camera
    private var currentCaptureResult: CameraManager.CaptureResult? = null
    private var currentMeterReading: String? = null
    private val inputNumber = StringBuilder()
    private var tapCount = 0
    private var retakeCount = 0
    private var decimalPosition = 0 // Track current decimal position
    private var editFlag = false
    private var originalReadingValue: String = ""

    // Image Processing State - DEFAULT TO COLOR PROCESSING
    private var isProcessingInGrayscale = !DEFAULT_USE_COLOR_PROCESSING  // false = color processing
    private var isGrayscaleDisplayMode = false
    private var originalResultBitmap: Bitmap? = null
    private var processedGrayscaleBitmap: Bitmap? = null

    // ===========================================
    // Feature Configuration
    // ===========================================

    private object FeatureFlags {
        const val ENABLE_GRAYSCALE_TOGGLE = true
        const val ENABLE_MANUAL_INPUT = true
        const val ENABLE_ADAPTIVE_PROCESSING = true
        const val ENABLE_ENHANCED_LOGGING = true
        const val ENABLE_PROCESSING_OPTIONS = true
        const val ENABLE_EXPOSURE_CONTROL = true
    }

    // ===========================================
    // Data Classes
    // ===========================================

    data class AppConfig(
        val serviceId: String,
        val valType: String,
        val savedFileName: String,
        val editEnable: Boolean,
        val useColorProcessing: Boolean = DEFAULT_USE_COLOR_PROCESSING
    ) {
        companion object {
            fun fromIntent(intent: Intent): AppConfig {
                val dataHandler = IntentDataHandler(intent)
                val serviceId = dataHandler.getServiceId()
                val valType = dataHandler.getValType()
                val isEditEnabled = dataHandler.isEditEnabled()
                val useColor =
                    intent.getBooleanExtra("use_color_processing", DEFAULT_USE_COLOR_PROCESSING)

                return AppConfig(
                    serviceId = serviceId,
                    valType = valType,
                    savedFileName = "${serviceId}_${valType}",
                    editEnable = isEditEnabled,
                    useColorProcessing = useColor
                )
            }
        }
    }

    sealed class AppState {
        object Camera : AppState()
        data class Processing(val message: String) : AppState()
        data class Result(val captureResult: CameraManager.CaptureResult) : AppState()
        data class Error(val message: String) : AppState()
    }

    enum class GrayscaleEnhancement {
        SIMPLE,
        CONTRAST,
        HIGH_CONTRAST,
        LUMINANCE_WEIGHTED
    }

    // ===========================================
    // Lifecycle Methods
    // ===========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Set orientation to portrait
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Check date range for app activation
        val startDate = "2025-02-01"
        val endDate = "2025-09-30"

        AppLogger.d("App activation period: $startDate to $endDate")
        logAutoRotationStatus()

        if (DateUtils.isWithinDateRange(startDate, endDate)) {
            try {
                initializeApp()
                AppLogger.d("App initialized successfully within activation period")
            } catch (e: Exception) {
                AppLogger.e("Failed to initialize app", e)
                showError("Failed to initialize app: ${e.message}")
                finish()
            }
        } else {
            showActivationExpiredMessage()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.d("Activity destroyed - cleaning up resources")
        cleanupResources()
    }

    // ===========================================
    // Initialization Methods
    // ===========================================

    private fun initializeApp() {
        AppLogger.d("Initializing application components")

        // Initialize configuration from intent
        appConfig = AppConfig.fromIntent(intent)
        AppLogger.d("App configuration loaded: $appConfig")

        // Set processing mode based on configuration
        isProcessingInGrayscale = !appConfig.useColorProcessing

        // Initialize UI components
        initializeViews()
        updateConfigurationUI()
        setupAccessibility()

        // Initialize managers
        initializeManagers()

        // Request necessary permissions
        permissionManager.requestPermissions()
    }

    private fun initializeViews() {
        AppLogger.d("Initializing UI components with existing layout structure")

        // Camera preview components
        previewView = findViewById(R.id.viewFinder)
        roiOverlay = findViewById(R.id.roiOverlay)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)
        switchButton = findViewById(R.id.switchButton)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)
        exposureSeekBar = findViewById(R.id.exposureSeekBar)
        progressBar = findViewById(R.id.progressBar)

        // Service ID and Value Type displays
        serviceIdTextView = findViewById(R.id.serviceIdTextView)
        valueTypeTextView = findViewById(R.id.valueTypeTextView)

        // Control panels
        controlPanel = findViewById(R.id.controlPanel)
        controlsContainer = findViewById(R.id.controlsContainer)

        // Result view components
        resultLayout = findViewById(R.id.resultLayout)
        resultImageView = findViewById(R.id.resultImageView)
        readingTextView = findViewById(R.id.readingTextView)
        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
        saveButton = findViewById(R.id.saveButton)
        retakeButton = findViewById(R.id.retakeButton)
        processButton = findViewById(R.id.processButton)
        titleTextView = findViewById(R.id.titleTextView)
        decimalCheckBox = findViewById(R.id.decimalCheckBox)
        decimaltextView = findViewById(R.id.decimalTextbox)

        setupClickListeners()
        setupSeekBarListeners()
        setupDecimalCheckbox()

        // Set title with version
        titleTextView.text = "Ebilly OCR ${getVersionName()}"
    }

    private fun setupDecimalCheckbox() {
        decimalCheckBox.setOnCheckedChangeListener { _, isChecked ->
            val currentText = readingTextView.text.toString()

            if (isChecked) {
                // Add decimal point one digit from right
                addDecimalToReading(currentText)
            } else {
                // Remove decimal point
                removeDecimalFromReading(currentText)
            }
        }
    }

    private fun showEditEnabledDialog() {
        try {
            val message = SpannableString("Edit Mode enabled")
            message.setSpan(StyleSpan(Typeface.BOLD), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            message.setSpan(RelativeSizeSpan(1.8f), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val dialog = AlertDialog.Builder(this)
                .setTitle("Information")
                .setMessage(message)
                .setCancelable(false)
                .create()

            dialog.show()

            lifecycleScope.launch {
                delay(3000)
                if (dialog.isShowing && !isDestroyed) {
                    dialog.dismiss()
                }
            }

            AppLogger.d("Edit enabled dialog displayed for 3 seconds")

        } catch (e: Exception) {
            AppLogger.e("Failed to show edit enabled dialog", e)
            showToast("Edit enabled")
        }
    }

    private fun addDecimalFromRight(reading: String) {
        // Skip if reading is empty or too short
        if (reading.isEmpty() || reading.length < 2) {
            return
        }

        // Remove existing decimal if present
        val cleanReading = reading.replace(".", "")

        // Store original value if not already stored
        if (originalReadingValue.isEmpty()) {
            originalReadingValue = cleanReading
        }

        // Calculate decimal position from right (cycles through valid positions)
        val maxPosition = cleanReading.length - 1
        decimalPosition = if (decimalPosition >= maxPosition) 1 else decimalPosition + 1

        // Insert decimal at position from right
        val insertPosition = cleanReading.length - decimalPosition
        val withDecimal = cleanReading.substring(0, insertPosition) + "." +
                cleanReading.substring(insertPosition)

        readingTextView.text = withDecimal
        currentMeterReading = withDecimal
    }

    private fun addDecimalToReading(reading: String) {
        // Skip if reading is empty, too short, or already has decimal
        if (reading.isEmpty() || reading.length < 2 || reading.contains(".")) {
            return
        }

        // Store original value if not already stored
        if (originalReadingValue.isEmpty()) {
            originalReadingValue = reading
        }

        // Add decimal point one digit from right
        // Example: "12345" becomes "1234.5"
        val withDecimal = reading.substring(0, reading.length - 1) + "." + reading.last()
        readingTextView.text = withDecimal
        currentMeterReading = withDecimal
    }

    private fun removeDecimalFromReading(reading: String) {
        // If has decimal, remove it
        if (reading.contains(".")) {
            val withoutDecimal = reading.replace(".", "")
            readingTextView.text = withoutDecimal
            currentMeterReading = readingTextView.text.toString()
        } else if (originalReadingValue.isNotEmpty()) {
            // Restore original value
            readingTextView.text = originalReadingValue
        }

        // Clear stored original value
        originalReadingValue = ""
    }

    // Call this method when you update the reading value from OCR/processing
    fun updateReadingValue(newReading: String) {
        // Clear stored original value when new reading comes in
        originalReadingValue = ""

        // Update the text view
        readingTextView.text = newReading

        // If decimal checkbox is checked, apply decimal formatting
        if (decimalCheckBox.isChecked) {
            addDecimalToReading(newReading)
        }
    }

    // Helper method to get the current reading value (with or without decimal)
    fun getCurrentReadingValue(): String {
        return readingTextView.text.toString()
    }

    // Helper method to get the numeric reading value without decimal
    fun getNumericReadingValue(): String {
        val currentText = readingTextView.text.toString()
        return if (currentText.contains(".")) {
            currentText.replace(".", "")
        } else {
            currentText
        }
    }

    private fun setupClickListeners() {

        decimaltextView.setOnClickListener {
            if (retakeCount >= 1) {
                val currentText = readingTextView.text.toString()
                addDecimalFromRight(currentText)
            } else {
                showToast("Click the Process Button first")
            }
        }

        captureButton.setOnClickListener {
            AppLogger.logUserAction("capture_clicked", mapOf("valType" to appConfig.valType))
            captureImage()
            AppLogger.d("Retake Count: $retakeCount")
        }

        flashButton.setOnClickListener {
            AppLogger.logUserAction("flash_toggled")
            toggleFlash()
        }

        switchButton.setOnClickListener {
            AppLogger.logUserAction("camera_switched")
            switchCamera()
        }

        if (FeatureFlags.ENABLE_GRAYSCALE_TOGGLE) {
            resultImageView.setOnClickListener {
                AppLogger.logUserAction("display_mode_toggled")
                toggleDisplayMode()
            }
        }

        saveButton.setOnClickListener {
            AppLogger.logUserAction("save_clicked")
            saveCurrentImage()
        }

        if (FeatureFlags.ENABLE_MANUAL_INPUT) {
            readingTextView.setOnClickListener {
                handleReadingTextTap()
            }
        }

        retakeButton.setOnClickListener {
            AppLogger.logUserAction("retake_clicked")
            updateUIState(AppState.Camera)
        }

        processButton.setOnClickListener {
            AppLogger.logUserAction("process_clicked")
            retakeCount++

            if (retakeCount == 3) {
                showEditEnabledDialog()
            }

            processCurrentImage()
        }

        // Long click for processing options
        if (FeatureFlags.ENABLE_PROCESSING_OPTIONS) {
            processButton.setOnLongClickListener {
                showProcessingOptionsDialog()
                true
            }
        }

        // Long press on exposure seekbar to reset exposure
        if (FeatureFlags.ENABLE_EXPOSURE_CONTROL) {
            exposureSeekBar.setOnLongClickListener {
                resetExposure()
                true
            }
        }
    }

    private fun setupSeekBarListeners() {
        // Zoom seek bar (existing functionality)
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::cameraManager.isInitialized) {
                    val zoomLevel = progress / 100f
                    cameraManager.setZoom(zoomLevel)
                    AppLogger.d("Zoom adjusted to: $progress% ($zoomLevel)")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                AppLogger.logUserAction("zoom_adjustment_started")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalZoom = seekBar?.progress ?: 50
                AppLogger.logUserAction("zoom_adjustment_completed", mapOf("final_value" to finalZoom))
            }
        })

        // Enhanced exposure seek bar with proper functionality
        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && ::cameraManager.isInitialized) {
                    // Convert 0-100 to exposure percentage
                    cameraManager.setExposurePercentage(progress)

                    // Show immediate feedback
                    showExposureFeedback(progress)
                    AppLogger.d("Exposure adjusted to: $progress%")
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                AppLogger.logUserAction("exposure_adjustment_started")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val finalExposure = seekBar?.progress ?: 50
                AppLogger.logUserAction("exposure_adjustment_completed",
                    mapOf("final_value" to finalExposure))
            }
        })
    }

    /**
     * Show exposure feedback to user
     */
    private fun showExposureFeedback(progress: Int) {
        if (::cameraManager.isInitialized) {
            val exposureInfo = cameraManager.getExposureInfo()
            // Log exposure info - can be shown in UI if needed
            AppLogger.d("Current exposure: $exposureInfo")
        }
    }

    /**
     * Reset exposure to middle position (auto)
     */
    private fun resetExposure() {
        if (::cameraManager.isInitialized) {
            exposureSeekBar.progress = 50
            cameraManager.setExposurePercentage(50)
            showToast("Exposure reset to auto")
            AppLogger.logUserAction("exposure_reset")
        }
    }

    private fun initializeManagers() {
        permissionManager = PermissionManager(this, this)
        meterDetector = MeterDetector(this)
    }

    private fun initializeCamera() {
        AppLogger.d("Initializing camera with exposure control")

        try {
            cameraManager = CameraManager(this, this, previewView, roiOverlay)

            // Observe camera capture results
            lifecycleScope.launch {
                cameraManager.captureResult.collectLatest { result ->
                    result?.let {
                        currentCaptureResult = it
                        updateUIState(AppState.Result(it))
                    }
                }
            }

            // Observe exposure state changes
            if (FeatureFlags.ENABLE_EXPOSURE_CONTROL) {
                lifecycleScope.launch {
                    cameraManager.exposureState.collectLatest { exposureState ->
                        updateExposureUI(exposureState)
                    }
                }
            }

            cameraManager.initialize()

            // Set initial camera settings after camera is initialized
            if (::cameraManager.isInitialized) {
                // Set initial exposure to middle (50%)
                exposureSeekBar.progress = 50

                // Set initial zoom to 50%
                zoomSeekBar.progress = 50
            }

            AppLogger.d("Camera initialized with exposure control")
        } catch (e: Exception) {
            AppLogger.e("Failed to initialize camera", e)
            showError("Failed to initialize camera: ${e.message}")
        }
    }

    /**
     * Update exposure UI based on camera exposure state
     */
    private fun updateExposureUI(exposureState: CameraManager.ExposureState) {
        // Update seek bar based on exposure support
        if (exposureState.isSupported) {
            exposureSeekBar.isEnabled = true
            exposureSeekBar.alpha = 1.0f

            // Sync seekbar with actual exposure value
            val percentage = if (exposureState.maxExposureCompensation > exposureState.minExposureCompensation) {
                val range = exposureState.maxExposureCompensation - exposureState.minExposureCompensation
                ((exposureState.exposureCompensation - exposureState.minExposureCompensation) * 100 / range)
            } else {
                50
            }

            if (Math.abs(exposureSeekBar.progress - percentage) > 2) { // Avoid feedback loops
                exposureSeekBar.progress = percentage
            }

            AppLogger.d("Exposure UI updated - supported: true, percentage: $percentage")
        } else {
            exposureSeekBar.isEnabled = false
            exposureSeekBar.alpha = 0.5f
            AppLogger.d("Exposure control not supported on this device")
        }
    }

    // ===========================================
    // Camera Operations
    // ===========================================

    private fun captureImage() {
        if (!::cameraManager.isInitialized) {
            showError("Camera not initialized")
            return
        }

        try {
            // Log current camera settings including exposure
            val exposureInfo = if (::cameraManager.isInitialized) {
                cameraManager.getExposureInfo()
            } else {
                "N/A"
            }
            val zoomRatio = cameraManager.getCurrentZoom()

            AppLogger.d("Capturing image with settings - $exposureInfo, Zoom: $zoomRatio")

            progressBar.visibility = View.VISIBLE
            captureButton.isEnabled = false
            cameraManager.captureImage(appConfig.valType)
        } catch (e: Exception) {
            AppLogger.e("Capture failed", e)
            showError("Failed to capture image: ${e.message}")
            resetCaptureUI()
        }
    }

    private fun toggleFlash() {
        if (!::cameraManager.isInitialized) return

        try {
            val flashOn = cameraManager.toggleFlash()
            flashButton.setImageResource(
                if (flashOn) R.drawable.ic_flash_on
                else R.drawable.ic_flash_off
            )

            AppLogger.d("Flash ${if (flashOn) "enabled" else "disabled"}")
        } catch (e: Exception) {
            AppLogger.e("Flash toggle failed", e)
            showError("Failed to toggle flash")
        }
    }

    private fun switchCamera() {
        if (!::cameraManager.isInitialized) return

        try {
            cameraManager.switchCamera()
            AppLogger.d("Camera switched - exposure settings preserved")
        } catch (e: Exception) {
            AppLogger.e("Camera switch failed", e)
            showError("Failed to switch camera")
        }
    }

    // ===========================================
    // Image Processing Methods
    // ===========================================

    private fun processCurrentImage() {
        val result = currentCaptureResult ?: return

        // Update UI for processing state
        processButton.isEnabled = false
        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        readingTextView.text = "Processing..."

        lifecycleScope.launch {
            try {
                // Log capture metadata for analysis
                AppLogger.d("Processing image captured with - Exposure: ${result.exposureCompensation}, Flash: ${result.flashUsed}, Zoom: ${result.zoomRatio}")

                val processingResult = withContext(Dispatchers.IO) {
                    // Choose processing method based on settings
                    val bitmapForProcessing = if (isProcessingInGrayscale) {
                        AppLogger.d("Using grayscale processing for OCR")
                        if (FeatureFlags.ENABLE_ADAPTIVE_PROCESSING) {
                            convertToGrayscaleAdaptive(result.modelBitmap)
                        } else {
                            convertToGrayscaleAdvanced(
                                result.modelBitmap,
                                GrayscaleEnhancement.CONTRAST
                            )
                        }
                    } else {
                        AppLogger.d("Using color processing for OCR")
                        result.modelBitmap
                    }

                    processedGrayscaleBitmap = bitmapForProcessing

                    // Update progress on main thread
                    withContext(Dispatchers.Main) {
                        readingTextView.text = "Analyzing text..."
                    }

                    // Perform OCR detection
                    val (detections, resultBitmap) = meterDetector.detectMeterReading(
                        bitmapForProcessing
                    )
                    val reading = meterDetector.extractMeterReading(detections)

                    Pair(reading, resultBitmap)
                }

                val (reading, resultBitmap) = processingResult
                currentMeterReading = reading
                resultImageView.setImageBitmap(resultBitmap)

                readingTextView.text = if (reading.isEmpty()) {
                    "No meter detected"
                } else {
                    reading
                }

                // Enable save button after successful processing
                saveButton.isEnabled = true

                AppLogger.logUserAction(
                    "ocr_completed", mapOf(
                        "reading" to (reading ?: "null"),
                        "processing_mode" to if (isProcessingInGrayscale) "grayscale" else "color",
                        "exposure_compensation" to result.exposureCompensation,
                        "flash_used" to result.flashUsed,
                        "zoom_ratio" to result.zoomRatio
                    )
                )

            } catch (e: Exception) {
                AppLogger.e("Processing failed", e)
                readingTextView.text = "Processing failed: ${e.message}"
                processButton.isEnabled = false
            } finally {
                progressBar.visibility = View.GONE
                processButton.isEnabled = false
            }
        }
    }

    private fun convertToGrayscaleAdvanced(
        originalBitmap: Bitmap,
        enhancementType: GrayscaleEnhancement = GrayscaleEnhancement.CONTRAST
    ): Bitmap {
        val width = originalBitmap.width
        val height = originalBitmap.height

        val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscaleBitmap)

        val colorMatrix = when (enhancementType) {
            GrayscaleEnhancement.SIMPLE -> {
                ColorMatrix().apply { setSaturation(0f) }
            }

            GrayscaleEnhancement.CONTRAST -> {
                ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 1.3f
                    val brightness = -0.1f
                    val scale = contrast
                    val translate = brightness * 255f * (1f - contrast)

                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                scale, 0f, 0f, 0f, translate,
                                0f, scale, 0f, 0f, translate,
                                0f, 0f, scale, 0f, translate,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            }

            GrayscaleEnhancement.HIGH_CONTRAST -> {
                ColorMatrix().apply {
                    setSaturation(0f)
                    val contrast = 1.8f
                    val brightness = -0.2f
                    val scale = contrast
                    val translate = brightness * 255f * (1f - contrast)

                    postConcat(
                        ColorMatrix(
                            floatArrayOf(
                                scale, 0f, 0f, 0f, translate,
                                0f, scale, 0f, 0f, translate,
                                0f, 0f, scale, 0f, translate,
                                0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                }
            }

            GrayscaleEnhancement.LUMINANCE_WEIGHTED -> {
                ColorMatrix(
                    floatArrayOf(
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0.299f, 0.587f, 0.114f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            }
        }

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
            isAntiAlias = true
            isDither = true
        }

        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
        return grayscaleBitmap
    }

    private fun convertToGrayscaleAdaptive(originalBitmap: Bitmap): Bitmap {
        val brightness = calculateAverageBrightness(originalBitmap)
        val contrast = calculateContrast(originalBitmap)

        val enhancement = when {
            brightness < 0.3f && contrast < 0.4f -> GrayscaleEnhancement.HIGH_CONTRAST
            brightness > 0.7f -> GrayscaleEnhancement.CONTRAST
            contrast > 0.6f -> GrayscaleEnhancement.LUMINANCE_WEIGHTED
            else -> GrayscaleEnhancement.CONTRAST
        }

        AppLogger.d("Adaptive grayscale: brightness=$brightness, contrast=$contrast, using=$enhancement")
        return convertToGrayscaleAdvanced(originalBitmap, enhancement)
    }

    private fun calculateAverageBrightness(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalBrightness = 0L
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
        }

        return (totalBrightness / (pixels.size * 255f))
    }

    private fun calculateContrast(bitmap: Bitmap): Float {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val brightness = mutableListOf<Float>()
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            brightness.add((0.299f * r + 0.587f * g + 0.114f * b) / 255f)
        }

        val mean = brightness.average().toFloat()
        val variance = brightness.map { (it - mean) * (it - mean) }.average().toFloat()
        return sqrt(variance)
    }

    // ===========================================
    // Display Mode Operations
    // ===========================================

    private fun toggleDisplayMode() {
        val result = currentCaptureResult ?: return

        try {
            if (!isGrayscaleDisplayMode) {
                // Switch to grayscale display
                val currentBitmap = originalResultBitmap ?: result.roiBitmap
                val grayscaleBitmap =
                    convertToGrayscaleAdvanced(currentBitmap, GrayscaleEnhancement.CONTRAST)

                if (originalResultBitmap == null) {
                    originalResultBitmap = (resultImageView.drawable as? BitmapDrawable)?.bitmap
                }

                resultImageView.setImageBitmap(grayscaleBitmap)
                isGrayscaleDisplayMode = true
                showToast("Grayscale display mode")

            } else {
                // Switch back to color display
                val originalBitmap = originalResultBitmap ?: result.roiBitmap
                resultImageView.setImageBitmap(originalBitmap)
                isGrayscaleDisplayMode = false
                showToast("Color display mode")
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to toggle display mode", e)
            showError("Failed to convert image")
        }
    }

    // ===========================================
    // User Input Methods
    // ===========================================

    private fun handleReadingTextTap() {
        AppLogger.d("Retake Count: $retakeCount", TAG)

        if (appConfig.editEnable) {
            AppLogger.d("Editable is true", TAG)
        } else {
            AppLogger.d("Editable is false", TAG)
        }

        if ((retakeCount >= 3 && appConfig.editEnable) || (retakeCount >= 1 && appConfig.valType == "RMD")) {
            editFlag = true
            showNumericBottomDialog()
        }
    }

    private fun isValidForTapping(text: String): Boolean {
        return text.toDoubleOrNull() != null ||
                text == "No meter detected" ||
                text.startsWith("Tap:")
    }

    private fun showNumericBottomDialog() {
        try {
            val inflater = layoutInflater
            val dialogView = inflater.inflate(R.layout.dialog_numeric_keyboard, null)

            val bottomSheetDialog = BottomSheetDialog(this)
            bottomSheetDialog.setContentView(dialogView)
            bottomSheetDialog.show()

            val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
            inputNumber.clear()

            // Setup number buttons
            val numberButtonListener = View.OnClickListener { view ->
                val button = view as Button
                inputNumber.append(button.text.toString())
                displayText.text = inputNumber.toString()
            }

            // Assign listeners to number buttons
            listOf(
                R.id.button_0, R.id.button_1, R.id.button_2, R.id.button_3, R.id.button_4,
                R.id.button_5, R.id.button_6, R.id.button_7, R.id.button_8, R.id.button_9,
                R.id.button_decimal
            ).forEach { id ->
                dialogView.findViewById<Button>(id).setOnClickListener(numberButtonListener)
            }

            // Clear button
            dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
                inputNumber.setLength(0)
                displayText.text = ""
            }

            // Enter button
            dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
                val enteredValue = inputNumber.toString()
                if (validateMeterReading(enteredValue)) {
                    readingTextView.text = enteredValue
                    currentMeterReading = enteredValue
                    editFlag = true
                    AppLogger.logUserAction("manual_input", mapOf("value" to enteredValue))
                    bottomSheetDialog.dismiss()
                } else {
                    showError("Invalid reading format")
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Failed to show numeric dialog", e)
            showError("Failed to show input dialog")
        }
    }

    // ===========================================
    // Processing Options Dialog
    // ===========================================

    private fun showProcessingOptionsDialog() {
        val options = arrayOf(
            "Color Processing (Default)",
            "Auto Adaptive Grayscale",
            "Simple Grayscale",
            "Enhanced Contrast",
            "High Contrast",
            "Luminance Weighted"
        )

        val currentSelection = if (isProcessingInGrayscale) 1 else 0

        android.app.AlertDialog.Builder(this)
            .setTitle("Processing Mode")
            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
                when (which) {
                    0 -> {
                        isProcessingInGrayscale = false
                        showToast("Processing mode: Color (Default)")
                    }

                    else -> {
                        isProcessingInGrayscale = true
                        showToast("Processing mode: ${options[which]}")
                    }
                }

                AppLogger.logUserAction(
                    "processing_mode_changed", mapOf(
                        "mode" to options[which],
                        "is_grayscale" to isProcessingInGrayscale
                    )
                )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ===========================================
    // Save and Results Methods
    // ===========================================

    private fun saveCurrentImage() {
        val result = currentCaptureResult ?: return

        saveButton.isEnabled = false
        progressBar.visibility = View.VISIBLE
        readingTextView.text = "Saving..."

        lifecycleScope.launch {
            try {
                val meterReading = currentMeterReading.toString()

                val saveResult = cameraManager.saveImage(
                    result,
                    currentMeterReading,
                    appConfig.savedFileName,
                    editFlag
                )

                if (saveResult.filePath != null && saveResult.uri != null) {
                    AppLogger.d("Image saved at: ${saveResult.filePath}")
                    AppLogger.d("Image URI: ${saveResult.uri}")

                    AppLogger.logUserAction(
                        "image_saved", mapOf(
                            "reading" to meterReading,
                            "edited" to editFlag,
                            "path" to saveResult.filePath,
                            "uri" to saveResult.uri.toString(),
                            "exposure_compensation" to result.exposureCompensation,
                            "flash_used" to result.flashUsed,
                            "zoom_ratio" to result.zoomRatio
                        )
                    )

                    sendBackResults(meterReading, saveResult.filePath, saveResult.uri)
                } else {
                    throw Exception("Failed to save image - null path or URI returned")
                }

            } catch (e: Exception) {
                AppLogger.e("Failed to save image", e)
                showError("Failed to save image: ${e.message}")
            } finally {
                saveButton.isEnabled = true
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun sendBackResults(meterReading: String, imagePath: String, imageUri: Uri) {
        val result = currentCaptureResult

        val metadata = JSONObject().apply {
            put("meterReading", meterReading)
            put("filename", imagePath)
            put("imageUri", imageUri.toString())
            put("isEdited", editFlag)
            put("valType", appConfig.valType)
            put("serviceId", appConfig.serviceId)
            put("processingMode", if (isProcessingInGrayscale) "grayscale" else "color")

            // Add exposure metadata if available
            result?.let {
                put("exposureCompensation", it.exposureCompensation)
                put("flashUsed", it.flashUsed)
                put("zoomRatio", it.zoomRatio)
            }
        }

        val resultCode = getResultCode(appConfig.valType)
        intent.putExtra("metadata", metadata.toString())
        setResult(resultCode, intent)

        AppLogger.d("Sending results with exposure metadata: code=$resultCode, metadata=$metadata")

        CoroutineScope(Dispatchers.Main).launch {
            delay(PROCESSING_DELAY_MS)
            finish()
        }
    }

    private fun getResultCode(valType: String): Int {
        return when (valType) {
            "KWH" -> OCR_KWH_RESULT_CODE
            "KVAH" -> OCR_KVAH_RESULT_CODE
            "SKWH" -> OCR_SKWH_RESULT_CODE
            "SKVAH" -> OCR_SKVAH_RESULT_CODE
            "RMD" -> OCR_RMD_RESULT_CODE
            "LT" -> OCR_LT_RESULT_CODE
            "IMG" -> OCR_IMG_RESULT_CODE
            else -> OCR_INVALID_RESULT_CODE
        }
    }

    // ===========================================
    // UI State Management
    // ===========================================

    private fun updateUIState(state: AppState) {
        currentState = state

        when (state) {
            is AppState.Camera -> showCameraView()
            is AppState.Result -> showResultView(state.captureResult)
            is AppState.Error -> showError(state.message)
            is AppState.Processing -> showToast(state.message)
        }
    }

    private fun showCameraView() {
        resultLayout.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        roiOverlay.visibility = View.VISIBLE
        controlPanel.visibility = View.VISIBLE
        controlsContainer.visibility = View.VISIBLE

        cleanupBitmaps()
        isGrayscaleDisplayMode = false
        tapCount = 0
    }

    private fun showResultView(result: CameraManager.CaptureResult) {
        resetCaptureUI()
        resetProcessingUI()

        // Display the captured image (use color by default)
        val displayBitmap = result.roiBitmap
        resultImageView.setImageBitmap(displayBitmap)

        resultLayout.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        roiOverlay.visibility = View.GONE
        controlPanel.visibility = View.GONE
        controlsContainer.visibility = View.GONE

        // Configure buttons based on value type
        if (appConfig.valType == "IMG") {
            readingTextView.text = "Tap 'Save'"
            processButton.visibility = View.GONE
            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = true
        } else {
            readingTextView.text = "Tap 'Process'"
            processButton.visibility = View.VISIBLE
            processButton.isEnabled = true
            saveButton.visibility = View.VISIBLE
            saveButton.isEnabled = false
        }

        currentMeterReading = null
        updateConfigurationUI()
    }

    // ===========================================
    // Utility Methods
    // ===========================================

    private fun updateConfigurationUI() {
        serviceIdTextView.text = appConfig.serviceId
        valueTypeTextView.text = appConfig.valType
        resultServiceIdTextView.text = appConfig.serviceId
        resultValueTypeTextView.text = appConfig.valType
    }

    private fun setupAccessibility() {
        captureButton.contentDescription = "Capture meter reading"
        flashButton.contentDescription = "Toggle flash"
        switchButton.contentDescription = "Switch camera"
        exposureSeekBar.contentDescription = "Adjust exposure compensation, long press to reset"
        zoomSeekBar.contentDescription = "Adjust zoom level"
        resultImageView.contentDescription = "Captured meter image, tap to toggle display mode"
        readingTextView.contentDescription = "Meter reading result, tap to edit manually when enabled"
        processButton.contentDescription = "Process image with OCR, long press for options"
        decimaltextView.contentDescription = "Move decimal position in reading"
    }

    private fun validateMeterReading(reading: String): Boolean {
        return when {
            reading.isBlank() -> false
            reading.toDoubleOrNull() == null -> false
            reading.length > MAX_READING_LENGTH -> false
            else -> true
        }
    }

    private fun resetCaptureUI() {
        progressBar.visibility = View.GONE
        captureButton.isEnabled = true
    }

    private fun resetProcessingUI() {
        progressBar.visibility = View.GONE
        processButton.isEnabled = false
        saveButton.isEnabled = true
    }

    private fun showActivationExpiredMessage() {
        titleTextView = findViewById(R.id.titleTextView)
        titleTextView.text = "Activation Expired ${getVersionName()}"
        AppLogger.d("App activation period expired")
    }

    private fun logAutoRotationStatus() {
        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
        AppLogger.d("Auto-rotation is ${if (isAutoRotateEnabled) "enabled" else "disabled"}")
    }

    private fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
        return Settings.System.getInt(
            contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0
        ) == 1
    }

    private fun getVersionName(): String {
        return try {
            val packageManager = packageManager
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            "(Ver:${packageInfo.versionName})"
        } catch (e: PackageManager.NameNotFoundException) {
            AppLogger.e("Version not found", e)
            "Version not found"
        }
    }

    private fun showError(message: String) {
        showToast(message)
        AppLogger.e("Error displayed to user: $message")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun cleanupBitmaps() {
        currentCaptureResult?.let {
            ImageCropper.safeRecycle(it.roiBitmap)
            ImageCropper.safeRecycle(it.modelBitmap)
            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
                ImageCropper.safeRecycle(it.originalBitmap)
            }
        }

        originalResultBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                ImageCropper.safeRecycle(bitmap)
            }
        }

        processedGrayscaleBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                ImageCropper.safeRecycle(bitmap)
            }
        }

        originalResultBitmap = null
        processedGrayscaleBitmap = null
        currentCaptureResult = null
        currentMeterReading = null
    }

    private fun cleanupResources() {
        cleanupBitmaps()

        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::meterDetector.isInitialized) {
            meterDetector.close()
        }
    }

    // ===========================================
    // Permission Callbacks
    // ===========================================

    override fun onPermissionsGranted() {
        AppLogger.d("All permissions granted")
        initializeCamera()
    }

    override fun onPermissionsDenied() {
        AppLogger.e("Permissions denied")
        showError("Camera and storage permissions are required")
        finish()
    }

    // ===========================================
    // Logger Object
    // ===========================================

    object AppLogger {
        fun d(message: String, tag: String = TAG) {
            if (FeatureFlags.ENABLE_ENHANCED_LOGGING) {
                Log.d(tag, message)
            }
        }

        fun e(message: String, error: Throwable? = null, tag: String = TAG) {
            Log.e(tag, message, error)
        }

        fun logUserAction(action: String, details: Map<String, Any> = emptyMap()) {
            if (FeatureFlags.ENABLE_ENHANCED_LOGGING) {
                val logMessage =
                    "UserAction: $action ${details.entries.joinToString { "${it.key}=${it.value}" }}"
                d(logMessage)
            }
        }
    }
}
//package com.protective.ebillyocr
//
//import android.content.pm.ActivityInfo
//import android.content.pm.PackageManager
//import android.os.Bundle
//import android.util.Log
//import android.view.View
//import android.widget.Button
//import android.widget.ImageButton
//import android.widget.ImageView
//import android.widget.LinearLayout
//import android.widget.ProgressBar
//import android.widget.SeekBar
//import android.widget.TextView
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.camera.view.PreviewView
//import androidx.lifecycle.lifecycleScope
//import com.google.android.material.bottomsheet.BottomSheetDialog
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.flow.collectLatest
//import kotlinx.coroutines.launch
//import kotlinx.coroutines.withContext
//import org.json.JSONObject
//import android.content.ContentResolver
//import android.content.Intent
//import android.graphics.Bitmap
//import android.graphics.Canvas
//import android.graphics.ColorMatrix
//import android.graphics.ColorMatrixColorFilter
//import android.graphics.Paint
//import android.graphics.drawable.BitmapDrawable
//import android.net.Uri
//import android.provider.Settings
//import android.widget.CheckBox
//import android.text.SpannableString
//import android.text.Spanned
//import android.text.style.StyleSpan
//import android.text.style.RelativeSizeSpan
//import android.graphics.Typeface
//import androidx.appcompat.app.AlertDialog
//
//import kotlin.math.sqrt
//
///**
// * Enhanced MainActivity for Meter Reading OCR Application
// * Default processing mode: COLOR (not grayscale)W
// *
// * Features:
// * - Camera capture with ROI overlay
// * - OCR processing with multiple enhancement modes
// * - Manual input capability
// * - Adaptive image processing
// * - Enhanced error handling and logging
// */
//class MainActivity : AppCompatActivity(), PermissionManager.PermissionListener {
//
//    companion object {
//        private const val TAG = "MainActivity_OCR"
//
//        private const val MAX_READING_LENGTH = 20
//        private const val TAP_COUNT_THRESHOLD = 3
//        private const val PROCESSING_DELAY_MS = 1000L
//
//        // Processing Settings
//        private const val DEFAULT_USE_COLOR_PROCESSING = true  // Changed to COLOR default
//
//        // Result Codes
//        const val OCR_KWH_RESULT_CODE = 666
//        const val OCR_KVAH_RESULT_CODE = 667
//        const val OCR_RMD_RESULT_CODE = 668
//        const val OCR_LT_RESULT_CODE = 669
//        const val OCR_IMG_RESULT_CODE = 770
//        const val OCR_SKWH_RESULT_CODE = 771
//        const val OCR_SKVAH_RESULT_CODE = 772
//        const val OCR_INVALID_RESULT_CODE = 773
//    }
//
//    // ===========================================
//    // UI Components
//    // ===========================================
//
//    private lateinit var previewView: PreviewView
//    private lateinit var roiOverlay: ROIOverlay
//    private lateinit var captureButton: Button
//    private lateinit var flashButton: ImageButton
//    private lateinit var switchButton: ImageButton
//    private lateinit var zoomSeekBar: SeekBar
//    private lateinit var exposureSeekBar: SeekBar
//    private lateinit var progressBar: ProgressBar
//    private lateinit var decimalCheckBox: CheckBox
//    private lateinit var resultLayout: LinearLayout
//    private lateinit var resultImageView: ImageView
//    private lateinit var readingTextView: TextView
//    private lateinit var decimaltextView: TextView
//    private lateinit var titleTextView: TextView
//
//    private lateinit var serviceIdTextView: TextView
//    private lateinit var valueTypeTextView: TextView
//    private lateinit var resultServiceIdTextView: TextView
//    private lateinit var resultValueTypeTextView: TextView
//
//    private lateinit var saveButton: Button
//    private lateinit var retakeButton: Button
//    private lateinit var processButton: Button
//
//    // ===========================================
//    // Core Components
//    // ===========================================
//
//    private lateinit var permissionManager: PermissionManager
//    private lateinit var cameraManager: CameraManager
//    private lateinit var meterDetector: MeterDetector
//    private lateinit var appConfig: AppConfig
//
//    // ===========================================
//    // State Variables
//    // ===========================================
//
//    private var currentState: AppState = AppState.Camera
//    private var currentCaptureResult: CameraManager.CaptureResult? = null
//    private var currentMeterReading: String? = null
//    private val inputNumber = StringBuilder()
//    private var tapCount = 0
//    private var retakeCount = 0
//    private var decimalPosition = 0 // Track current decimal position
//    private var editFlag = false
//    private var originalReadingValue: String = ""
//
//    // Image Processing State - DEFAULT TO COLOR PROCESSING
//    private var isProcessingInGrayscale = !DEFAULT_USE_COLOR_PROCESSING  // false = color processing
//    private var isGrayscaleDisplayMode = false
//    private var originalResultBitmap: Bitmap? = null
//    private var processedGrayscaleBitmap: Bitmap? = null
//
//    // ===========================================
//    // Feature Configuration
//    // ===========================================
//
//    private object FeatureFlags {
//        const val ENABLE_GRAYSCALE_TOGGLE = true
//        const val ENABLE_MANUAL_INPUT = true
//        const val ENABLE_ADAPTIVE_PROCESSING = true
//        const val ENABLE_ENHANCED_LOGGING = true
//        const val ENABLE_PROCESSING_OPTIONS = true
//    }
//
//    // ===========================================
//    // Data Classes
//    // ===========================================
//
//    data class AppConfig(
//        val serviceId: String,
//        val valType: String,
//        val savedFileName: String,
//        val editEnable: Boolean,
//        val useColorProcessing: Boolean = DEFAULT_USE_COLOR_PROCESSING
//    ) {
//        companion object {
//            fun fromIntent(intent: Intent): AppConfig {
//                val dataHandler = IntentDataHandler(intent)
//                val serviceId = dataHandler.getServiceId()
//                val valType = dataHandler.getValType()
//                val isEditEnabled = dataHandler.isEditEnabled()
//                val useColor =
//                    intent.getBooleanExtra("use_color_processing", DEFAULT_USE_COLOR_PROCESSING)
//
//
//
//                return AppConfig(
//                    serviceId = serviceId,
//                    valType = valType,
//                    savedFileName = "${serviceId}_${valType}",
//                    isEditEnabled,
//                    useColorProcessing = useColor
//                )
//            }
//        }
//    }
//
//    sealed class AppState {
//        object Camera : AppState()
//        data class Processing(val message: String) : AppState()
//        data class Result(val captureResult: CameraManager.CaptureResult) : AppState()
//        data class Error(val message: String) : AppState()
//    }
//
//    enum class GrayscaleEnhancement {
//        SIMPLE,
//        CONTRAST,
//        HIGH_CONTRAST,
//        LUMINANCE_WEIGHTED
//    }
//
//    // ===========================================
//    // Lifecycle Methods
//    // ===========================================
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        setContentView(R.layout.activity_main)
//
//        // Set orientation to portrait
//        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
//
//        // Check date range for app activation
//        val startDate = "2025-02-01"
//        val endDate = "2025-09-30"
//
//        AppLogger.d("App activation period: $startDate to $endDate")
//        logAutoRotationStatus()
//
//        if (DateUtils.isWithinDateRange(startDate, endDate)) {
//            try {
//                initializeApp()
//                AppLogger.d("App initialized successfully within activation period")
//            } catch (e: Exception) {
//                AppLogger.e("Failed to initialize app", e)
//                showError("Failed to initialize app: ${e.message}")
//                finish()
//            }
//        } else {
//            showActivationExpiredMessage()
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        AppLogger.d("Activity destroyed - cleaning up resources")
//
//        cleanupResources()
//    }
//
//    // ===========================================
//    // Initialization Methods
//    // ===========================================
//
//    private fun initializeApp() {
//        AppLogger.d("Initializing application components")
//
//        // Initialize configuration from intent
//        appConfig = AppConfig.fromIntent(intent)
//        AppLogger.d("App configuration loaded: $appConfig")
//
//        // Set processing mode based on configuration
//        isProcessingInGrayscale = !appConfig.useColorProcessing
//
//        // Initialize UI components
//        initializeViews()
//        updateConfigurationUI()
//        setupAccessibility()
//
//        // Initialize managers
//        initializeManagers()
//
//        // Request necessary permissions
//        permissionManager.requestPermissions()
//    }
//
//    private fun initializeViews() {
//        AppLogger.d("Initializing UI components")
//
//        // Camera preview components
//        previewView = findViewById(R.id.viewFinder)
//        roiOverlay = findViewById(R.id.roiOverlay)
//        captureButton = findViewById(R.id.captureButton)
//        flashButton = findViewById(R.id.flashButton)
//        switchButton = findViewById(R.id.switchButton)
//        zoomSeekBar = findViewById(R.id.zoomSeekBar)
//        exposureSeekBar = findViewById(R.id.exposureSeekBar)
//        progressBar = findViewById(R.id.progressBar)
//
//        // Service ID and Value Type displays
//        serviceIdTextView = findViewById(R.id.serviceIdTextView)
//        valueTypeTextView = findViewById(R.id.valueTypeTextView)
//
//        // Result view components
//        resultLayout = findViewById(R.id.resultLayout)
//        resultImageView = findViewById(R.id.resultImageView)
//        readingTextView = findViewById(R.id.readingTextView)
//        resultServiceIdTextView = findViewById(R.id.resultServiceIdTextView)
//        resultValueTypeTextView = findViewById(R.id.resultValueTypeTextView)
//        saveButton = findViewById(R.id.saveButton)
//        retakeButton = findViewById(R.id.retakeButton)
//        processButton = findViewById(R.id.processButton)
//        titleTextView = findViewById(R.id.titleTextView)
//        decimalCheckBox = findViewById(R.id.decimalCheckBox)
//        decimaltextView = findViewById(R.id.decimalTextbox)
//
//        setupClickListeners()
//        setupSeekBarListeners()
//        setupDecimalCheckbox()
//
//        // Set title with version
//        titleTextView.text = "Ebilly OCR ${getVersionName()}"
//
//        //cameraManager.setZoom(50/100f)
//    }
//    private fun setupExposureControls() {
//        // Exposure lock button
//        exposureLockButton.setOnClickListener {
//            AppLogger.logUserAction("exposure_lock_toggled")
//            toggleExposureLock()
//        }
//
//        // Long press on exposure info for reset
//        exposureInfoTextView.setOnLongClickListener {
//            AppLogger.logUserAction("exposure_reset")
//            resetExposure()
//            true
//        }
//
//        // Initialize exposure info display
//        updateExposureInfo()
//    }
//
//    private fun setupDecimalCheckbox() {
//        decimalCheckBox.setOnCheckedChangeListener { _, isChecked ->
//            val currentText = readingTextView.text.toString()
//
//            if (isChecked) {
//                // Add decimal point one digit from right
//                addDecimalToReading(currentText)
//            } else {
//                // Remove decimal point
//                removeDecimalFromReading(currentText)
//            }
//        }
//    }
//    private fun showEditEnabledDialog() {
//        try {
//            val message = SpannableString("Edit Mode enabled")
//            message.setSpan(StyleSpan(Typeface.BOLD), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
//            message.setSpan(RelativeSizeSpan(1.8f), 0, message.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
//
//            val dialog = AlertDialog.Builder(this)
//                .setTitle("Information")
//                .setMessage(message)
//                .setCancelable(false)
//                .create()
//
//            dialog.show()
//
//            lifecycleScope.launch {
//                delay(3000)
//                if (dialog.isShowing && !isDestroyed) {
//                    dialog.dismiss()
//                }
//            }
//
//            AppLogger.d("Edit enabled dialog displayed for 3 seconds")
//
//        } catch (e: Exception) {
//            AppLogger.e("Failed to show edit enabled dialog", e)
//            showToast("Edit enabled")
//        }
//    }
//
//    private fun addDecimalFromRight(reading: String) {
//        // Skip if reading is empty or too short
//        if (reading.isEmpty() || reading.length < 2) {
//            return
//        }
//
//        // Remove existing decimal if present
//        val cleanReading = reading.replace(".", "")
//
//        // Store original value if not already stored
//        if (originalReadingValue.isEmpty()) {
//            originalReadingValue = cleanReading
//        }
//
//        // Calculate decimal position from right (cycles through valid positions)
//        val maxPosition = cleanReading.length - 1
//        decimalPosition = if (decimalPosition >= maxPosition) 1 else decimalPosition + 1
//
//        // Insert decimal at position from right
//        val insertPosition = cleanReading.length - decimalPosition
//        val withDecimal = cleanReading.substring(0, insertPosition) + "." +
//                cleanReading.substring(insertPosition)
//
//        readingTextView.text = withDecimal
//        currentMeterReading = withDecimal
//    }
//
//
//    private fun addDecimalFromLeft(reading: String) {
//        // Skip if reading is empty or too short
//        if (reading.isEmpty() || reading.length < 2) {
//            return
//        }
//
//        // Remove existing decimal if present
//        val cleanReading = reading.replace(".", "")
//
//        // Store original value if not already stored
//        if (originalReadingValue.isEmpty()) {
//            originalReadingValue = cleanReading
//        }
//
//        // Calculate decimal position (cycles through valid positions)
//        val maxPosition = cleanReading.length - 1
//        decimalPosition = (decimalPosition % maxPosition) + 1
//
//        // Insert decimal at current position
//        val withDecimal = cleanReading.substring(0, decimalPosition) + "." +
//                cleanReading.substring(decimalPosition)
//
//        readingTextView.text = withDecimal
//        currentMeterReading = withDecimal
//    }
//
//    // Optional: Reset decimal position when needed
//    private fun resetDecimalPosition() {
//        decimalPosition = 0
//    }
//
//    private fun addDecimalToReading(reading: String) {
//        // Skip if reading is empty, too short, or already has decimal
//        if (reading.isEmpty() || reading.length < 2 || reading.contains(".")) {
//            return
//        }
//
//        // Store original value if not already stored
//        if (originalReadingValue.isEmpty()) {
//            originalReadingValue = reading
//        }
//
//        // Add decimal point one digit from right
//        // Example: "12345" becomes "1234.5"
//        val withDecimal = reading.substring(0, reading.length - 1) + "." + reading.last()
//        readingTextView.text = withDecimal
//        currentMeterReading = withDecimal
//    }
//
//    private fun removeDecimalFromReading(reading: String) {
//        // If has decimal, remove it
//        if (reading.contains(".")) {
//            val withoutDecimal = reading.replace(".", "")
//            readingTextView.text = withoutDecimal
//            currentMeterReading = readingTextView.text.toString()
//        } else if (originalReadingValue.isNotEmpty()) {
//            // Restore original value
//            readingTextView.text = originalReadingValue
//        }
//
//        // Clear stored original value
//        originalReadingValue = ""
//
//    }
//
//    // Call this method when you update the reading value from OCR/processing
//    fun updateReadingValue(newReading: String) {
//        // Clear stored original value when new reading comes in
//        originalReadingValue = ""
//
//        // Update the text view
//        readingTextView.text = newReading
//
//        // If decimal checkbox is checked, apply decimal formatting
//        if (decimalCheckBox.isChecked) {
//            addDecimalToReading(newReading)
//        }
//    }
//
//    // Helper method to get the current reading value (with or without decimal)
//    fun getCurrentReadingValue(): String {
//        return readingTextView.text.toString()
//    }
//
//    // Helper method to get the numeric reading value without decimal
//    fun getNumericReadingValue(): String {
//        val currentText = readingTextView.text.toString()
//        return if (currentText.contains(".")) {
//            currentText.replace(".", "")
//        } else {
//            currentText
//        }
//    }
//
//
//    private fun setupClickListeners() {
//
//        decimaltextView.setOnClickListener {
//
//            if (retakeCount >= 1) {
//                val currentText = readingTextView.text.toString()
//                addDecimalFromRight(currentText)
//            } else {
//                showToast("Click the Process Button first")
//            }
//
//
//        }
//        captureButton.setOnClickListener {
//            AppLogger.logUserAction("capture_clicked", mapOf("valType" to appConfig.valType))
//            captureImage()
//
//
//
//
//
//            AppLogger.d("Retake Count: $retakeCount")
//
//        }
//
//        flashButton.setOnClickListener {
//            AppLogger.logUserAction("flash_toggled")
//            toggleFlash()
//        }
//
//        switchButton.setOnClickListener {
//            AppLogger.logUserAction("camera_switched")
//            switchCamera()
//        }
//
//        if (FeatureFlags.ENABLE_GRAYSCALE_TOGGLE) {
//            resultImageView.setOnClickListener {
//                AppLogger.logUserAction("display_mode_toggled")
//                toggleDisplayMode()
//            }
//        }
//
//        saveButton.setOnClickListener {
//            AppLogger.logUserAction("save_clicked")
//            saveCurrentImage()
//        }
//
//        if (FeatureFlags.ENABLE_MANUAL_INPUT) {
//            readingTextView.setOnClickListener {
//                handleReadingTextTap()
//            }
//        }
//
//        retakeButton.setOnClickListener {
//            AppLogger.logUserAction("retake_clicked")
//
//
//
//
//
//            updateUIState(AppState.Camera)
//        }
//
//        processButton.setOnClickListener {
//            AppLogger.logUserAction("process_clicked")
//
//            retakeCount++
//
//
//
//            if (retakeCount == 3) {
//                showEditEnabledDialog()
////                showToast("Edit Enabled")
////                showToast("Retake count: $retakeCount")
//            }
//
//
//            processCurrentImage()
//
//
//        }
//
//        // Long click for processing options
//        if (FeatureFlags.ENABLE_PROCESSING_OPTIONS) {
//            processButton.setOnLongClickListener {
//                showProcessingOptionsDialog()
//                true
//            }
//        }
//    }
//
//    private fun setupSeekBarListeners() {
//        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                    val zoomLevel = progress / 100f
//                    cameraManager.setZoom(zoomLevel)
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//
//        exposureSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
//            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
//                if (fromUser && ::cameraManager.isInitialized) {
//                    // Exposure control implementation can be added here
//                    AppLogger.d("Exposure adjustment: $progress")
//                }
//            }
//
//            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
//            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
//        })
//    }
//
//    private fun initializeManagers() {
//        permissionManager = PermissionManager(this, this)
//        meterDetector = MeterDetector(this)
//    }
//
//    private fun initializeCamera() {
//        AppLogger.d("Initializing camera")
//
//        try {
//            cameraManager = CameraManager(this, this, previewView, roiOverlay)
//
//
//            // Observe camera capture results
//            lifecycleScope.launch {
//                cameraManager.captureResult.collectLatest { result ->
//                    result?.let {
//                        currentCaptureResult = it
//                        updateUIState(AppState.Result(it))
//                    }
//                }
//            }
//
//            cameraManager.initialize()
//
//
//            // Set initial exposure
//            if (::cameraManager.isInitialized) {
//                exposureSeekBar.progress = 50
//
//                zoomSeekBar.progress = 50
////                val zoomLevel = zoomSeekBar.progress / 100f
////                cameraManager.setZoom(zoomLevel)
//            }
//            AppLogger.d("Camera initialized")
//        } catch (e: Exception) {
//            AppLogger.e("Failed to initialize camera", e)
//            showError("Failed to initialize camera: ${e.message}")
//        }
//    }
//
//    // ===========================================
//    // Camera Operations
//    // ===========================================
//
//    private fun captureImage() {
//        if (!::cameraManager.isInitialized) {
//            showError("Camera not initialized")
//            return
//        }
//
//        try {
//            progressBar.visibility = View.VISIBLE
//            captureButton.isEnabled = false
//            cameraManager.captureImage(appConfig.valType)
//        } catch (e: Exception) {
//            AppLogger.e("Capture failed", e)
//            showError("Failed to capture image: ${e.message}")
//            resetCaptureUI()
//        }
//    }
//
//    private fun toggleFlash() {
//        if (!::cameraManager.isInitialized) return
//
//        try {
//            val flashOn = cameraManager.toggleFlash()
//            flashButton.setImageResource(
//                if (flashOn) R.drawable.ic_flash_on
//                else R.drawable.ic_flash_off
//            )
//        } catch (e: Exception) {
//            AppLogger.e("Flash toggle failed", e)
//            showError("Failed to toggle flash")
//        }
//    }
//
//    private fun switchCamera() {
//        if (!::cameraManager.isInitialized) return
//
//        try {
//            cameraManager.switchCamera()
//        } catch (e: Exception) {
//            AppLogger.e("Camera switch failed", e)
//            showError("Failed to switch camera")
//        }
//    }
//
//    // ===========================================
//    // Image Processing Methods
//    // ===========================================
//
//    private fun processCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        // Update UI for processing state
//        processButton.isEnabled = false
//        saveButton.isEnabled = false
//        progressBar.visibility = View.VISIBLE
//        readingTextView.text = "Processing..."
//
//        lifecycleScope.launch {
//            try {
//                val processingResult = withContext(Dispatchers.IO) {
//                    // Choose processing method based on settings
//                    val bitmapForProcessing = if (isProcessingInGrayscale) {
//                        AppLogger.d("Using grayscale processing for OCR")
//                        if (FeatureFlags.ENABLE_ADAPTIVE_PROCESSING) {
//                            convertToGrayscaleAdaptive(result.modelBitmap)
//                        } else {
//                            convertToGrayscaleAdvanced(
//                                result.modelBitmap,
//                                GrayscaleEnhancement.CONTRAST
//                            )
//                        }
//                    } else {
//                        AppLogger.d("Using color processing for OCR")
//                        result.modelBitmap
//                    }
//
//                    processedGrayscaleBitmap = bitmapForProcessing
//
//                    // Update progress on main thread
//                    withContext(Dispatchers.Main) {
//                        readingTextView.text = "Analyzing text..."
//                    }
//
//                    // Perform OCR detection
//                    val (detections, resultBitmap) = meterDetector.detectMeterReading(
//                        bitmapForProcessing
//                    )
//                    val reading = meterDetector.extractMeterReading(detections)
//
//                    Pair(reading, resultBitmap)
//                }
//
//                val (reading, resultBitmap) = processingResult
//                currentMeterReading = reading
//                resultImageView.setImageBitmap(resultBitmap)
//
//                readingTextView.text = if (reading.isEmpty()) {
//                    "No meter detected"
//                } else {
//                    reading
//                }
//
//                // Enable save button after successful processing
//                saveButton.isEnabled = true
//
//                AppLogger.logUserAction(
//                    "ocr_completed", mapOf(
//                        "reading" to (reading ?: "null"),
//                        "processing_mode" to if (isProcessingInGrayscale) "grayscale" else "color"
//                    )
//                )
//
//            } catch (e: Exception) {
//                AppLogger.e("Processing failed", e)
//                readingTextView.text = "Processing failed: ${e.message}"
//                processButton.isEnabled = false
//            } finally {
//                progressBar.visibility = View.GONE
//                processButton.isEnabled = false
//            }
//        }
//    }
//
//    private fun convertToGrayscaleAdvanced(
//        originalBitmap: Bitmap,
//        enhancementType: GrayscaleEnhancement = GrayscaleEnhancement.CONTRAST
//    ): Bitmap {
//        val width = originalBitmap.width
//        val height = originalBitmap.height
//
//        val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
//        val canvas = Canvas(grayscaleBitmap)
//
//        val colorMatrix = when (enhancementType) {
//            GrayscaleEnhancement.SIMPLE -> {
//                ColorMatrix().apply { setSaturation(0f) }
//            }
//
//            GrayscaleEnhancement.CONTRAST -> {
//                ColorMatrix().apply {
//                    setSaturation(0f)
//                    val contrast = 1.3f
//                    val brightness = -0.1f
//                    val scale = contrast
//                    val translate = brightness * 255f * (1f - contrast)
//
//                    postConcat(
//                        ColorMatrix(
//                            floatArrayOf(
//                                scale, 0f, 0f, 0f, translate,
//                                0f, scale, 0f, 0f, translate,
//                                0f, 0f, scale, 0f, translate,
//                                0f, 0f, 0f, 1f, 0f
//                            )
//                        )
//                    )
//                }
//            }
//
//            GrayscaleEnhancement.HIGH_CONTRAST -> {
//                ColorMatrix().apply {
//                    setSaturation(0f)
//                    val contrast = 1.8f
//                    val brightness = -0.2f
//                    val scale = contrast
//                    val translate = brightness * 255f * (1f - contrast)
//
//                    postConcat(
//                        ColorMatrix(
//                            floatArrayOf(
//                                scale, 0f, 0f, 0f, translate,
//                                0f, scale, 0f, 0f, translate,
//                                0f, 0f, scale, 0f, translate,
//                                0f, 0f, 0f, 1f, 0f
//                            )
//                        )
//                    )
//                }
//            }
//
//            GrayscaleEnhancement.LUMINANCE_WEIGHTED -> {
//                ColorMatrix(
//                    floatArrayOf(
//                        0.299f, 0.587f, 0.114f, 0f, 0f,
//                        0.299f, 0.587f, 0.114f, 0f, 0f,
//                        0.299f, 0.587f, 0.114f, 0f, 0f,
//                        0f, 0f, 0f, 1f, 0f
//                    )
//                )
//            }
//        }
//
//        val paint = Paint().apply {
//            colorFilter = ColorMatrixColorFilter(colorMatrix)
//            isAntiAlias = true
//            isDither = true
//        }
//
//        canvas.drawBitmap(originalBitmap, 0f, 0f, paint)
//        return grayscaleBitmap
//    }
//
//    private fun convertToGrayscaleAdaptive(originalBitmap: Bitmap): Bitmap {
//        val brightness = calculateAverageBrightness(originalBitmap)
//        val contrast = calculateContrast(originalBitmap)
//
//        val enhancement = when {
//            brightness < 0.3f && contrast < 0.4f -> GrayscaleEnhancement.HIGH_CONTRAST
//            brightness > 0.7f -> GrayscaleEnhancement.CONTRAST
//            contrast > 0.6f -> GrayscaleEnhancement.LUMINANCE_WEIGHTED
//            else -> GrayscaleEnhancement.CONTRAST
//        }
//
//        AppLogger.d("Adaptive grayscale: brightness=$brightness, contrast=$contrast, using=$enhancement")
//        return convertToGrayscaleAdvanced(originalBitmap, enhancement)
//    }
//
//    private fun calculateAverageBrightness(bitmap: Bitmap): Float {
//        val width = bitmap.width
//        val height = bitmap.height
//        val pixels = IntArray(width * height)
//        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
//
//        var totalBrightness = 0L
//        for (pixel in pixels) {
//            val r = (pixel shr 16) and 0xFF
//            val g = (pixel shr 8) and 0xFF
//            val b = pixel and 0xFF
//            totalBrightness += (0.299 * r + 0.587 * g + 0.114 * b).toLong()
//        }
//
//        return (totalBrightness / (pixels.size * 255f))
//    }
//
//    private fun calculateContrast(bitmap: Bitmap): Float {
//        val width = bitmap.width
//        val height = bitmap.height
//        val pixels = IntArray(width * height)
//        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
//
//        val brightness = mutableListOf<Float>()
//        for (pixel in pixels) {
//            val r = (pixel shr 16) and 0xFF
//            val g = (pixel shr 8) and 0xFF
//            val b = pixel and 0xFF
//            brightness.add((0.299f * r + 0.587f * g + 0.114f * b) / 255f)
//        }
//
//        val mean = brightness.average().toFloat()
//        val variance = brightness.map { (it - mean) * (it - mean) }.average().toFloat()
//        return sqrt(variance)
//    }
//
//    // ===========================================
//    // Display Mode Operations
//    // ===========================================
//
//    private fun toggleDisplayMode() {
//        val result = currentCaptureResult ?: return
//
//        try {
//            if (!isGrayscaleDisplayMode) {
//                // Switch to grayscale display
//                val currentBitmap = originalResultBitmap ?: result.roiBitmap
//                val grayscaleBitmap =
//                    convertToGrayscaleAdvanced(currentBitmap, GrayscaleEnhancement.CONTRAST)
//
//                if (originalResultBitmap == null) {
//                    originalResultBitmap = (resultImageView.drawable as? BitmapDrawable)?.bitmap
//                }
//
//                resultImageView.setImageBitmap(grayscaleBitmap)
//                isGrayscaleDisplayMode = true
//                showToast("Grayscale display mode")
//
//            } else {
//                // Switch back to color display
//                val originalBitmap = originalResultBitmap ?: result.roiBitmap
//                resultImageView.setImageBitmap(originalBitmap)
//                isGrayscaleDisplayMode = false
//                showToast("Color display mode")
//            }
//        } catch (e: Exception) {
//            AppLogger.e("Failed to toggle display mode", e)
//            showError("Failed to convert image")
//        }
//    }
//
//    // ===========================================
//    // User Input Methods
//    // ===========================================
//
//    private fun handleReadingTextTap() {
//
//        AppLogger.d("Retake Count: $retakeCount", TAG)
//
//        if (appConfig.editEnable) {
//            AppLogger.d("Editable is true", TAG)
//        } else {
//            AppLogger.d("Editable is false", TAG)
//        }
//
//        if ((retakeCount >= 3 && appConfig.editEnable) || (retakeCount >= 1 && appConfig.valType == "RMD")) {
//            editFlag = true
//            showNumericBottomDialog()
//        }
//
////        val text = readingTextView.text.toString()
////
////        if (isValidForTapping(text)) {
////            tapCount++
////            AppLogger.d("Reading text tapped: count=$tapCount")
////
////            when (tapCount) {
////                TAP_COUNT_THRESHOLD -> {
////                    editFlag = true
////                    showNumericBottomDialog()
////                    tapCount = 0
////                }
////                else -> {
////                    val remaining = TAP_COUNT_THRESHOLD - tapCount
////                    readingTextView.text = "Tap: ${if (remaining == 1) "1 time" else "$remaining times"}"
////                }
////            }
////        }
//    }
//
//    private fun isValidForTapping(text: String): Boolean {
//        return text.toDoubleOrNull() != null ||
//                text == "No meter detected" ||
//                text.startsWith("Tap:")
//    }
//
//    private fun showNumericBottomDialog() {
//        try {
//            val inflater = layoutInflater
//            val dialogView = inflater.inflate(R.layout.dialog_numeric_keyboard, null)
//
//            val bottomSheetDialog = BottomSheetDialog(this)
//            bottomSheetDialog.setContentView(dialogView)
//            bottomSheetDialog.show()
//
//            val displayText = dialogView.findViewById<TextView>(R.id.txtTitle)
//            inputNumber.clear()
//
//            // Setup number buttons
//            val numberButtonListener = View.OnClickListener { view ->
//                val button = view as Button
//                inputNumber.append(button.text.toString())
//                displayText.text = inputNumber.toString()
//            }
//
//            // Assign listeners to number buttons
//            listOf(
//                R.id.button_0, R.id.button_1, R.id.button_2, R.id.button_3, R.id.button_4,
//                R.id.button_5, R.id.button_6, R.id.button_7, R.id.button_8, R.id.button_9,
//                R.id.button_decimal
//            ).forEach { id ->
//                dialogView.findViewById<Button>(id).setOnClickListener(numberButtonListener)
//            }
//
//            // Clear button
//            dialogView.findViewById<Button>(R.id.button_clear).setOnClickListener {
//                inputNumber.setLength(0)
//                displayText.text = ""
//            }
//
//            // Enter button
//            dialogView.findViewById<Button>(R.id.button_enter).setOnClickListener {
//                val enteredValue = inputNumber.toString()
//                if (validateMeterReading(enteredValue)) {
//                    readingTextView.text = enteredValue
//                    currentMeterReading = enteredValue
//                    editFlag = true
//                    AppLogger.logUserAction("manual_input", mapOf("value" to enteredValue))
//                    bottomSheetDialog.dismiss()
//                } else {
//                    showError("Invalid reading format")
//                }
//            }
//        } catch (e: Exception) {
//            AppLogger.e("Failed to show numeric dialog", e)
//            showError("Failed to show input dialog")
//        }
//    }
//
//    // ===========================================
//    // Processing Options Dialog
//    // ===========================================
//
//    private fun showProcessingOptionsDialog() {
//        val options = arrayOf(
//            "Color Processing (Default)",
//            "Auto Adaptive Grayscale",
//            "Simple Grayscale",
//            "Enhanced Contrast",
//            "High Contrast",
//            "Luminance Weighted"
//        )
//
//        val currentSelection = if (isProcessingInGrayscale) 1 else 0
//
//        android.app.AlertDialog.Builder(this)
//            .setTitle("Processing Mode")
//            .setSingleChoiceItems(options, currentSelection) { dialog, which ->
//                when (which) {
//                    0 -> {
//                        isProcessingInGrayscale = false
//                        showToast("Processing mode: Color (Default)")
//                    }
//
//                    else -> {
//                        isProcessingInGrayscale = true
//                        showToast("Processing mode: ${options[which]}")
//                    }
//                }
//
//                AppLogger.logUserAction(
//                    "processing_mode_changed", mapOf(
//                        "mode" to options[which],
//                        "is_grayscale" to isProcessingInGrayscale
//                    )
//                )
//                dialog.dismiss()
//            }
//            .setNegativeButton("Cancel", null)
//            .show()
//    }
//
//    // ===========================================
//    // Save and Results Methods
//    // ===========================================
//
////    private fun saveCurrentImage() {
////        val result = currentCaptureResult ?: return
////
////        saveButton.isEnabled = false
////        progressBar.visibility = View.VISIBLE
////        readingTextView.text = "Saving..."
////
////        lifecycleScope.launch {
////            try {
////                val meterReading = currentMeterReading.toString()
////               // val meterReading = readingTextView.text.toString()
////                val imagePath = cameraManager.saveImage(
////                    result,
////                    currentMeterReading,
////                    appConfig.savedFileName,
////                    editFlag
////                ).toString()
////
////                AppLogger.d("Image saved at: $imagePath")
////                AppLogger.logUserAction("image_saved", mapOf(
////                    "reading" to meterReading,
////                    "edited" to editFlag,
////                    "path" to imagePath
////                ))
////
////                sendBackResults(meterReading, imagePath)
////
////            } catch (e: Exception) {
////                AppLogger.e("Failed to save image", e)
////                showError("Failed to save image: ${e.message}")
////                saveButton.isEnabled = true
////                progressBar.visibility = View.GONE
////            }
////        }
////    }
//
//    private fun saveCurrentImage() {
//        val result = currentCaptureResult ?: return
//
//        saveButton.isEnabled = false
//        progressBar.visibility = View.VISIBLE
//        readingTextView.text = "Saving..."
//
//        lifecycleScope.launch {
//            try {
//                val meterReading = currentMeterReading.toString()
//
//                val saveResult = cameraManager.saveImage(
//                    result,
//                    currentMeterReading,
//                    appConfig.savedFileName,
//                    editFlag
//                )
//
//                if (saveResult.filePath != null && saveResult.uri != null) {
//                    AppLogger.d("Image saved at: ${saveResult.filePath}")
//                    AppLogger.d("Image URI: ${saveResult.uri}")
//
//                    AppLogger.logUserAction(
//                        "image_saved", mapOf(
//                            "reading" to meterReading,
//                            "edited" to editFlag,
//                            "path" to saveResult.filePath,
//                            "uri" to saveResult.uri.toString()
//                        )
//                    )
//
//                    sendBackResults(meterReading, saveResult.filePath, saveResult.uri)
//                } else {
//                    throw Exception("Failed to save image - null path or URI returned")
//                }
//
//            } catch (e: Exception) {
//                AppLogger.e("Failed to save image", e)
//                showError("Failed to save image: ${e.message}")
//            } finally {
//                saveButton.isEnabled = true
//                progressBar.visibility = View.GONE
//            }
//        }
//    }
//
////    private fun sendBackResults(meterReading: String, imagePath: String) {
////        val metadata = JSONObject().apply {
////            put("meterReading", meterReading)
////            put("filename", imagePath)
////            put("isEdited", editFlag)
////            put("valType", appConfig.valType)
////            put("serviceId", appConfig.serviceId)
////            put("processingMode", if (isProcessingInGrayscale) "grayscale" else "color")
////        }
////
////        val resultCode = getResultCode(appConfig.valType)
////        intent.putExtra("metadata", metadata.toString())
////        setResult(resultCode, intent)
////
////        AppLogger.d("Sending results: code=$resultCode, metadata=$metadata")
////
////        CoroutineScope(Dispatchers.Main).launch {
////            delay(PROCESSING_DELAY_MS)
////            finish()
////        }
////    }
//
//    private fun sendBackResults(meterReading: String, imagePath: String, imageUri: Uri) {
//        val metadata = JSONObject().apply {
//            put("meterReading", meterReading)
//            put("filename", imagePath)
//            put("imageUri", imageUri.toString())
//            put("isEdited", editFlag)
//            put("valType", appConfig.valType)
//            put("serviceId", appConfig.serviceId)
//            put("processingMode", if (isProcessingInGrayscale) "grayscale" else "color")
//        }
//
//        val resultCode = getResultCode(appConfig.valType)
//        intent.putExtra("metadata", metadata.toString())
//        setResult(resultCode, intent)
//
//        AppLogger.d("Sending results: code=$resultCode, metadata=$metadata")
//
//        CoroutineScope(Dispatchers.Main).launch {
//            delay(PROCESSING_DELAY_MS)
//            finish()
//        }
//    }
//
//    private fun getResultCode(valType: String): Int {
//        return when (valType) {
//            "KWH" -> OCR_KWH_RESULT_CODE
//            "KVAH" -> OCR_KVAH_RESULT_CODE
//            "SKWH" -> OCR_SKWH_RESULT_CODE
//            "SKVAH" -> OCR_SKVAH_RESULT_CODE
//            "RMD" -> OCR_RMD_RESULT_CODE
//            "LT" -> OCR_LT_RESULT_CODE
//            "IMG" -> OCR_IMG_RESULT_CODE
//            else -> OCR_INVALID_RESULT_CODE
//        }
//    }
//
//    // ===========================================
//    // UI State Management
//    // ===========================================
//
//    private fun updateUIState(state: AppState) {
//        currentState = state
//
//        when (state) {
//            is AppState.Camera -> showCameraView()
//            is AppState.Result -> showResultView(state.captureResult)
//            is AppState.Error -> showError(state.message)
//            is AppState.Processing -> showToast(state.message)
//        }
//    }
//
//    private fun showCameraView() {
//        resultLayout.visibility = View.GONE
//        previewView.visibility = View.VISIBLE
//        roiOverlay.visibility = View.VISIBLE
//        captureButton.visibility = View.VISIBLE
//        flashButton.visibility = View.VISIBLE
//        switchButton.visibility = View.VISIBLE
//        zoomSeekBar.visibility = View.VISIBLE
//        exposureSeekBar.visibility = View.GONE
//
//        cleanupBitmaps()
//        isGrayscaleDisplayMode = false
//        tapCount = 0
//    }
//
//    private fun showResultView(result: CameraManager.CaptureResult) {
//        resetCaptureUI()
//        resetProcessingUI()
//
//        // Display the captured image (use color by default)
//        val displayBitmap = result.roiBitmap
//        resultImageView.setImageBitmap(displayBitmap)
//
//        resultLayout.visibility = View.VISIBLE
//        previewView.visibility = View.GONE
//        roiOverlay.visibility = View.GONE
//        captureButton.visibility = View.GONE
//        flashButton.visibility = View.GONE
//        switchButton.visibility = View.GONE
//        zoomSeekBar.visibility = View.GONE
//        exposureSeekBar.visibility = View.GONE
//
//        // Configure buttons based on value type
//        if (appConfig.valType == "IMG") {
//            readingTextView.text = "Tap 'Save'"
//            processButton.visibility = View.GONE
//            saveButton.visibility = View.VISIBLE
//            saveButton.isEnabled = true
//        } else {
//            readingTextView.text = "Tap 'Process'"
//            processButton.visibility = View.VISIBLE
//            processButton.isEnabled = true
//            saveButton.visibility = View.VISIBLE
//            saveButton.isEnabled = false
//        }
//
//        currentMeterReading = null
//        updateConfigurationUI()
//    }
//
//    // ===========================================
//    // Utility Methods
//    // ===========================================
//
//    private fun updateConfigurationUI() {
//        serviceIdTextView.text = appConfig.serviceId
//        valueTypeTextView.text = appConfig.valType
//        resultServiceIdTextView.text = appConfig.serviceId
//        resultValueTypeTextView.text = appConfig.valType
//    }
//
//    private fun setupAccessibility() {
//        captureButton.contentDescription = "Capture meter reading"
//        flashButton.contentDescription = "Toggle flash"
//        switchButton.contentDescription = "Switch camera"
//        resultImageView.contentDescription = "Captured meter image, tap to toggle display mode"
//        readingTextView.contentDescription = "Meter reading result, tap 3 times to edit manually"
//        processButton.contentDescription = "Process image with OCR, long press for options"
//    }
//
//    private fun validateMeterReading(reading: String): Boolean {
//        return when {
//            reading.isBlank() -> false
//            reading.toDoubleOrNull() == null -> false
//            reading.length > MAX_READING_LENGTH -> false
//            else -> true
//        }
//    }
//
//    private fun resetCaptureUI() {
//        progressBar.visibility = View.GONE
//        captureButton.isEnabled = true
//    }
//
//    private fun resetProcessingUI() {
//        progressBar.visibility = View.GONE
//        processButton.isEnabled = false
//        saveButton.isEnabled = true
//    }
//
//    private fun showActivationExpiredMessage() {
//        titleTextView = findViewById(R.id.titleTextView)
//        titleTextView.text = "Activation Expired ${getVersionName()}"
//        AppLogger.d("App activation period expired")
//    }
//
//    private fun logAutoRotationStatus() {
//        val isAutoRotateEnabled = isAutoRotateOn(contentResolver)
//        AppLogger.d("Auto-rotation is ${if (isAutoRotateEnabled) "enabled" else "disabled"}")
//    }
//
//    private fun isAutoRotateOn(contentResolver: ContentResolver): Boolean {
//        return Settings.System.getInt(
//            contentResolver,
//            Settings.System.ACCELEROMETER_ROTATION,
//            0
//        ) == 1
//    }
//
//    private fun getVersionName(): String {
//        return try {
//            val packageManager = packageManager
//            val packageInfo = packageManager.getPackageInfo(packageName, 0)
//            "(Ver:${packageInfo.versionName})"
//        } catch (e: PackageManager.NameNotFoundException) {
//            AppLogger.e("Version not found", e)
//            "Version not found"
//        }
//    }
//
//    private fun showError(message: String) {
//        showToast(message)
//        AppLogger.e("Error displayed to user: $message")
//    }
//
//    private fun showToast(message: String) {
//        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
//    }
//
//    private fun cleanupBitmaps() {
//        currentCaptureResult?.let {
//            ImageCropper.safeRecycle(it.roiBitmap)
//            ImageCropper.safeRecycle(it.modelBitmap)
//            if (it.originalBitmap != it.roiBitmap && it.originalBitmap != it.modelBitmap) {
//                ImageCropper.safeRecycle(it.originalBitmap)
//            }
//        }
//
//        originalResultBitmap?.let { bitmap ->
//            if (!bitmap.isRecycled) {
//                ImageCropper.safeRecycle(bitmap)
//            }
//        }
//
//        processedGrayscaleBitmap?.let { bitmap ->
//            if (!bitmap.isRecycled) {
//                ImageCropper.safeRecycle(bitmap)
//            }
//        }
//
//        originalResultBitmap = null
//        processedGrayscaleBitmap = null
//        currentCaptureResult = null
//        currentMeterReading = null
//    }
//
//    private fun cleanupResources() {
//        cleanupBitmaps()
//
//        if (::cameraManager.isInitialized) {
//            cameraManager.shutdown()
//        }
//        if (::meterDetector.isInitialized) {
//            meterDetector.close()
//        }
//    }
//
//    // ===========================================
//    // Permission Callbacks
//    // ===========================================
//
//    override fun onPermissionsGranted() {
//        AppLogger.d("All permissions granted")
//        initializeCamera()
//    }
//
//    override fun onPermissionsDenied() {
//        AppLogger.e("Permissions denied")
//        showError("Camera and storage permissions are required")
//        finish()
//    }
//
//    // ===========================================
//    // Logger Object
//    // ===========================================
//
//    object AppLogger {
//        fun d(message: String, tag: String = TAG) {
//            if (FeatureFlags.ENABLE_ENHANCED_LOGGING) {
//                Log.d(tag, message)
//
//            }
//        }
//
//        fun e(message: String, error: Throwable? = null, tag: String = TAG) {
//            Log.e(tag, message, error)
//        }
//
//        fun logUserAction(action: String, details: Map<String, Any> = emptyMap()) {
//            if (FeatureFlags.ENABLE_ENHANCED_LOGGING) {
//                val logMessage =
//                    "UserAction: $action ${details.entries.joinToString { "${it.key}=${it.value}" }}"
//                d(logMessage)
//            }
//        }
//    }
//}