package net.duhowpi.nfccoins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * A simple bar chart view that draws side-by-side bars for two data series
 * (added = green, subtracted = red) per time slot.
 *
 * Touching the chart highlights the tapped slot and draws a tooltip showing
 * the slot label plus the added/subtracted amounts.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Entry(val label: String, val added: Int, val subtracted: Int)

    var entries: List<Entry> = emptyList()
        set(value) {
            field = value
            selectedIndex = null
            invalidate()
        }

    private var selectedIndex: Int? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val paintAdded = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_added)
    }
    private val paintSubtracted = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_subtracted)
    }
    private val paintAxis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_axis)
        strokeWidth = 2f
    }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_label)
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.chart_label_text_size)
    }
    private val paintValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chart_label)
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.chart_value_text_size)
    }
    private val paintHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 200, 200, 200)
        style = Paint.Style.FILL
    }
    private val paintTooltipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 50, 50, 50)
        style = Paint.Style.FILL
    }
    private val paintTooltipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = resources.getDimension(R.dimen.chart_label_text_size)
    }

    private val paddingLeft = resources.getDimensionPixelSize(R.dimen.chart_padding_left).toFloat()
    private val paddingBottom = resources.getDimensionPixelSize(R.dimen.chart_padding_bottom).toFloat()
    private val paddingTop = resources.getDimensionPixelSize(R.dimen.chart_padding_top).toFloat()
    private val barGap = resources.getDimensionPixelSize(R.dimen.chart_bar_gap).toFloat()

    private val rect = RectF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                updateSelection(event.x)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSelection(x: Float) {
        if (entries.isEmpty()) return
        val chartLeft = paddingLeft
        val chartRight = width.toFloat() - 8f
        if (x < chartLeft || x > chartRight) return
        val chartWidth = chartRight - chartLeft
        val slotWidth = chartWidth / entries.size
        val idx = ((x - chartLeft) / slotWidth).toInt().coerceIn(0, entries.size - 1)
        selectedIndex = idx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        val chartLeft = paddingLeft
        val chartBottom = h - paddingBottom
        val chartTop = paddingTop
        val chartRight = w - 8f
        val chartHeight = chartBottom - chartTop
        val chartWidth = chartRight - chartLeft

        // Max value for Y-axis scaling
        val maxVal = entries.maxOf { max(it.added, it.subtracted) }.coerceAtLeast(1)

        val n = entries.size
        val slotWidth = chartWidth / n
        val barWidth = (slotWidth - barGap * 3) / 2f

        // Draw axes
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, paintAxis)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, paintAxis)

        // Draw Y-axis grid lines and labels
        val gridSteps = 4
        for (step in 0..gridSteps) {
            val value = maxVal * step / gridSteps
            val y = chartBottom - (chartHeight * step / gridSteps)
            paintAxis.alpha = if (step == 0) 255 else 60
            canvas.drawLine(chartLeft, y, chartRight, y, paintAxis)
            paintAxis.alpha = 255
            paintValue.textAlign = Paint.Align.RIGHT
            canvas.drawText(value.toString(), chartLeft - 4f, y + paintValue.textSize / 3f, paintValue)
        }
        paintValue.textAlign = Paint.Align.CENTER

        // Draw bars and labels
        val labelInterval = when {
            n > 16 -> 4
            n > 8  -> 2
            else   -> 1
        }

        // Draw highlight for selected slot (behind bars)
        selectedIndex?.let { idx ->
            if (idx in entries.indices) {
                val slotX = chartLeft + idx * slotWidth
                canvas.drawRect(slotX, chartTop, slotX + slotWidth, chartBottom, paintHighlight)
            }
        }

        for (i in entries.indices) {
            val entry = entries[i]
            val slotX = chartLeft + i * slotWidth

            // Added bar (green, left)
            val addedH = if (maxVal > 0) chartHeight * entry.added / maxVal else 0f
            rect.set(
                slotX + barGap,
                chartBottom - addedH,
                slotX + barGap + barWidth,
                chartBottom
            )
            canvas.drawRect(rect, paintAdded)

            // Subtracted bar (red, right)
            val subH = if (maxVal > 0) chartHeight * entry.subtracted / maxVal else 0f
            rect.set(
                slotX + barGap * 2 + barWidth,
                chartBottom - subH,
                slotX + barGap * 2 + barWidth * 2,
                chartBottom
            )
            canvas.drawRect(rect, paintSubtracted)

            // X-axis label centered in the slot (only every Nth to avoid crowding)
            if (i % labelInterval == 0) {
                val labelX = slotX + slotWidth / 2f
                canvas.drawText(entry.label, labelX, chartBottom + paintLabel.textSize + 4f, paintLabel)
            }
        }

        // Draw tooltip for selected slot (on top of everything)
        selectedIndex?.let { idx ->
            if (idx in entries.indices) {
                val entry = entries[idx]
                val slotX = chartLeft + idx * slotWidth
                val centerX = slotX + slotWidth / 2f

                val line1 = entry.label
                val line2 = "+${entry.added} / -${entry.subtracted}"
                val textWidth = maxOf(
                    paintTooltipText.measureText(line1),
                    paintTooltipText.measureText(line2)
                )
                val tipPad = 8f
                val lineHeight = paintTooltipText.textSize + 4f
                val boxWidth = textWidth + tipPad * 2
                val boxHeight = lineHeight * 2 + tipPad * 2

                var boxLeft = centerX - boxWidth / 2f
                var boxRight = boxLeft + boxWidth
                if (boxLeft < chartLeft) { boxLeft = chartLeft; boxRight = boxLeft + boxWidth }
                if (boxRight > chartRight) { boxRight = chartRight; boxLeft = boxRight - boxWidth }

                val boxTop = chartTop + 2f
                val boxBottom = boxTop + boxHeight

                canvas.drawRoundRect(boxLeft, boxTop, boxRight, boxBottom, 6f, 6f, paintTooltipBg)
                val textX = (boxLeft + boxRight) / 2f
                canvas.drawText(line1, textX, boxTop + tipPad + paintTooltipText.textSize, paintTooltipText)
                canvas.drawText(line2, textX, boxTop + tipPad + paintTooltipText.textSize + lineHeight, paintTooltipText)
            }
        }
    }
}
