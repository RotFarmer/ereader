package com.rotfarmer.ereader.sketch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** The same page treatment at full size and in the library preview. */
object SketchPaper {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, rect: RectF, style: Int, density: Float) {
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        paint.color = Color.WHITE
        canvas.drawRect(rect, paint)
        val step = rect.width() / 24f
        paint.color = 0xFFD8DFDF.toInt()
        paint.strokeWidth = (0.8f * density).coerceAtLeast(0.5f)
        paint.style = Paint.Style.STROKE
        if (style == 1 || style == 2) {
            var y = rect.top + step
            while (y < rect.bottom) { canvas.drawLine(rect.left, y, rect.right, y, paint); y += step }
        }
        if (style == 2) {
            var x = rect.left + step
            while (x < rect.right) { canvas.drawLine(x, rect.top, x, rect.bottom, paint); x += step }
        }
        if (style == 3) {
            paint.style = Paint.Style.FILL
            var x = rect.left + step
            while (x < rect.right) {
                var y = rect.top + step
                while (y < rect.bottom) { canvas.drawCircle(x, y, (0.8f * density).coerceAtLeast(0.5f), paint); y += step }
                x += step
            }
        }
        paint.style = Paint.Style.STROKE
        paint.color = 0xFFBFC5C0.toInt()
        paint.strokeWidth = (1f * density).coerceAtLeast(0.5f)
        canvas.drawRect(rect, paint)
    }
}
