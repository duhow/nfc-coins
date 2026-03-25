package net.duhowpi.nfccoins

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import java.util.Locale

class CustomButtonsActivity : AppCompatActivity() {

    private lateinit var layoutButtonGrid: LinearLayout
    private var buttons = mutableListOf<CustomButton>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdvancedSettingsActivity.wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_buttons)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.custom_buttons)

        applyThemeToActionBar()

        layoutButtonGrid = findViewById(R.id.layoutButtonGrid)

        buttons = CustomButton.loadButtons(this).toMutableList()
        renderGrid()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun applyThemeToActionBar() {
        val color = AdvancedSettingsActivity.getThemeColor(this)
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val bgColor = ta.getColor(0, Color.WHITE)
        ta.recycle()
        supportActionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        @Suppress("DEPRECATION")
        window.statusBarColor = bgColor
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            AdvancedSettingsActivity.contrastColor(bgColor) == Color.BLACK
    }

    // -------------------------------------------------------------------------
    // Grid rendering
    // -------------------------------------------------------------------------

    private fun renderGrid() {
        layoutButtonGrid.removeAllViews()

        val dp = resources.displayMetrics.density
        val slotHeightPx = (100 * dp).toInt()
        val marginPx = (4 * dp).toInt()

        for (row in 0 until 3) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { if (row > 0) it.topMargin = marginPx }
            }

            for (col in 0 until 3) {
                val slotIndex = row * 3 + col
                val btn = buttons.getOrNull(slotIndex)

                val slotView = buildSlotView(slotIndex, btn)
                val lp = LinearLayout.LayoutParams(0, slotHeightPx, 1f)
                if (col > 0) lp.marginStart = marginPx
                slotView.layoutParams = lp
                rowLayout.addView(slotView)
            }

            layoutButtonGrid.addView(rowLayout)
        }
    }

    private fun buildSlotView(slotIndex: Int, btn: CustomButton?): View {
        val dp = resources.displayMetrics.density
        val strokeWidthPx = (1 * dp).toInt()
        val themeColor = AdvancedSettingsActivity.getThemeColor(this)

        return if (btn != null) {
            // Configured slot: show button preview
            val textView = TextView(this).apply {
                gravity = Gravity.CENTER
                setPadding(4, 4, 4, 4)

                // Display text: emoji or label
                text = btn.buttonDisplayText()
                textSize = if (btn.emoji.isNotEmpty()) 28f else 18f

                // Background
                background = buildSlotDrawable(
                    fillColor = if (btn.backgroundColor != 0) btn.backgroundColor else Color.TRANSPARENT,
                    strokeColor = themeColor,
                    strokeWidth = strokeWidthPx
                )

                // Subtitle: operation + amount
                val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this@CustomButtonsActivity)
                val opSign = if (btn.operation == CustomButton.OP_ADD) "+" else "−"
                val amtText = formatAmount(btn.amount, isDecimalMode)
                contentDescription = "${btn.label} $opSign$amtText"

                setOnClickListener { showEditDialog(slotIndex, btn) }
                setOnLongClickListener {
                    showDeleteConfirm(slotIndex)
                    true
                }
            }

            // Overlay a small label for operation + amount at bottom
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = buildSlotDrawable(
                    fillColor = if (btn.backgroundColor != 0) btn.backgroundColor else Color.TRANSPARENT,
                    strokeColor = themeColor,
                    strokeWidth = strokeWidthPx
                )
                setOnClickListener { showEditDialog(slotIndex, btn) }
                setOnLongClickListener {
                    showDeleteConfirm(slotIndex)
                    true
                }
            }

            val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
            val opSign = if (btn.operation == CustomButton.OP_ADD) "+" else "−"
            val amtText = formatAmount(btn.amount, isDecimalMode)

            val tvMain = TextView(this).apply {
                gravity = Gravity.CENTER
                text = btn.buttonDisplayText()
                textSize = if (btn.emoji.isNotEmpty()) 28f else 20f
                if (btn.backgroundColor != 0) setTextColor(AdvancedSettingsActivity.contrastColor(btn.backgroundColor))
            }
            val tvSub = TextView(this).apply {
                gravity = Gravity.CENTER
                text = "$opSign$amtText"
                textSize = 12f
                setTextColor(if (btn.backgroundColor != 0) AdvancedSettingsActivity.contrastColor(btn.backgroundColor) else Color.GRAY)
            }

            container.addView(tvMain)
            container.addView(tvSub)
            container
        } else {
            // Empty slot: show "+" add button
            if (buttons.size >= CustomButton.MAX_BUTTONS) {
                // All slots already have buttons but this slot is beyond the list—shouldn't happen
                View(this)
            } else {
                TextView(this).apply {
                    gravity = Gravity.CENTER
                    text = "+"
                    textSize = 32f
                    setTextColor(themeColor)
                    background = buildSlotDrawable(
                        fillColor = Color.TRANSPARENT,
                        strokeColor = themeColor,
                        strokeWidth = strokeWidthPx,
                        dashed = true
                    )
                    setOnClickListener { showAddDialog(slotIndex) }
                }
            }
        }
    }

    private fun buildSlotDrawable(
        fillColor: Int,
        strokeColor: Int,
        strokeWidth: Int,
        dashed: Boolean = false
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8 * resources.displayMetrics.density
            setColor(fillColor)
            if (dashed) {
                // Dashed border for empty slots via a layered drawable approach is complex;
                // use a solid thin stroke instead.
                setStroke(strokeWidth, strokeColor, 12f, 8f)
            } else {
                setStroke(strokeWidth, strokeColor)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Dialogs
    // -------------------------------------------------------------------------

    private fun showAddDialog(slotIndex: Int) {
        if (buttons.size >= CustomButton.MAX_BUTTONS) {
            Toast.makeText(this, getString(R.string.custom_button_max_reached), Toast.LENGTH_SHORT).show()
            return
        }
        showButtonDialog(slotIndex, existingButton = null)
    }

    private fun showEditDialog(slotIndex: Int, btn: CustomButton) {
        showButtonDialog(slotIndex, existingButton = btn)
    }

    private fun showDeleteConfirm(slotIndex: Int) {
        val btn = buttons.getOrNull(slotIndex) ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_button_delete)
            .setMessage(getString(R.string.custom_button_delete_confirm, btn.label))
            .setPositiveButton(R.string.custom_button_delete) { _, _ ->
                buttons.removeAt(slotIndex)
                reassignIds()
                CustomButton.saveButtons(this, buttons)
                renderGrid()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
            .also { applyThemeToDialog(it) }
    }

    private fun showButtonDialog(slotIndex: Int, existingButton: CustomButton?) {
        val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
        val dp = resources.displayMetrics.density
        val pad = (16 * dp).toInt()

        // Operation selector
        val rgOperation = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbSubtract = RadioButton(this).apply {
            text = getString(R.string.custom_button_operation_subtract)
            id = View.generateViewId()
        }
        val rbAdd = RadioButton(this).apply {
            text = getString(R.string.custom_button_operation_add)
            id = View.generateViewId()
        }
        rgOperation.addView(rbSubtract)
        rgOperation.addView(rbAdd)

        // Label
        val tvLabelHint = TextView(this).apply {
            text = getString(R.string.custom_button_label)
            setPadding(0, pad / 2, 0, 0)
        }
        val etLabel = EditText(this).apply {
            hint = getString(R.string.custom_button_label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(20))
        }

        // Emoji
        val tvEmojiHint = TextView(this).apply {
            text = getString(R.string.custom_button_emoji)
            setPadding(0, pad / 2, 0, 0)
        }
        val etEmoji = EditText(this).apply {
            hint = getString(R.string.custom_button_emoji_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(4))
        }

        // Amount
        val tvAmountHint = TextView(this).apply {
            text = getString(R.string.custom_button_amount)
            setPadding(0, pad / 2, 0, 0)
        }
        val etAmount = EditText(this).apply {
            hint = if (isDecimalMode) getString(R.string.custom_button_amount_hint_decimal)
                   else getString(R.string.custom_button_amount_hint)
            inputType = if (isDecimalMode)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                InputType.TYPE_CLASS_NUMBER
        }

        // Color picker
        val tvColorHint = TextView(this).apply {
            text = getString(R.string.custom_button_color)
            setPadding(0, pad / 2, 0, 0)
        }
        var selectedColor = existingButton?.backgroundColor ?: 0
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 4, 0, 0)
        }
        val colorPreview = View(this).apply {
            val sizePx = (36 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).also { it.marginEnd = (8 * dp).toInt() }
            background = buildColorPreviewDrawable(selectedColor)
        }
        val btnPickColor = MaterialButton(this).apply {
            text = getString(R.string.custom_button_color_pick)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = (8 * dp).toInt() }
        }
        val btnClearColor = MaterialButton(this).apply {
            text = getString(R.string.custom_button_color_clear)
        }
        colorRow.addView(colorPreview)
        colorRow.addView(btnPickColor)
        colorRow.addView(btnClearColor)

        // Populate existing values
        existingButton?.let { btn ->
            if (btn.operation == CustomButton.OP_ADD) rgOperation.check(rbAdd.id)
            else rgOperation.check(rbSubtract.id)
            etLabel.setText(btn.label)
            etEmoji.setText(btn.emoji)
            etAmount.setText(formatAmount(btn.amount, isDecimalMode))
        } ?: run {
            rgOperation.check(rbSubtract.id)
        }

        // Color picker button
        btnPickColor.setOnClickListener {
            val wheel = ColorWheelView(this)
            if (selectedColor != 0) wheel.setColor(selectedColor)
            AlertDialog.Builder(this)
                .setTitle(R.string.color_pick_title)
                .setView(wheel)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    selectedColor = wheel.getColor()
                    colorPreview.background = buildColorPreviewDrawable(selectedColor)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
                .also { applyThemeToDialog(it) }
        }
        btnClearColor.setOnClickListener {
            selectedColor = 0
            colorPreview.background = buildColorPreviewDrawable(0)
        }

        // Layout
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        layout.addView(rgOperation)
        layout.addView(tvLabelHint)
        layout.addView(etLabel)
        layout.addView(tvEmojiHint)
        layout.addView(etEmoji)
        layout.addView(tvAmountHint)
        layout.addView(etAmount)
        layout.addView(tvColorHint)
        layout.addView(colorRow)

        val title = if (existingButton != null) R.string.custom_button_edit else R.string.custom_button_add
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(R.string.custom_button_save) { _, _ -> /* handled below */ }
            .setNegativeButton(android.R.string.cancel, null)
            .also { builder ->
                if (existingButton != null) {
                    builder.setNeutralButton(R.string.custom_button_delete) { _, _ ->
                        showDeleteConfirm(slotIndex)
                    }
                }
            }
            .show()
            .also { applyThemeToDialog(it) }

        // Override positive button to validate before dismissing
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val labelText = etLabel.text.toString().trim()
            if (labelText.isEmpty()) {
                etLabel.error = getString(R.string.custom_button_label_invalid)
                return@setOnClickListener
            }
            val amountText = etAmount.text.toString().trim()
            val parsedAmount = parseAmountInput(amountText, isDecimalMode)
            if (parsedAmount <= 0) {
                etAmount.error = getString(R.string.custom_button_amount_invalid)
                return@setOnClickListener
            }
            val operation = if (rgOperation.checkedRadioButtonId == rbAdd.id)
                CustomButton.OP_ADD else CustomButton.OP_SUBTRACT

            val newBtn = CustomButton(
                id = existingButton?.id ?: slotIndex,
                operation = operation,
                amount = parsedAmount,
                label = labelText,
                emoji = etEmoji.text.toString().trim(),
                backgroundColor = selectedColor
            )

            if (existingButton != null) {
                buttons[slotIndex] = newBtn
            } else {
                buttons.add(newBtn)
                reassignIds()
            }
            CustomButton.saveButtons(this, buttons)
            renderGrid()
            dialog.dismiss()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun reassignIds() {
        buttons = buttons.mapIndexed { idx, btn -> btn.copy(id = idx) }.toMutableList()
    }

    private fun formatAmount(amount: Int, isDecimalMode: Boolean): String {
        return if (isDecimalMode) {
            String.format(Locale.getDefault(), "%d.%02d", amount / 100, Math.abs(amount % 100))
        } else {
            amount.toString()
        }
    }

    private fun parseAmountInput(text: String, isDecimalMode: Boolean): Int {
        if (text.isEmpty()) return 0
        return if (isDecimalMode) {
            val normalized = text.replace(',', '.')
            if ('.' in normalized) {
                val parts = normalized.split('.')
                val intPart = parts[0].toIntOrNull() ?: 0
                val fracStr = (parts.getOrNull(1) ?: "").take(2).padEnd(2, '0')
                val fracPart = fracStr.toIntOrNull() ?: 0
                intPart * 100 + fracPart
            } else {
                (text.toIntOrNull() ?: 0)
            }
        } else {
            text.toIntOrNull() ?: 0
        }
    }

    private fun buildColorPreviewDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4 * resources.displayMetrics.density
            setColor(if (color != 0) color else Color.LTGRAY)
            setStroke((1 * resources.displayMetrics.density).toInt(), Color.GRAY)
        }
    }

    private fun applyThemeToDialog(dialog: AlertDialog) {
        val color = AdvancedSettingsActivity.getThemeColor(this)
        val rippleTint = ColorStateList.valueOf(AdvancedSettingsActivity.rippleColor(color))
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { which ->
                val btn = dialog.getButton(which) ?: return@forEach
                btn.setTextColor(color)
                (btn as? MaterialButton)?.rippleColor = rippleTint
            }
    }
}
