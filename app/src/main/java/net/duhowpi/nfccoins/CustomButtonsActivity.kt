package net.duhowpi.nfccoins

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.MenuItem
import android.view.View
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
    // Grid rendering – shows configured buttons + ONE next empty "+" slot
    // -------------------------------------------------------------------------

    private fun renderGrid() {
        layoutButtonGrid.removeAllViews()

        val dp = resources.displayMetrics.density
        val slotHeightPx = (100 * dp).toInt()
        val marginPx = (4 * dp).toInt()

        // Only show configured buttons + exactly one "+" slot at the end (if room).
        val showAddSlot = buttons.size < CustomButton.MAX_BUTTONS
        val totalCells = buttons.size + if (showAddSlot) 1 else 0
        if (totalCells == 0) return

        val totalRows = (totalCells + 2) / 3  // ceiling division

        for (row in 0 until totalRows) {
            // Use fixed row height (not WRAP_CONTENT) so all rows align identically.
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    slotHeightPx
                ).also { if (row > 0) it.topMargin = marginPx }
            }

            for (col in 0 until 3) {
                val cellIndex = row * 3 + col
                val isAddSlot = showAddSlot && cellIndex == buttons.size
                val slotView: View = when {
                    cellIndex >= totalCells -> View(this)  // invisible filler to maintain 3-col grid
                    isAddSlot -> buildAddSlotView()
                    else -> buildConfiguredSlotView(cellIndex, buttons[cellIndex])
                }
                val lp = LinearLayout.LayoutParams(0, slotHeightPx, 1f)
                if (col > 0) lp.marginStart = marginPx
                slotView.layoutParams = lp
                rowLayout.addView(slotView)
            }

            layoutButtonGrid.addView(rowLayout)
        }
    }

    private fun buildAddSlotView(): View {
        val dp = resources.displayMetrics.density
        val themeColor = AdvancedSettingsActivity.getThemeColor(this)
        return TextView(this).apply {
            gravity = Gravity.CENTER
            text = "+"
            textSize = 32f
            setTextColor(themeColor)
            background = buildSlotDrawable(
                fillColor = Color.TRANSPARENT,
                strokeColor = themeColor,
                strokeWidth = (1 * dp).toInt(),
                dashed = true
            )
            setOnClickListener { showAddDialog(buttons.size) }
        }
    }

    private fun buildConfiguredSlotView(slotIndex: Int, btn: CustomButton): View {
        val dp = resources.displayMetrics.density
        val themeColor = AdvancedSettingsActivity.getThemeColor(this)
        val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
        val opSign = if (btn.operation == CustomButton.OP_ADD) "+" else "−"
        val amtText = formatAmount(btn.amount, isDecimalMode)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = buildSlotDrawable(
                fillColor = if (btn.backgroundColor != 0) btn.backgroundColor else Color.TRANSPARENT,
                strokeColor = themeColor,
                strokeWidth = (1 * dp).toInt()
            )
            // Tap to edit
            setOnClickListener { showEditDialog(slotIndex, btn) }
            // Long-click (≈500 ms, Android default) starts drag-and-drop reorder
            setOnLongClickListener { v ->
                val clipData = ClipData.newPlainText("", "")
                val shadow = View.DragShadowBuilder(v)
                v.startDragAndDrop(clipData, shadow, slotIndex, 0)
                v.alpha = 0.5f
                true
            }
            // Accept drops from other configured slots
            setOnDragListener { v, event -> handleDragEvent(v, event, slotIndex) }
        }

        val tvMain = TextView(this).apply {
            gravity = Gravity.CENTER
            text = btn.buttonDisplayText()
            textSize = if (btn.emoji.isNotEmpty()) 28f else 20f
            if (btn.backgroundColor != 0)
                setTextColor(AdvancedSettingsActivity.contrastColor(btn.backgroundColor))
        }
        val tvSub = TextView(this).apply {
            gravity = Gravity.CENTER
            text = "$opSign$amtText"
            textSize = 12f
            setTextColor(
                if (btn.backgroundColor != 0)
                    AdvancedSettingsActivity.contrastColor(btn.backgroundColor)
                else Color.GRAY
            )
        }

        container.addView(tvMain)
        container.addView(tvSub)
        return container
    }

    // Drag-and-drop event handler shared by all configured slot views.
    private fun handleDragEvent(targetView: View, event: DragEvent, targetIndex: Int): Boolean {
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                targetView.alpha = 0.7f
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                targetView.alpha = 1f
                true
            }
            DragEvent.ACTION_DROP -> {
                targetView.alpha = 1f
                val fromIndex = event.localState as? Int ?: return false
                if (fromIndex != targetIndex
                    && fromIndex in buttons.indices
                    && targetIndex in buttons.indices) {
                    val moved = buttons.removeAt(fromIndex)
                    buttons.add(targetIndex, moved)
                    reassignIds()
                    CustomButton.saveButtons(this, buttons)
                    // Defer renderGrid so the drag framework finishes before views are replaced.
                    layoutButtonGrid.post { renderGrid() }
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                // Restore alpha whether or not a drop occurred (covers the drag-source view).
                targetView.alpha = 1f
                true
            }
            else -> true
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

    private fun showDeleteConfirm(slotIndex: Int, parentDialog: AlertDialog) {
        val btn = buttons.getOrNull(slotIndex) ?: return
        parentDialog.dismiss()
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
        val smallPad = (8 * dp).toInt()

        // ── Operation selector ───────────────────────────────────────────────
        val rbSubtract = RadioButton(this).apply {
            text = getString(R.string.custom_button_operation_subtract)
            id = View.generateViewId()
        }
        val rbAdd = RadioButton(this).apply {
            text = getString(R.string.custom_button_operation_add)
            id = View.generateViewId()
        }
        val rgOperation = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbSubtract)
            addView(rbAdd)
        }

        // ── Label (80%) + Emoji (20%) on the same row ────────────────────────
        // Header row
        val tvLabelHeader = TextView(this).apply {
            text = getString(R.string.custom_button_label)
            setPadding(0, pad / 2, 0, 0)
        }
        val tvEmojiHeader = TextView(this).apply {
            text = getString(R.string.custom_button_emoji_short)
            setPadding(smallPad, pad / 2, 0, 0)
        }
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(tvLabelHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f))
        headerRow.addView(tvEmojiHeader, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Input row
        val etLabel = EditText(this).apply {
            hint = getString(R.string.custom_button_label_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(20))
        }
        val etEmoji = EditText(this).apply {
            hint = "☺"
            inputType = InputType.TYPE_CLASS_TEXT
            // Allow only non-ASCII-letter characters (emoji, symbols, digits for keycap sequences)
            filters = arrayOf(InputFilter.LengthFilter(8), makeEmojiFilter())
            setPadding(smallPad, paddingTop, paddingRight, paddingBottom)
        }
        val fieldRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fieldRow.addView(etLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 4f))
        fieldRow.addView(etEmoji, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.marginStart = smallPad })

        // ── Amount (with +/- sign) ───────────────────────────────────────────
        val tvAmountHint = TextView(this).apply {
            text = getString(R.string.custom_button_amount)
            setPadding(0, pad / 2, 0, 0)
        }
        val amountHint = if (isDecimalMode)
            getString(R.string.custom_button_amount_hint_decimal)
        else
            getString(R.string.custom_button_amount_hint)
        val etAmount = EditText(this).apply {
            hint = amountHint
            // TYPE_CLASS_TEXT lets the user type '+' and '-' freely
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(makeSignedNumericFilter(isDecimalMode))
        }

        // ── Color – tappable swatch (no separate "Pick" button) ──────────────
        val tvColorHint = TextView(this).apply {
            text = getString(R.string.custom_button_color)
            setPadding(0, pad / 2, 0, 0)
        }
        var selectedColor = existingButton?.backgroundColor ?: 0
        val colorSizePx = (52 * dp).toInt()
        val colorPreview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(colorSizePx, colorSizePx)
                .also { it.marginEnd = (8 * dp).toInt() }
            background = buildColorPreviewDrawable(selectedColor)
            isClickable = true
            isFocusable = true
        }
        val btnClearColor = MaterialButton(this).apply {
            text = getString(R.string.custom_button_color_clear)
        }
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 4, 0, 0)
            addView(colorPreview)
            addView(btnClearColor)
        }

        // ── Populate existing values ─────────────────────────────────────────
        val defaultOp = existingButton?.operation ?: CustomButton.OP_SUBTRACT
        rgOperation.check(if (defaultOp == CustomButton.OP_ADD) rbAdd.id else rbSubtract.id)
        existingButton?.let { btn ->
            etLabel.setText(btn.label)
            etEmoji.setText(btn.emoji)
            val sign = if (btn.operation == CustomButton.OP_ADD) "+" else "-"
            etAmount.setText("$sign${formatAmount(btn.amount, isDecimalMode)}")
        } ?: run {
            // New button: default sign is "-" (SUBTRACT)
            etAmount.setText("-")
        }

        // ── Two-way binding: amount sign ↔ operation radio ───────────────────
        var updatingSign = false
        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingSign) return
                val raw = s?.toString() ?: return
                val targetId = if (raw.startsWith("+")) rbAdd.id else rbSubtract.id
                if (rgOperation.checkedRadioButtonId != targetId) rgOperation.check(targetId)
            }
        })
        rgOperation.setOnCheckedChangeListener { _, checkedId ->
            val raw = etAmount.text?.toString() ?: ""
            val digits = raw.trimStart('+', '-')
            val newSign = if (checkedId == rbAdd.id) "+" else "-"
            if (!raw.startsWith(newSign)) {
                updatingSign = true
                etAmount.setText("$newSign$digits")
                etAmount.setSelection(etAmount.text?.length ?: 0)
                updatingSign = false
            }
        }

        // ── Color picker: tap swatch to open wheel ───────────────────────────
        fun openColorPicker() {
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
        colorPreview.setOnClickListener { openColorPicker() }
        btnClearColor.setOnClickListener {
            selectedColor = 0
            colorPreview.background = buildColorPreviewDrawable(0)
        }

        // ── Layout assembly ──────────────────────────────────────────────────
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(rgOperation)
            addView(headerRow)
            addView(fieldRow)
            addView(tvAmountHint)
            addView(etAmount)
            addView(tvColorHint)
            addView(colorRow)
        }

        val title = if (existingButton != null) R.string.custom_button_edit else R.string.custom_button_add
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(R.string.custom_button_save) { _, _ -> /* validated below */ }
            .setNegativeButton(android.R.string.cancel, null)
            .also { builder ->
                if (existingButton != null) {
                    builder.setNeutralButton(R.string.custom_button_delete) { _, _ -> /* handled below */ }
                }
            }
            .show()
            .also { applyThemeToDialog(it) }

        // Delete button: dismiss this dialog first, then show confirmation
        if (existingButton != null) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                showDeleteConfirm(slotIndex, dialog)
            }
        }

        // Save button: validate, then persist and refresh
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
            String.format(Locale.getDefault(), "%d.%02d", amount / 100, kotlin.math.abs(amount % 100))
        } else {
            amount.toString()
        }
    }

    /**
     * Parses the amount from the text field (which may start with '+' or '-').
     * Strips the leading sign before parsing and caps at [MifareClassicHelper.MAX_BALANCE].
     */
    private fun parseAmountInput(text: String, isDecimalMode: Boolean): Int {
        val stripped = text.trimStart('+', '-')
        if (stripped.isEmpty()) return 0
        val maxAllowed = MifareClassicHelper.MAX_BALANCE
        return if (isDecimalMode) {
            val normalized = stripped.replace(',', '.')
            val result = if ('.' in normalized) {
                val parts = normalized.split('.')
                val intPart = parts[0].toIntOrNull() ?: 0
                val fracStr = (parts.getOrNull(1) ?: "").take(2).padEnd(2, '0')
                val fracPart = fracStr.toIntOrNull() ?: 0
                intPart * 100 + fracPart
            } else {
                stripped.toIntOrNull() ?: 0
            }
            result.coerceAtMost(maxAllowed)
        } else {
            val raw = stripped.substringBefore('.').substringBefore(',').toIntOrNull() ?: 0
            raw.coerceAtMost(maxAllowed)
        }
    }

    /**
     * InputFilter that blocks basic ASCII letters (a–z, A–Z) so only emoji and
     * non-Latin characters can be entered in the emoji field.
     */
    private fun makeEmojiFilter(): InputFilter = InputFilter { source, start, end, _, _, _ ->
        val sb = StringBuilder()
        var i = start
        while (i < end) {
            val cp = Character.codePointAt(source, i)
            val len = Character.charCount(cp)
            val isAsciiLetter = cp in 0x41..0x5A || cp in 0x61..0x7A  // A-Z or a-z
            if (!isAsciiLetter) sb.append(source, i, i + len)
            i += len
        }
        if (sb.length == (end - start)) null else sb.toString()
    }

    /**
     * InputFilter that restricts the amount field to digits, optional leading sign
     * (+/-), and (in decimal mode) a decimal separator (. or ,).
     */
    private fun makeSignedNumericFilter(isDecimalMode: Boolean): InputFilter =
        InputFilter { source, start, end, _, _, _ ->
            val sb = StringBuilder()
            for (i in start until end) {
                val c = source[i]
                if (c.isDigit() || c == '+' || c == '-' || (isDecimalMode && (c == '.' || c == ','))) {
                    sb.append(c)
                }
            }
            if (sb.length == (end - start)) null else sb.toString()
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
