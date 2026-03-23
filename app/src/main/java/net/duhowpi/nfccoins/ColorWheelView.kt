package net.duhowpi.nfccoins

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * A color picker view that shows:
 *  - An outer hue ring (touch to select hue)
 *  - An inner saturation/value square (touch to select saturation and brightness)
 *  - A small circle indicator for each selection area
 *
 * The current color is exposed via [getColor] and [setColor].
 * [onColorChanged] is called whenever the color changes.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onColorChanged: ((Int) -> Unit)? = null

    // Current HSV: [hue 0-360, saturation 0-1, value 0-1]
    private val hsv = floatArrayOf(0f, 1f, 1f)

    // Geometry – computed in onSizeChanged
    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var ringWidth = 0f
    private var innerRadius = 0f
    private var svRect = RectF()

    // Hue ring drawing
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    // SV square bitmap (rebuilt when hue changes)
    private var svBitmap: Bitmap? = null
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    // Indicator circles (drawn on top of ring and square)
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val indicatorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Touch tracking
    private var touchTarget: TouchTarget = TouchTarget.NONE
    private enum class TouchTarget { NONE, HUE_RING, SV_SQUARE }

    /** Set the displayed color, updating internal HSV state. */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        svBitmap = null // force rebuild for new hue
        invalidate()
    }

    /** Returns the currently selected color as an ARGB integer. */
    fun getColor(): Int = Color.HSVToColor(hsv)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = resolveSize(
            (MIN_SIZE_DP * resources.displayMetrics.density).toInt(),
            min(widthMeasureSpec, heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        outerRadius = min(w, h) / 2f - PADDING_DP * resources.displayMetrics.density
        ringWidth = outerRadius * RING_FRACTION
        innerRadius = outerRadius - ringWidth

        // SV square inscribed inside the inner circle (side = innerRadius * sqrt(2))
        val halfSide = innerRadius * 0.707f * SV_SQUARE_FRACTION
        svRect = RectF(
            centerX - halfSide, centerY - halfSide,
            centerX + halfSide, centerY + halfSide
        )

        ringPaint.strokeWidth = ringWidth
        ringPaint.shader = buildHueShader()
        svBitmap = null // rebuild on next draw
    }

    override fun onDraw(canvas: Canvas) {
        // --- Hue ring ---
        val ringRadius = outerRadius - ringWidth / 2f
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)

        // --- SV square ---
        val bmp = svBitmap ?: buildSvBitmap().also { svBitmap = it }
        canvas.drawBitmap(bmp, null, svRect, svPaint)

        // --- Hue indicator on ring ---
        val hueAngle = Math.toRadians(hsv[0].toDouble() - 90.0)
        val hueX = (centerX + ringRadius * cos(hueAngle)).toFloat()
        val hueY = (centerY + ringRadius * sin(hueAngle)).toFloat()
        drawIndicator(canvas, hueX, hueY)

        // --- SV indicator on square ---
        val svX = svRect.left + hsv[1] * svRect.width()
        val svY = svRect.top + (1f - hsv[2]) * svRect.height()
        drawIndicator(canvas, svX, svY)
    }

    private fun drawIndicator(canvas: Canvas, x: Float, y: Float) {
        indicatorFillPaint.color = Color.WHITE
        canvas.drawCircle(x, y, INDICATOR_RADIUS_DP * resources.displayMetrics.density, indicatorFillPaint)
        indicatorPaint.color = Color.DKGRAY
        canvas.drawCircle(x, y, INDICATOR_RADIUS_DP * resources.displayMetrics.density, indicatorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx, dy)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchTarget = when {
                    dist in (innerRadius)..(outerRadius) -> TouchTarget.HUE_RING
                    svRect.contains(x, y) -> TouchTarget.SV_SQUARE
                    else -> TouchTarget.NONE
                }
            }
            MotionEvent.ACTION_MOVE -> { /* use existing target */ }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchTarget = TouchTarget.NONE
                return true
            }
        }

        when (touchTarget) {
            TouchTarget.HUE_RING -> {
                // Angle from center, starting at 12 o'clock, going clockwise
                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                if (angle < 0) angle += 360f
                if (angle >= 360f) angle -= 360f
                hsv[0] = angle
                svBitmap = null // rebuild SV square for new hue
                invalidate()
                onColorChanged?.invoke(getColor())
            }
            TouchTarget.SV_SQUARE -> {
                hsv[1] = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                hsv[2] = (1f - (y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
                invalidate()
                onColorChanged?.invoke(getColor())
            }
            TouchTarget.NONE -> {}
        }

        return true
    }

    private fun buildHueShader(): SweepGradient {
        // SweepGradient starts at 3 o'clock; offset by -90° to put red at top (12 o'clock)
        val matrix = android.graphics.Matrix()
        matrix.postRotate(-90f, centerX, centerY)
        return SweepGradient(centerX, centerY, HUE_COLORS, null).also { it.setLocalMatrix(matrix) }
    }

    private fun buildSvBitmap(): Bitmap {
        val size = SV_BITMAP_SIZE
        val pixels = IntArray(size * size)
        val tempHsv = FloatArray(3)
        tempHsv[0] = hsv[0]
        for (y in 0 until size) {
            val value = 1f - y.toFloat() / (size - 1)
            for (x in 0 until size) {
                tempHsv[1] = x.toFloat() / (size - 1)
                tempHsv[2] = value
                pixels[y * size + x] = Color.HSVToColor(tempHsv)
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }

    companion object {
        private const val MIN_SIZE_DP = 260
        private const val PADDING_DP = 8f
        private const val RING_FRACTION = 0.18f      // ring is 18% of outer radius
        private const val SV_SQUARE_FRACTION = 0.95f // square fills 95% of inscribed square
        private const val INDICATOR_RADIUS_DP = 6f
        private const val SV_BITMAP_SIZE = 128        // pixels for SV square bitmap

        // Precomputed hue gradient colors (constant, no need to rebuild per instance)
        private val HUE_COLORS = IntArray(361) { i ->
            Color.HSVToColor(floatArrayOf(i.toFloat() % 360f, 1f, 1f))
        }
    }
}
