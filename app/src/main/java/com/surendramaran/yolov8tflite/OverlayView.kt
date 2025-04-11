package com.surendramaran.yolov8tflite

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var results: List<BoundingBox> = emptyList()
    private var imageWidth: Int = 1  // prevent divide-by-zero
    private var imageHeight: Int = 1

    private val boxPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun setResults(boxes: List<BoundingBox>, imgWidth: Int, imgHeight: Int) {
        results = boxes
        imageWidth = imgWidth
        imageHeight = imgHeight
        invalidate()
    }

    fun clear() {
        results = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (results.isEmpty()) return

        // Calculate scaling factors from image to view
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        for (box in results) {
            val left = box.x1 * imageWidth * scaleX
            val top = box.y1 * imageHeight * scaleY
            val right = box.x2 * imageWidth * scaleX
            val bottom = box.y2 * imageHeight * scaleY

            // Draw bounding box
            canvas.drawRect(left, top, right, bottom, boxPaint)

            // Draw class label
            canvas.drawText(box.clsName, left, top - 10, textPaint)
        }
    }
}

//package com.surendramaran.yolov8tflite
//
//import android.content.Context
//import android.graphics.Canvas
//import android.graphics.Color
//import android.graphics.Paint
//import android.graphics.Rect
//import android.util.AttributeSet
//import android.view.View
//import androidx.core.content.ContextCompat
//
//class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {
//
//    private var results = listOf<BoundingBox>()
//    private var boxPaint = Paint()
//    private var textBackgroundPaint = Paint()
//    private var textPaint = Paint()
//    private var imageWidth = 1
//    private var imageHeight = 1
//
//    private var bounds = Rect()
//
//    init {
//        initPaints()
//    }
//
//    fun clear() {
//        results = listOf()
//        textPaint.reset()
//        textBackgroundPaint.reset()
//        boxPaint.reset()
//        invalidate()
//        initPaints()
//    }
//
//    private fun initPaints() {
//        textBackgroundPaint.color = Color.BLACK
//        textBackgroundPaint.style = Paint.Style.FILL
//        textBackgroundPaint.textSize = 50f
//
//        textPaint.color = Color.WHITE
//        textPaint.style = Paint.Style.FILL
//        textPaint.textSize = 50f
//
//        boxPaint.color = ContextCompat.getColor(context!!, R.color.bounding_box_color)
//        boxPaint.strokeWidth = 8F
//        boxPaint.style = Paint.Style.STROKE
//    }
//
//    override fun draw(canvas: Canvas) {
//        super.draw(canvas)
//
//        results.forEach {
//            val scaleX = width.toFloat() / imageWidth
//            val scaleY = height.toFloat() / imageHeight
//
//            val left = it.x1 * imageWidth * scaleX
//            val top = it.y1 * imageHeight * scaleY
//            val right = it.x2 * imageWidth * scaleX
//            val bottom = it.y2 * imageHeight * scaleY
//
//
//            canvas.drawRect(left, top, right, bottom, boxPaint)
//            val drawableText = "${it.clsName}"
//
//            textBackgroundPaint.getTextBounds(drawableText, 0, drawableText.length, bounds)
//            val textWidth = bounds.width()
//            val textHeight = bounds.height()
//            canvas.drawRect(
//                left,
//                top,
//                left + textWidth + BOUNDING_RECT_TEXT_PADDING,
//                top + textHeight + BOUNDING_RECT_TEXT_PADDING,
//                textBackgroundPaint
//            )
//            canvas.drawText(drawableText, left, top + bounds.height(), textPaint)
//        }
//    }
//
//    fun setResults(boundingBoxes: List<BoundingBox>, imageWidth: Int, imageHeight: Int) {
//        results = boundingBoxes
//        this.imageWidth = imageWidth
//        this.imageHeight = imageHeight
//        invalidate()
//    }
//
//    companion object {
//        private const val BOUNDING_RECT_TEXT_PADDING = 8
//    }
//}