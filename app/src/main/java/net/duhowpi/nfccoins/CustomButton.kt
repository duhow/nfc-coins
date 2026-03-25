package net.duhowpi.nfccoins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Represents a configurable action button shown on the main screen.
 *
 * [amount] is stored in the same internal unit used by card operations:
 * in decimal mode it is already multiplied by 100 (e.g. "1.50 coins" → 150),
 * in integer mode it equals the displayed coin count (e.g. "5 coins" → 5).
 */
data class CustomButton(
    val id: Int,
    val operation: String,
    val amount: Int,
    val label: String,
    val emoji: String,
    val backgroundColor: Int
) {
    companion object {
        const val OP_ADD = "add"
        const val OP_SUBTRACT = "subtract"
        const val MAX_BUTTONS = 9
        private const val PREFS_KEY = "custom_buttons"

        fun getDefaultButtons(context: Context): List<CustomButton> {
            val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(context)
            return listOf(
                CustomButton(0, OP_SUBTRACT, if (isDecimalMode) 100 else 1, "−1", "", 0),
                CustomButton(1, OP_SUBTRACT, if (isDecimalMode) 200 else 2, "−2", "", 0)
            )
        }

        fun loadButtons(context: Context): List<CustomButton> {
            val prefs = context.getSharedPreferences(
                AdvancedSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE
            )
            val json = prefs.getString(PREFS_KEY, null) ?: return getDefaultButtons(context)
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CustomButton(
                        id = o.getInt("id"),
                        operation = o.getString("operation"),
                        amount = o.getInt("amount"),
                        label = o.getString("label"),
                        emoji = o.optString("emoji", ""),
                        backgroundColor = o.optInt("backgroundColor", 0)
                    )
                }
            } catch (_: Exception) {
                getDefaultButtons(context)
            }
        }

        fun saveButtons(context: Context, buttons: List<CustomButton>) {
            val arr = JSONArray()
            buttons.forEach { btn ->
                val o = JSONObject()
                o.put("id", btn.id)
                o.put("operation", btn.operation)
                o.put("amount", btn.amount)
                o.put("label", btn.label)
                o.put("emoji", btn.emoji)
                o.put("backgroundColor", btn.backgroundColor)
                arr.put(o)
            }
            context.getSharedPreferences(AdvancedSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY, arr.toString()).apply()
        }
    }

    /** Returns the text shown inside the button: emoji if set, otherwise label. */
    fun buttonDisplayText(): String = if (emoji.isNotEmpty()) emoji else label
}
