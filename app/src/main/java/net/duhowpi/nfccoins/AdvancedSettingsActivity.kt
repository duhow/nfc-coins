package net.duhowpi.nfccoins

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import java.security.SecureRandom

class AdvancedSettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "nfc_pos_settings"
        const val KEY_TARGET_SECTOR = "target_sector"
        const val KEY_STATIC_KEY = "static_key"
        const val KEY_DYNAMIC_KEY_ENABLED = "dynamic_key_enabled"
        const val KEY_FLASH_ENABLED = "flash_enabled"
        const val KEY_VERIFY_INTEGRITY = "verify_integrity"
        const val KEY_DEBUG_ENABLED = "debug_enabled"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        const val KEY_THEME_COLOR = "theme_color"
        const val DEFAULT_SECTOR = 14
        val DEFAULT_THEME_COLOR = 0xFF6200EE.toInt()

        private const val SWATCH_NORMAL_TEXT_SP = 14f
        private const val SWATCH_SELECTED_TEXT_SP = 18f

        val COLOR_OPTIONS = listOf(
            0xFF6200EE.toInt(), // Purple (default)
            0xFF2196F3.toInt(), // Blue
            0xFF4CAF50.toInt(), // Green
            0xFFF44336.toInt(), // Red
            0xFFFF9800.toInt(), // Orange
            0xFF009688.toInt(), // Teal
            0xFF000000.toInt(), // Black
        )

        fun getTargetSector(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_TARGET_SECTOR, DEFAULT_SECTOR)
        }

        fun getStaticKey(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_STATIC_KEY, BuildConfig.NFC_PSK) ?: BuildConfig.NFC_PSK
        }

        fun isDynamicKeyEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DYNAMIC_KEY_ENABLED, true)
        }

        fun isFlashEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_FLASH_ENABLED, true)
        }

        fun isVerifyIntegrityEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_VERIFY_INTEGRITY, true)
        }

        fun isDebugEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        }

        fun isKeepScreenOnEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)
        }

        fun isSoundEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SOUND_ENABLED, true)
        }

        fun isVibrationEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_VIBRATION_ENABLED, false)
        }

        fun getThemeColor(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_THEME_COLOR, DEFAULT_THEME_COLOR)
        }

        /** Returns a darkened version of [color] for status bar / action bar variant. */
        fun darkenColor(color: Int, factor: Float = 0.7f): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
            return Color.HSVToColor(hsv)
        }

        /** Returns black or white, whichever contrasts better with [color]. */
        fun contrastColor(color: Int): Int {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return if (luminance > 0.35) Color.BLACK else Color.WHITE
        }
    }

    private lateinit var etSector: TextInputEditText
    private lateinit var etStaticKey: TextInputEditText
    private lateinit var btnToggleKeyVisibility: MaterialButton
    private lateinit var btnGenerateKey: MaterialButton
    private lateinit var cbDynamicKey: MaterialCheckBox
    private lateinit var cbFlashEnabled: MaterialCheckBox
    private lateinit var cbVerifyIntegrity: MaterialCheckBox
    private lateinit var cbDebugEnabled: MaterialCheckBox
    private lateinit var cbKeepScreenOn: MaterialCheckBox
    private lateinit var cbSoundEnabled: MaterialCheckBox
    private lateinit var cbVibrationEnabled: MaterialCheckBox
    private lateinit var colorSelectorLayout: LinearLayout
    private lateinit var btnSaveSettings: MaterialButton

    private var keyVisible = false
    private var selectedThemeColor: Int = DEFAULT_THEME_COLOR

    private val isCustomColor get() = selectedThemeColor !in COLOR_OPTIONS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.advanced_settings)

        etSector               = findViewById(R.id.etSector)
        etStaticKey            = findViewById(R.id.etStaticKey)
        btnToggleKeyVisibility = findViewById(R.id.btnToggleKeyVisibility)
        btnGenerateKey         = findViewById(R.id.btnGenerateKey)
        cbDynamicKey           = findViewById(R.id.cbDynamicKey)
        cbFlashEnabled         = findViewById(R.id.cbFlashEnabled)
        cbVerifyIntegrity      = findViewById(R.id.cbVerifyIntegrity)
        cbDebugEnabled         = findViewById(R.id.cbDebugEnabled)
        cbKeepScreenOn         = findViewById(R.id.cbKeepScreenOn)
        cbSoundEnabled         = findViewById(R.id.cbSoundEnabled)
        cbVibrationEnabled     = findViewById(R.id.cbVibrationEnabled)
        colorSelectorLayout    = findViewById(R.id.colorSelectorLayout)
        btnSaveSettings        = findViewById(R.id.btnSaveSettings)

        loadCurrentSettings()
        applyThemeToActionBar()

        btnToggleKeyVisibility.setOnClickListener { toggleKeyVisibility() }
        btnGenerateKey.setOnClickListener { confirmGenerateKey() }
        btnSaveSettings.setOnClickListener { saveSettings() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun applyThemeToActionBar() {
        val color = selectedThemeColor
        supportActionBar?.setBackgroundDrawable(ColorDrawable(color))
        window.statusBarColor = darkenColor(color)
    }

    private fun loadCurrentSettings() {
        etSector.setText(getTargetSector(this).toString())
        etStaticKey.setText(getStaticKey(this))
        cbDynamicKey.isChecked = isDynamicKeyEnabled(this)
        cbFlashEnabled.isChecked = isFlashEnabled(this)
        cbVerifyIntegrity.isChecked = isVerifyIntegrityEnabled(this)
        cbDebugEnabled.isChecked = isDebugEnabled(this)
        cbKeepScreenOn.isChecked = isKeepScreenOnEnabled(this)
        cbSoundEnabled.isChecked = isSoundEnabled(this)
        cbVibrationEnabled.isChecked = isVibrationEnabled(this)
        selectedThemeColor = getThemeColor(this)

        // Key is hidden by default
        etStaticKey.transformationMethod = PasswordTransformationMethod.getInstance()
        etStaticKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyVisible = false
        btnToggleKeyVisibility.setIconResource(R.drawable.ic_eye)

        renderColorSwatches()
    }

    private fun renderColorSwatches() {
        colorSelectorLayout.removeAllViews()
        val density = resources.displayMetrics.density
        val selectedSizePx = (44 * density).toInt()
        val normalSizePx = (32 * density).toInt()
        val marginPx = (8 * density).toInt()
        val strokeWidthPx = (3 * density).toInt()

        for (color in COLOR_OPTIONS) {
            val isSelected = color == selectedThemeColor
            val sizePx = if (isSelected) selectedSizePx else normalSizePx

            val swatch = android.view.View(this)
            val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = marginPx
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            swatch.layoutParams = params

            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(color)
            if (isSelected) {
                drawable.setStroke(strokeWidthPx, Color.BLACK)
            }
            swatch.background = drawable

            swatch.setOnClickListener {
                selectedThemeColor = color
                applyThemeToActionBar()
                renderColorSwatches()
            }
            colorSelectorLayout.addView(swatch)
        }

        // "+" swatch for custom color
        val customIsSelected = isCustomColor
        val sizePx = if (customIsSelected) selectedSizePx else normalSizePx

        val customSwatch = TextView(this)
        val params = LinearLayout.LayoutParams(sizePx, sizePx).apply {
            marginEnd = marginPx
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        customSwatch.layoutParams = params
        customSwatch.gravity = android.view.Gravity.CENTER
        customSwatch.setPadding(0, 0, 0, 0)

        val drawable = GradientDrawable()
        drawable.shape = GradientDrawable.OVAL
        if (customIsSelected) {
            drawable.setColor(selectedThemeColor)
            drawable.setStroke(strokeWidthPx, Color.BLACK)
        } else {
            drawable.setColor(Color.LTGRAY)
            customSwatch.text = "+"
            customSwatch.setTextColor(Color.DKGRAY)
            customSwatch.textSize = if (customIsSelected) SWATCH_SELECTED_TEXT_SP else SWATCH_NORMAL_TEXT_SP
        }
        customSwatch.background = drawable

        customSwatch.setOnClickListener { showCustomColorPicker() }
        colorSelectorLayout.addView(customSwatch)
    }

    private fun showCustomColorPicker() {
        val input = EditText(this)
        input.hint = "#RRGGBB"
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.isSingleLine = true
        input.setPadding(
            (16 * resources.displayMetrics.density).toInt(), 0,
            (16 * resources.displayMetrics.density).toInt(), 0
        )
        if (isCustomColor) {
            input.setText(String.format("#%06X", selectedThemeColor and 0xFFFFFF))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.color_pick_title))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val hex = input.text.toString().trim().removePrefix("#")
                val parsed = if (hex.length == 6) hex.toLongOrNull(16) else null
                val color = parsed?.let { (0xFF000000L or it).toInt() }
                if (color == null) {
                    Toast.makeText(this, getString(R.string.color_pick_invalid), Toast.LENGTH_SHORT).show()
                } else {
                    selectedThemeColor = color
                    applyThemeToActionBar()
                    renderColorSwatches()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleKeyVisibility() {
        keyVisible = !keyVisible
        val cursorPos = etStaticKey.selectionEnd
        if (keyVisible) {
            etStaticKey.transformationMethod = HideReturnsTransformationMethod.getInstance()
            etStaticKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            btnToggleKeyVisibility.contentDescription = getString(R.string.btn_hide_key)
            btnToggleKeyVisibility.setIconResource(R.drawable.ic_eye_off)
        } else {
            etStaticKey.transformationMethod = PasswordTransformationMethod.getInstance()
            etStaticKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            btnToggleKeyVisibility.contentDescription = getString(R.string.btn_show_key)
            btnToggleKeyVisibility.setIconResource(R.drawable.ic_eye)
        }
        etStaticKey.setSelection(cursorPos.coerceIn(0, etStaticKey.text?.length ?: 0))
    }

    private fun confirmGenerateKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.generate_key_confirm_title)
            .setMessage(R.string.generate_key_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> generateRandomKey() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateRandomKey() {
        val random = SecureRandom()
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        val key = bytes.joinToString("") { "%02X".format(it) }
        etStaticKey.setText(key)
        if (!keyVisible) {
            keyVisible = true
            etStaticKey.transformationMethod = HideReturnsTransformationMethod.getInstance()
            etStaticKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            btnToggleKeyVisibility.contentDescription = getString(R.string.btn_hide_key)
            btnToggleKeyVisibility.setIconResource(R.drawable.ic_eye_off)
        }
    }

    private fun saveSettings() {
        val sectorText = etSector.text?.toString()?.trim() ?: ""
        val sector = sectorText.toIntOrNull()
        if (sector == null || sector < 1 || sector > 15) {
            Toast.makeText(this, getString(R.string.sector_invalid), Toast.LENGTH_SHORT).show()
            return
        }

        val staticKey = etStaticKey.text?.toString()?.trim() ?: ""
        if (staticKey.isNotEmpty() && staticKey.length < 8) {
            Toast.makeText(this, getString(R.string.key_too_short), Toast.LENGTH_SHORT).show()
            return
        }
        val dynamicKeyEnabled = cbDynamicKey.isChecked
        val flashEnabled = cbFlashEnabled.isChecked
        val verifyIntegrity = cbVerifyIntegrity.isChecked
        val debugEnabled = cbDebugEnabled.isChecked
        val keepScreenOn = cbKeepScreenOn.isChecked
        val soundEnabled = cbSoundEnabled.isChecked
        val vibrationEnabled = cbVibrationEnabled.isChecked

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TARGET_SECTOR, sector)
            .putString(KEY_STATIC_KEY, staticKey.ifEmpty { BuildConfig.NFC_PSK })
            .putBoolean(KEY_DYNAMIC_KEY_ENABLED, dynamicKeyEnabled)
            .putBoolean(KEY_FLASH_ENABLED, flashEnabled)
            .putBoolean(KEY_VERIFY_INTEGRITY, verifyIntegrity)
            .putBoolean(KEY_DEBUG_ENABLED, debugEnabled)
            .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
            .putBoolean(KEY_SOUND_ENABLED, soundEnabled)
            .putBoolean(KEY_VIBRATION_ENABLED, vibrationEnabled)
            .putInt(KEY_THEME_COLOR, selectedThemeColor)
            .apply()

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
