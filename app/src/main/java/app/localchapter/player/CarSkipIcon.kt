package app.localchapter.player

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable

internal class CarSkipIcon(
    seconds: Long,
    private val forward: Boolean,
    private val size: Int,
    private val colors: ColorStateList
) : Drawable() {
    private val number = seconds.toString()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arc = RectF(8f, 8f, 40f, 40f)
    private val arrow = Path().apply {
        moveTo(29f, 13f)
        lineTo(35f, 13f)
        lineTo(35f, 7f)
    }

    override fun draw(canvas: Canvas) {
        val side = minOf(bounds.width(), bounds.height()).toFloat()
        if (side <= 0) return
        paint.color = colors.getColorForState(state, colors.defaultColor)
        paint.alpha = alpha
        canvas.save()
        canvas.translate(bounds.exactCenterX() - side / 2, bounds.exactCenterY() - side / 2)
        canvas.scale(side / 48f, side / 48f)
        canvas.save()
        if (!forward) canvas.scale(-1f, 1f, 24f, 24f)
        paint.style = Paint.Style.STROKE
        canvas.drawArc(arc, 10f, 305f, false, paint)
        canvas.drawPath(arrow, paint)
        canvas.restore()

        paint.style = Paint.Style.FILL
        paint.textSize = 15f
        val width = paint.measureText(number)
        if (width > 25f) paint.textSize *= 25f / width
        canvas.drawText(number, 24f, 24f - (paint.ascent() + paint.descent()) / 2, paint)
        canvas.restore()
    }

    private var opacity = 255
    override fun getAlpha() = opacity
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun isStateful() = true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
}
