package net.duhowpi.nfccoins

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class OperationsHistoryActivity : AppCompatActivity() {

    private lateinit var db: TransactionDatabase
    private lateinit var chartView: BarChartView
    private lateinit var tvNoData: TextView
    private lateinit var togglePeriod: MaterialButtonToggleGroup
    private lateinit var tableLayout: TableLayout
    private lateinit var tvNavLabel: TextView
    private lateinit var btnNavPrev: ImageButton
    private lateinit var btnNavNext: ImageButton
    private lateinit var tvSummary: TextView

    /** How many days back from today the hourly view is showing (0 = today). */
    private var dayOffset: Int = 0

    /** How many weeks back from the current week the weekly view is showing (0 = this week). */
    private var weekOffset: Int = 0

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdvancedSettingsActivity.wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operations_history)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ops_history_title)

        db = TransactionDatabase(this)
        chartView    = findViewById(R.id.chartView)
        tvNoData     = findViewById(R.id.tvNoData)
        togglePeriod = findViewById(R.id.togglePeriod)
        tableLayout  = findViewById(R.id.tableButtonStats)
        tvNavLabel   = findViewById(R.id.tvNavLabel)
        btnNavPrev   = findViewById(R.id.btnNavPrev)
        btnNavNext   = findViewById(R.id.btnNavNext)
        tvSummary    = findViewById(R.id.tvSummary)

        btnNavPrev.setOnClickListener {
            if (isWeeklyMode()) weekOffset++ else dayOffset++
            loadData()
        }
        btnNavNext.setOnClickListener {
            if (isWeeklyMode()) {
                if (weekOffset > 0) weekOffset--
            } else {
                if (dayOffset > 0) dayOffset--
            }
            loadData()
        }

        togglePeriod.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                // Reset offsets when switching mode
                dayOffset = 0
                weekOffset = 0
                loadData()
            }
        }

        // Default: hourly view
        togglePeriod.check(R.id.btnHourly)
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun isWeeklyMode() = togglePeriod.checkedButtonId == R.id.btnWeekly

    private fun loadData() {
        if (isWeeklyMode()) loadWeeklyData() else loadHourlyData()
        loadButtonStats()
        updateNavButtons()
    }

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    private fun updateNavButtons() {
        val offset = if (isWeeklyMode()) weekOffset else dayOffset
        btnNavNext.isEnabled = offset > 0
        btnNavNext.alpha = if (offset > 0) 1f else 0.3f
    }

    private fun buildDayLabel(dayOffset: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -dayOffset)
        val fmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())
        return fmt.format(cal.time)
    }

    private fun buildWeekLabel(weekOffset: Int): String {
        val cal = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, -weekOffset)
            val dow = get(Calendar.DAY_OF_WEEK)
            val daysFromMon = (dow + 5) % 7
            add(Calendar.DAY_OF_YEAR, -daysFromMon)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val monFmt = SimpleDateFormat("d MMM", Locale.getDefault())
        val sunFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        val monStr = monFmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val sunStr = sunFmt.format(cal.time)
        return "$monStr – $sunStr"
    }

    // -------------------------------------------------------------------------
    // Hourly view
    // -------------------------------------------------------------------------

    private fun loadHourlyData() {
        tvNavLabel.text = buildDayLabel(dayOffset)

        val stats = db.getHourlyStats(dayOffset)
        val entries = stats.map { s ->
            BarChartView.Entry("${s.hour}h", s.added, s.subtracted)
        }
        updateChart(entries)

        val summary = db.getDaySummary(dayOffset)
        updateSummary(summary)
    }

    // -------------------------------------------------------------------------
    // Weekly view
    // -------------------------------------------------------------------------

    private fun loadWeeklyData() {
        tvNavLabel.text = buildWeekLabel(weekOffset)

        val stats = db.getDailyStats(weekOffset, Locale.getDefault())
        val entries = stats.map { s ->
            BarChartView.Entry(s.dayLabel, s.added, s.subtracted)
        }
        updateChart(entries)

        val summary = db.getWeekSummary(weekOffset)
        updateSummary(summary)
    }

    // -------------------------------------------------------------------------
    // Chart
    // -------------------------------------------------------------------------

    private fun updateChart(entries: List<BarChartView.Entry>) {
        val hasData = entries.any { it.added > 0 || it.subtracted > 0 }
        tvNoData.visibility = if (hasData) View.GONE else View.VISIBLE
        chartView.visibility = if (hasData) View.VISIBLE else View.GONE
        chartView.entries = entries
    }

    // -------------------------------------------------------------------------
    // Summary row
    // -------------------------------------------------------------------------

    private fun updateSummary(summary: PeriodSummary) {
        tvSummary.text = getString(
            R.string.ops_summary,
            summary.added, summary.addOps,
            summary.subtracted, summary.subtractOps
        )
    }

    // -------------------------------------------------------------------------
    // Button (price) stats table
    // -------------------------------------------------------------------------

    private fun loadButtonStats() {
        val stats = db.getButtonStats()

        while (tableLayout.childCount > 1) tableLayout.removeViewAt(1)

        if (stats.isEmpty()) {
            val row = TableRow(this)
            val cell = TextView(this).apply {
                text = getString(R.string.ops_no_button_data)
                setPadding(8, 8, 8, 8)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2)
            }
            row.addView(cell)
            tableLayout.addView(row)
            return
        }

        val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
        for (stat in stats) {
            val row = TableRow(this)
            row.setPadding(0, 4, 0, 4)

            // Show price as absolute positive value
            val unit1 = if (isDecimalMode) 100 else 1
            val unit2 = if (isDecimalMode) 200 else 2
            val priceText = when (stat.amount) {
                unit1 -> "1"
                unit2 -> "2"
                else  -> stat.amount.toString()
            }

            row.addView(makeCell(priceText, isBold = true))
            row.addView(makeCell(stat.countLastHour.toString()))
            row.addView(makeCell(stat.countLastDay.toString()))
            row.addView(makeCell(stat.countLastWeek.toString()))
            tableLayout.addView(row)
        }

        // Total row
        val totalHour = stats.sumOf { it.countLastHour }
        val totalDay  = stats.sumOf { it.countLastDay }
        val totalWeek = stats.sumOf { it.countLastWeek }
        val totalRow  = TableRow(this)
        totalRow.setPadding(0, 4, 0, 4)
        totalRow.addView(makeCell(getString(R.string.ops_col_total), isBold = true))
        totalRow.addView(makeCell(totalHour.toString(), isBold = true))
        totalRow.addView(makeCell(totalDay.toString(), isBold = true))
        totalRow.addView(makeCell(totalWeek.toString(), isBold = true))
        tableLayout.addView(totalRow)
    }

    private fun makeCell(text: String, isBold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            setPadding(12, 8, 12, 8)
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            if (isBold) setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f)
        }
    }
}
