package net.duhowpi.nfccoins

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup

class OperationsHistoryActivity : AppCompatActivity() {

    private lateinit var db: TransactionDatabase
    private lateinit var chartView: BarChartView
    private lateinit var tvNoData: TextView
    private lateinit var togglePeriod: MaterialButtonToggleGroup
    private lateinit var tableLayout: TableLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdvancedSettingsActivity.wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operations_history)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.ops_history_title)

        db = TransactionDatabase(this)
        chartView = findViewById(R.id.chartView)
        tvNoData = findViewById(R.id.tvNoData)
        togglePeriod = findViewById(R.id.togglePeriod)
        tableLayout = findViewById(R.id.tableButtonStats)

        togglePeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) loadData(checkedId == R.id.btnWeekly)
        }

        // Default: hourly view
        togglePeriod.check(R.id.btnHourly)
    }

    override fun onResume() {
        super.onResume()
        val isWeekly = togglePeriod.checkedButtonId == R.id.btnWeekly
        loadData(isWeekly)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun loadData(weekly: Boolean) {
        if (weekly) {
            loadWeeklyData()
        } else {
            loadHourlyData()
        }
        loadButtonStats()
    }

    private fun loadHourlyData() {
        val stats = db.getHourlyStats(24)
        val entries = stats.map { s ->
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.HOUR_OF_DAY, -s.hourOffset)
            val label = if (s.hourOffset == 0) getString(R.string.ops_now)
                        else "${cal.get(java.util.Calendar.HOUR_OF_DAY)}h"
            BarChartView.Entry(label, s.added, s.subtracted)
        }
        updateChart(entries)
    }

    private fun loadWeeklyData() {
        val stats = db.getDailyStats(7, java.util.Locale.getDefault())
        val entries = stats.map { s ->
            BarChartView.Entry(s.dayLabel, s.added, s.subtracted)
        }
        updateChart(entries)
    }

    private fun updateChart(entries: List<BarChartView.Entry>) {
        val hasData = entries.any { it.added > 0 || it.subtracted > 0 }
        tvNoData.visibility = if (hasData) View.GONE else View.VISIBLE
        chartView.visibility = if (hasData) View.VISIBLE else View.GONE
        chartView.entries = entries
    }

    private fun loadButtonStats() {
        val stats = db.getButtonStats()

        // Remove all rows except the header (index 0)
        while (tableLayout.childCount > 1) {
            tableLayout.removeViewAt(1)
        }

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

            val unit1 = if (isDecimalMode) 100 else 1
            val unit2 = if (isDecimalMode) 200 else 2
            val labelText = when (stat.amount) {
                unit1 -> getString(R.string.deduct_1_short)
                unit2 -> getString(R.string.deduct_2_short)
                else  -> "-${stat.amount}"
            }

            row.addView(makeCell(labelText, isBold = true))
            row.addView(makeCell(stat.countLastHour.toString()))
            row.addView(makeCell(stat.countLastDay.toString()))
            row.addView(makeCell(stat.countLastWeek.toString()))
            tableLayout.addView(row)
        }
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
