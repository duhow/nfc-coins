package net.duhowpi.nfccoins

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class HourlyStats(val hourOffset: Int, val added: Int, val subtracted: Int)

data class DailyStats(val dayOffset: Int, val dayLabel: String, val added: Int, val subtracted: Int)

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
     * Returns hourly aggregated stats for the past [hours] hours (oldest first).
     * hourOffset=0 is the current hour, hourOffset=(hours-1) is the oldest hour.
     */
    fun getHourlyStats(hours: Int = 24): List<HourlyStats> {
        val now = System.currentTimeMillis()
        val result = mutableListOf<HourlyStats>()
        val db = readableDatabase

        for (i in (hours - 1) downTo 0) {
            val end = now - i * 3_600_000L
            val start = end - 3_600_000L
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
                if (it.moveToFirst()) {
                    result.add(HourlyStats(i, it.getInt(0), it.getInt(1)))
                }
            }
        }
        return result
    }

    /**
     * Returns daily aggregated stats for the past [days] days (oldest first).
     * dayOffset=0 is today, dayOffset=(days-1) is the oldest day.
     * Day labels are locale-aware (e.g. "Mon", "Lun" in Spanish).
     */
    fun getDailyStats(days: Int = 7, locale: Locale = Locale.getDefault()): List<DailyStats> {
        val dayFormat = SimpleDateFormat("EEE", locale)
        val result = mutableListOf<DailyStats>()
        val db = readableDatabase

        for (i in (days - 1) downTo 0) {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -i)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
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
                if (it.moveToFirst()) {
                    result.add(DailyStats(i, dayLabel, it.getInt(0), it.getInt(1)))
                }
            }
        }
        return result
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
