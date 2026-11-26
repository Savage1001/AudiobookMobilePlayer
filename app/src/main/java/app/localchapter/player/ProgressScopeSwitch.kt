package app.localchapter.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.RadioButton
import kotlin.math.abs
import kotlin.math.roundToInt

internal class ProgressScopeSwitch(
    context: Context,
    wholeBook: Boolean,
    private val onScopeChanged: (Boolean) -> Unit
) : LinearLayout(context) {
    private val inset = dp(3)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.player_scope_highlight)
    }
    private val bounds = RectF()
    private var bookSelected = wholeBook
    private var fraction = if (wholeBook) 1f else 0f
    private var animation: ValueAnimator? = null
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var grabOffset = 0f
    private var dragging = false
    private val chapterButton = segment("Chapter", R.drawable.ic_scope_chapter, false)
    private val bookButton = segment("Book", R.drawable.ic_scope_book, true)

    init {
        orientation = HORIZONTAL
        isBaselineAligned = false
        setPadding(inset, inset, inset, inset)
        background = GradientDrawable().apply {
            cornerRadius = dp(11).toFloat()
            setColor(context.getColor(R.color.player_background))
            setStroke(dp(1), context.getColor(R.color.player_border))
        }
        setWillNotDraw(false)
        addView(chapterButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(bookButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        updateChecked()
    }

    private fun segment(label: String, iconId: Int, book: Boolean) = RadioButton(context).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.player_text))
        buttonDrawable = null
        background = null
        minimumHeight = dp(48)
        setPaddingRelative(dp(12), 0, dp(12), 0)
        val icon = context.getDrawable(iconId)?.mutate()?.apply {
            setTint(context.getColor(R.color.player_muted))
            setBounds(0, 0, dp(17), dp(17))
        }
        setCompoundDrawablesRelative(icon, null, null, null)
        compoundDrawablePadding = dp(6)
        setOnClickListener { select(book) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val segmentWidth = segmentWidth()
        val position = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - fraction else fraction
        val left = paddingLeft + segmentWidth * position
        bounds.set(left, inset.toFloat(), left + segmentWidth, (height - inset).toFloat())
        canvas.drawRoundRect(bounds, dp(8).toFloat(), dp(8).toFloat(), highlight)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(0)
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) return false
                val x = event.getX(index)
                val dx = abs(x - downX)
                val dy = abs(event.getY(index) - downY)
                if (dx > touchSlop && dx > dy && segmentWidth() > 0) {
                    animation?.cancel()
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val start = positionAt(downX)
                    val touchedHighlight = abs(start - fraction) <= 0.5f
                    grabOffset = if (touchedHighlight) fraction - start else 0f
                    moveHighlight(x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointer = MotionEvent.INVALID_POINTER_ID
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        val index = event.findPointerIndex(pointer)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (index >= 0) moveHighlight(event.getX(index))
            MotionEvent.ACTION_UP -> {
                if (index >= 0) moveHighlight(event.getX(index))
                finishDrag(if (fraction == 0.5f) bookSelected else fraction > 0.5f)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) finishDrag(bookSelected)
            }
            MotionEvent.ACTION_CANCEL -> finishDrag(bookSelected)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        animation?.cancel()
        dragging = false
        pointer = MotionEvent.INVALID_POINTER_ID
        fraction = if (bookSelected) 1f else 0f
        super.onDetachedFromWindow()
    }

    private fun finishDrag(book: Boolean) {
        dragging = false
        pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        select(book)
    }

    private fun moveHighlight(x: Float) {
        fraction = (positionAt(x) + grabOffset).coerceIn(0f, 1f)
        invalidate()
    }

    private fun positionAt(x: Float): Float {
        val position = (x - paddingLeft) / segmentWidth() - 0.5f
        return if (layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f - position else position
    }

    private fun segmentWidth() = (width - paddingLeft - paddingRight) / 2f

    private fun select(book: Boolean) {
        val changed = bookSelected != book
        bookSelected = book
        updateChecked()
        val target = if (book) 1f else 0f
        animation?.cancel()
        if (fraction != target) {
            animation = ValueAnimator.ofFloat(fraction, target).apply {
                duration = 180L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (changed) onScopeChanged(book)
    }

    private fun updateChecked() {
        chapterButton.isChecked = !bookSelected
        bookButton.isChecked = bookSelected
    }

    private fun dp(size: Int) = (size * resources.displayMetrics.density).roundToInt()
}
