package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

class BoundingBoxImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var boxes: List<BoundingBox> = emptyList()

    // Paint used for drawing bounding boxes
    private val boxPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // Reusable RectF to avoid allocation in onDraw
    private val drawableRect = RectF()

    fun setBoundingBoxes(boxes: List<BoundingBox>) {
        this.boxes = boxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Return early if no drawable or boxes
        val drawable = drawable ?: return

        drawableRect.set(
            0f, 0f,
            drawable.intrinsicWidth.toFloat(),
            drawable.intrinsicHeight.toFloat()
        )

        val imageMatrix = imageMatrix
        imageMatrix.mapRect(drawableRect)

        val scaleX = drawableRect.width()
        val scaleY = drawableRect.height()
        val dx = drawableRect.left
        val dy = drawableRect.top

        for (box in boxes) {
            val left = box.x1 * scaleX + dx
            val top = box.y1 * scaleY + dy
            val right = box.x2 * scaleX + dx
            val bottom = box.y2 * scaleY + dy
            canvas.drawRect(left, top, right, bottom, boxPaint)
        }
    }
}
