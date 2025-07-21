package com.protective.ebillyocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Utility class for handling image cropping, resizing, and transformation operations
 */
object ImageCropper {
    private const val TAG = "ImageCropper"

    /**
     * Crop a bitmap to the specified Region of Interest
     *
     * @param sourceBitmap The original bitmap to crop
     * @param roi The Region of Interest in the view coordinates
     * @param viewWidth The width of the view containing the ROI
     * @param viewHeight The height of the view containing the ROI
     * @param recycleSource Whether to recycle the source bitmap after cropping
     * @return The cropped bitmap or null if an error occurs
     */
    fun cropToROI(
        sourceBitmap: Bitmap,
        roi: RectF,
        viewWidth: Int,
        viewHeight: Int,
        recycleSource: Boolean = false
    ): Bitmap? {
        if (sourceBitmap.isRecycled) {
            Log.e(TAG, "Cannot crop a recycled bitmap")
            return null
        }

        try {
            // Calculate scaling factors between bitmap and view
            val scaleX = sourceBitmap.width.toFloat() / viewWidth
            val scaleY = sourceBitmap.height.toFloat() / viewHeight

            // Calculate crop rectangle in image coordinates
            val cropX = (roi.left * scaleX).roundToInt()
            val cropY = (roi.top * scaleY).roundToInt()
            val cropWidth = (roi.width() * scaleX).roundToInt()
            val cropHeight = (roi.height() * scaleY).roundToInt()

            // Ensure crop bounds are within the image
            val safeX = cropX.coerceIn(0, sourceBitmap.width - 1)
            val safeY = cropY.coerceIn(0, sourceBitmap.height - 1)
            val safeWidth = cropWidth.coerceIn(1, sourceBitmap.width - safeX)
            val safeHeight = cropHeight.coerceIn(1, sourceBitmap.height - safeY)

            // Crop the bitmap
            val result = Bitmap.createBitmap(
                sourceBitmap,
                safeX,
                safeY,
                safeWidth,
                safeHeight
            )

            // Recycle source if requested and the result is different from source
            if (recycleSource && result != sourceBitmap && !sourceBitmap.isRecycled) {
                sourceBitmap.recycle()
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error cropping bitmap: ${e.message}", e)
            // Don't recycle on error as the caller might still need the source bitmap
            return null
        }
    }

    /**
     * Resize a bitmap to the specified dimensions
     *
     * @param bitmap The bitmap to resize
     * @param targetWidth The target width
     * @param targetHeight The target height
     * @param recycleSource Whether to recycle the source bitmap after resizing
     * @return The resized bitmap or null if an error occurs
     */
    fun resizeBitmap(
        bitmap: Bitmap,
        targetWidth: Int,
        targetHeight: Int,
        recycleSource: Boolean = false
    ): Bitmap? {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Cannot resize a recycled bitmap")
            return null
        }

        try {
            // Don't resize if dimensions already match
            if (bitmap.width == targetWidth && bitmap.height == targetHeight) {
                return bitmap
            }

            val result = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)

            // Recycle source if requested and the result is different from source
            if (recycleSource && result != bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error resizing bitmap: ${e.message}", e)
            return null
        }
    }

    /**
     * Crop the image to ROI and place it on a background of specified size
     * This preserves the aspect ratio of the ROI while creating a standard-sized input
     *
     * @param sourceBitmap The original bitmap to crop
     * @param roi The Region of Interest in the view coordinates
     * @param viewWidth The width of the view containing the ROI
     * @param viewHeight The height of the view containing the ROI
     * @param targetWidth The width of the output image (e.g., 640)
     * @param targetHeight The height of the output image (e.g., 640)
     * @param backgroundColor The color to use for the background (default: gray)
     * @param recycleSource Whether to recycle the source bitmap after processing
     * @return A bitmap of size targetWidth x targetHeight with the ROI content centered on a colored background
     */
    fun cropToROIWithBackground(
        sourceBitmap: Bitmap,
        roi: RectF,
        viewWidth: Int,
        viewHeight: Int,
        targetWidth: Int = 640,
        targetHeight: Int = 640,
        backgroundColor: Int = Color.GRAY,
        recycleSource: Boolean = false
    ): Bitmap? {
        if (sourceBitmap.isRecycled) {
            Log.e(TAG, "Cannot process a recycled bitmap")
            return null
        }

        var croppedBitmap: Bitmap? = null
        var scaledBitmap: Bitmap? = null
        var resultBitmap: Bitmap? = null

        try {
            // First crop to ROI
            croppedBitmap = cropToROI(sourceBitmap, roi, viewWidth, viewHeight, recycleSource)
                ?: return null // Return null if cropping failed

            // Create a new bitmap with the target dimensions and fill with background color
            resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(resultBitmap)
            canvas.drawColor(backgroundColor)

            // Calculate scaling to fit the cropped image within the target dimensions while preserving aspect ratio
            val scale = min(
                targetWidth.toFloat() / croppedBitmap.width,
                targetHeight.toFloat() / croppedBitmap.height
            )

            // Calculate dimensions after scaling
            val scaledWidth = (croppedBitmap.width * scale).toInt()
            val scaledHeight = (croppedBitmap.height * scale).toInt()

            // Scale the cropped bitmap
            scaledBitmap = Bitmap.createScaledBitmap(
                croppedBitmap,
                scaledWidth,
                scaledHeight,
                true
            )

            // Calculate position to center the scaled bitmap
            val left = (targetWidth - scaledWidth) / 2f
            val top = (targetHeight - scaledHeight) / 2f

            // Draw the scaled bitmap onto the center of the background
            canvas.drawBitmap(scaledBitmap, left, top, null)

            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error in cropToROIWithBackground: ${e.message}", e)
            resultBitmap?.recycle()
            return null
        } finally {
            // Clean up temporary bitmaps in all cases
            try {
                if (croppedBitmap != null && croppedBitmap != sourceBitmap && !croppedBitmap.isRecycled) {
                    croppedBitmap.recycle()
                }
                if (scaledBitmap != null && scaledBitmap != croppedBitmap && !scaledBitmap.isRecycled) {
                    scaledBitmap.recycle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recycling temporary bitmaps: ${e.message}", e)
            }
        }
    }

    /**
     * Rotate a bitmap by the specified angle
     *
     * @param bitmap The bitmap to rotate
     * @param rotationDegrees The rotation angle in degrees
     * @param flipHorizontal Whether to flip the image horizontally
     * @param recycleSource Whether to recycle the source bitmap after rotation
     * @return The rotated bitmap or null if an error occurs
     */
    fun rotateBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        flipHorizontal: Boolean = false,
        recycleSource: Boolean = false
    ): Bitmap? {
        if (bitmap.isRecycled) {
            Log.e(TAG, "Cannot rotate a recycled bitmap")
            return null
        }

        try {
            // Skip rotation if angle is 0 and no flip is needed
            if (rotationDegrees == 0 && !flipHorizontal) {
                return bitmap
            }

            val matrix = Matrix().apply {
                postRotate(rotationDegrees.toFloat())
                if (flipHorizontal) {
                    postScale(-1f, 1f)
                }
            }

            val result = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            // Recycle source if requested and the result is different from source
            if (recycleSource && result != bitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }

            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error rotating bitmap: ${e.message}", e)
            return null
        }
    }

    /**
     * Safely recycle a bitmap if it's not null and not already recycled
     *
     * @param bitmap The bitmap to recycle
     * @return true if successfully recycled, false otherwise
     */
    fun safeRecycle(bitmap: Bitmap?): Boolean {
        return try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recycling bitmap: ${e.message}", e)
            false
        }
    }
}