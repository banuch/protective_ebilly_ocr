
package com.protective.ebillyocr
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.toColorInt

/**
 * Custom view for displaying a Region of Interest (ROI) overlay on the camera preview.
 * This creates a white overlay with a clear rectangle for the ROI.
 */
class ROIOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ROI parameters as percentages of view dimensions
    var roiX = 0.10f  // X position from left (10% of width)
    var roiY = 0.25f  // Y position from top (25% of height)
    var roiWidth = 0.80f  // Width (80% of view width)
    var roiHeight = 0.20f  // Height (20% of view height)

    // Paint objects for drawing
    private val overlayPaint = Paint().apply {
//        color = Color.WHITE  // White for areas outside ROI
        color = "#FFFFFF".toColorInt()  // White for areas outside ROI
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val cornerPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // ROI rectangle in view coordinates
    private var roiRect = RectF()

    // Corner marker size
    private val cornerSize = 40f

    // Flag to show/hide the overlay
    var showOverlay = true
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!showOverlay) return

        // Calculate ROI rectangle
        roiRect.set(
            width * roiX,
            height * roiY,
            width * roiX + width * roiWidth,
            height * roiY + height * roiHeight
        )

        // Create path for white overlay with a cutout for the ROI
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(roiRect, Path.Direction.CCW)
        }

        // Draw white overlay (outside of ROI)
        canvas.drawPath(path, overlayPaint)

        // Draw ROI rectangle border
        canvas.drawRect(roiRect, borderPaint)

        // Draw corner markers
        // Top-left corner
        canvas.drawLine(
            roiRect.left, roiRect.top,
            roiRect.left + cornerSize, roiRect.top,
            cornerPaint
        )
        canvas.drawLine(
            roiRect.left, roiRect.top,
            roiRect.left, roiRect.top + cornerSize,
            cornerPaint
        )

        // Top-right corner
        canvas.drawLine(
            roiRect.right, roiRect.top,
            roiRect.right - cornerSize, roiRect.top,
            cornerPaint
        )
        canvas.drawLine(
            roiRect.right, roiRect.top,
            roiRect.right, roiRect.top + cornerSize,
            cornerPaint
        )

        // Bottom-left corner
        canvas.drawLine(
            roiRect.left, roiRect.bottom,
            roiRect.left + cornerSize, roiRect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            roiRect.left, roiRect.bottom,
            roiRect.left, roiRect.bottom - cornerSize,
            cornerPaint
        )

        // Bottom-right corner
        canvas.drawLine(
            roiRect.right, roiRect.bottom,
            roiRect.right - cornerSize, roiRect.bottom,
            cornerPaint
        )
        canvas.drawLine(
            roiRect.right, roiRect.bottom,
            roiRect.right, roiRect.bottom - cornerSize,
            cornerPaint
        )
    }

    /**
     * Get the ROI rectangle in view coordinates
     */
    fun getROIRect(): RectF {
        return RectF(
            width * roiX,
            height * roiY,
            width * roiX + width * roiWidth,
            height * roiY + height * roiHeight
        )
    }

    /**
     * Clear any detection results - used in the original app for clearing detection boxes
     */
    fun clear() {
        // This method is kept to maintain compatibility with the original code
        invalidate()
    }
}
