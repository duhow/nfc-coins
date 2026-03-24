package net.duhowpi.nfccoins

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Stats for one clock hour (0–23) within a specific calendar day. */
data class HourlyStats(val hour: Int, val added: Int, val subtracted: Int)

/** Stats for one calendar day inside a specific week (Mon=0 … Sun=6). */
data class DailyStats(val weekdayIndex: Int, val dayLabel: String, val added: Int, val subtracted: Int)

/** Aggregated totals for a period. */
data class PeriodSummary(val added: Int, val subtracted: Int, val totalOps: Int)

data class ButtonStats(val amount: Int, val countLastHour: Int, val countLastDay: Int, val countLastWeek: Int)

class TransactionDatabase(context: Context) : SQLiteOpenHelper(
    context, DATABASE_NAME, null, DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "nfc_transactions.db"
        private const val DATABASE_VERSION = 1

        const val TABLE = "transactions"
        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_TYPE = "type"
        const val COL_AMOUNT = "amount"
        const val COL_BALANCE_BEFORE = "balance_before"
        const val COL_BALANCE_AFTER = "balance_after"
        const val COL_CARD_UID = "card_uid"
        const val COL_BUTTON_VALUE = "button_value"

        const val TYPE_READ = "read"
        const val TYPE_ADD = "add"
        const val TYPE_SUBTRACT = "subtract"
        const val TYPE_FORMAT = "format"
        const val TYPE_RESET = "reset"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_TYPE TEXT NOT NULL,
                $COL_AMOUNT INTEGER,
                $COL_BALANCE_BEFORE INTEGER,
                $COL_BALANCE_AFTER INTEGER,
                $COL_CARD_UID TEXT,
                $COL_BUTTON_VALUE INTEGER
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insertTransaction(
        type: String,
        amount: Int? = null,
        balanceBefore: Int? = null,
        balanceAfter: Int? = null,
        cardUid: String? = null,
        buttonValue: Int? = null
    ) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, System.currentTimeMillis())
            put(COL_TYPE, type)
            amount?.let { put(COL_AMOUNT, it) }
            balanceBefore?.let { put(COL_BALANCE_BEFORE, it) }
            balanceAfter?.let { put(COL_BALANCE_AFTER, it) }
            cardUid?.let { put(COL_CARD_UID, it) }
            buttonValue?.let { put(COL_BUTTON_VALUE, it) }
        }
        db.insert(TABLE, null, values)
    }

    /**
     * Returns 24 hourly buckets (hour 0 = midnight … hour 23 = 11 pm) for the calendar day
     * that is [dayOffset] days before today (dayOffset=0 → today, 1 → yesterday, …).
     */
    fun getHourlyStats(dayOffset: Int = 0): List<HourlyStats> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val dayStart = cal.timeInMillis

        val result = mutableListOf<HourlyStats>()
        val db = readableDatabase
        for (hour in 0..23) {
            val start = dayStart + hour * 3_600_000L
            val end = start + 3_600_000L
            val cursor = db.rawQuery(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN $COL_TYPE = '$TYPE_ADD' THEN $COL_AMOUNT ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN $COL_TYPE = '$TYPE_SUBTRACT' THEN ABS($COL_AMOUNT) ELSE 0 END), 0)
                FROM $TABLE
                WHERE $COL_TIMESTAMP >= ? AND $COL_TIMESTAMP < ?
                """.trimIndent(),
                arrayOf(start.toString(), end.toString())
            )
            cursor.use {
                if (it.moveToFirst()) result.add(HourlyStats(hour, it.getInt(0), it.getInt(1)))
            }
        }
        return result
    }

    /**
     * Returns 7 daily buckets Mon–Sun for the ISO week that is [weekOffset] weeks before the
     * current one (weekOffset=0 → this week, 1 → last week, …).
     * Day labels are locale-aware.
     */
    fun getDailyStats(weekOffset: Int = 0, locale: Locale = Locale.getDefault()): List<DailyStats> {
        val dayFormat = SimpleDateFormat("EEE", locale)

        // Find the Monday of the target week.
        val cal = Calendar.getInstance().apply {
            // Move to the requested week.
            add(Calendar.WEEK_OF_YEAR, -weekOffset)
            // Normalise to Monday regardless of the device's firstDayOfWeek setting.
            val dow = get(Calendar.DAY_OF_WEEK)   // 1=Sun … 7=Sat
            val daysFromMon = (dow + 5) % 7        // 0=Mon … 6=Sun
            add(Calendar.DAY_OF_YEAR, -daysFromMon)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val result = mutableListOf<DailyStats>()
        val db = readableDatabase
        for (idx in 0..6) {
            val start = cal.timeInMillis
            val dayLabel = dayFormat.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis

            val cursor = db.rawQuery(
                """
                SELECT
                    COALESCE(SUM(CASE WHEN $COL_TYPE = '$TYPE_ADD' THEN $COL_AMOUNT ELSE 0 END), 0),
                    COALESCE(SUM(CASE WHEN $COL_TYPE = '$TYPE_SUBTRACT' THEN ABS($COL_AMOUNT) ELSE 0 END), 0)
                FROM $TABLE
                WHERE $COL_TIMESTAMP >= ? AND $COL_TIMESTAMP < ?
                """.trimIndent(),
                arrayOf(start.toString(), end.toString())
            )
            cursor.use {
                if (it.moveToFirst()) result.add(DailyStats(idx, dayLabel, it.getInt(0), it.getInt(1)))
            }
        }
        return result
    }

    /**
     * Returns summary totals (added, subtracted, total operations) for a full calendar day.
     * dayOffset=0 → today, 1 → yesterday, etc.
     */
    fun getDaySummary(dayOffset: Int = 0): PeriodSummary {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -dayOffset)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        val end = start + 86_400_000L
        return querySummary(start, end)
    }

    /**
     * Returns summary totals for the ISO week that is [weekOffset] weeks before the current one.
     */
    fun getWeekSummary(weekOffset: Int = 0): PeriodSummary {
        val cal = Calendar.getInstance().apply {
            add(Calendar.WEEK_OF_YEAR, -weekOffset)
            val dow = get(Calendar.DAY_OF_WEEK)
            val daysFromMon = (dow + 5) % 7
            add(Calendar.DAY_OF_YEAR, -daysFromMon)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = start + 7 * 86_400_000L
        return querySummary(start, end)
    }

    private fun querySummary(start: Long, end: Long): PeriodSummary {
        val db = readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT
                COALESCE(SUM(CASE WHEN $COL_TYPE = '$TYPE_ADD' THEN $COL_AMOUNT ELSE 0 END), 0),
                COALESCE(SUM(CASE WHEN $COL_TYPE = '$TYPE_SUBTRACT' THEN ABS($COL_AMOUNT) ELSE 0 END), 0),
                COUNT(*)
            FROM $TABLE
            WHERE $COL_TIMESTAMP >= ? AND $COL_TIMESTAMP < ?
            """.trimIndent(),
            arrayOf(start.toString(), end.toString())
        )
        return cursor.use {
            if (it.moveToFirst()) PeriodSummary(it.getInt(0), it.getInt(1), it.getInt(2))
            else PeriodSummary(0, 0, 0)
        }
    }

    /**
     * Returns per-button-value usage counts (subtract operations only) for the past week.
     */
    fun getButtonStats(): List<ButtonStats> {
        val db = readableDatabase
        val now = System.currentTimeMillis()
        val oneHourAgo = now - 3_600_000L
        val oneDayAgo = now - 86_400_000L
        val oneWeekAgo = now - 7 * 86_400_000L

        val cursor = db.rawQuery(
            """
            SELECT
                $COL_BUTTON_VALUE,
                SUM(CASE WHEN $COL_TIMESTAMP >= $oneHourAgo THEN 1 ELSE 0 END),
                SUM(CASE WHEN $COL_TIMESTAMP >= $oneDayAgo THEN 1 ELSE 0 END),
                COUNT(*)
            FROM $TABLE
            WHERE $COL_TYPE = '$TYPE_SUBTRACT'
              AND $COL_TIMESTAMP >= $oneWeekAgo
              AND $COL_BUTTON_VALUE IS NOT NULL
            GROUP BY $COL_BUTTON_VALUE
            ORDER BY COUNT(*) DESC
            """.trimIndent(),
            null
        )

        val result = mutableListOf<ButtonStats>()
        cursor.use {
            while (it.moveToNext()) {
                result.add(
                    ButtonStats(
                        amount = it.getInt(0),
                        countLastHour = it.getInt(1),
                        countLastDay = it.getInt(2),
                        countLastWeek = it.getInt(3)
                    )
                )
            }
        }
        return result
    }
}
