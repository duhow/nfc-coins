package net.duhowpi.nfccoins

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * A simple bar chart view that draws side-by-side bars for two data series
 * (added = green, subtracted = red) per time slot.
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
            invalidate()
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

    private val paddingLeft = resources.getDimensionPixelSize(R.dimen.chart_padding_left).toFloat()
    private val paddingBottom = resources.getDimensionPixelSize(R.dimen.chart_padding_bottom).toFloat()
    private val paddingTop = resources.getDimensionPixelSize(R.dimen.chart_padding_top).toFloat()
    private val barGap = resources.getDimensionPixelSize(R.dimen.chart_bar_gap).toFloat()

    private val rect = RectF()

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
    }
}
