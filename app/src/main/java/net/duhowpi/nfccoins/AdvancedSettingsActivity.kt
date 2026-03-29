package net.duhowpi.nfccoins

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
        const val KEY_SELLER_MODE = "seller_mode"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_DECIMAL_MODE = "decimal_mode"
        const val KEY_LEGAL_AGE = "legal_age"
        /** Kept for one-time migration of legacy language preference to AppCompat locale. */
        const val KEY_LANGUAGE = "language"
        const val KEY_DISTRIBUTED_POS = "distributed_pos"
        const val KEY_BROADCAST_ENABLED = "broadcast_enabled"
        const val DEFAULT_LANGUAGE = "en"

        /**
         * Returns the list of supported languages as (displayName, code) pairs by reading
         * `R.array.language_names` and `R.array.language_codes` from resources.
         * Adding a new language requires updating those arrays in `res/values/arrays.xml`
         * and adding the locale to `res/xml/locale_config.xml`.
         */
        fun getLanguageEntries(context: Context): List<Pair<String, String>> {
            val codes = context.resources.getStringArray(R.array.language_codes)
            val names = context.resources.getStringArray(R.array.language_names)
            check(codes.size == names.size) {
                "language_codes and language_names arrays must have the same number of items " +
                        "(got ${codes.size} codes vs ${names.size} names)"
            }
            return names.zip(codes)
        }

        const val DEFAULT_SECTOR = 14
        val DEFAULT_THEME_COLOR = 0xFF6200EE.toInt()
        const val DEFAULT_LEGAL_AGE = 18

        private const val SWATCH_NORMAL_TEXT_SP = 14f
        private const val SWATCH_SELECTED_TEXT_SP = 18f

        val COLOR_OPTIONS = listOf(
            0xFF6200EE.toInt(), // Purple (default)
            0xFF2196F3.toInt(), // Blue
            0xFF4CAF50.toInt(), // Green
            0xFFF44336.toInt(), // Red
            0xFFFF9800.toInt(), // Orange
            0xFF009688.toInt(), // Teal
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

        fun isSellerModeEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_SELLER_MODE, false)
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

        fun isDecimalModeEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DECIMAL_MODE, false)
        }

        fun isDistributedPosEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_DISTRIBUTED_POS, false)
        }

        fun isBroadcastEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_BROADCAST_ENABLED, false)
        }

        fun getLegalAge(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_LEGAL_AGE, DEFAULT_LEGAL_AGE)
        }

        /** Returns the currently active app language code (e.g. "en", "es"). */
        fun getLanguage(): String {
            val locales = AppCompatDelegate.getApplicationLocales()
            return if (!locales.isEmpty) locales[0]?.language ?: DEFAULT_LANGUAGE else DEFAULT_LANGUAGE
        }

        /**
         * Returns the language code that is VISUALLY active in the app.
         * When no explicit app locale is set (system language mode), falls back to the
         * actual locale currently applied to the app's resources instead of "en".
         */
        fun getEffectiveLanguage(context: Context): String {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            if (!appLocales.isEmpty) {
                return appLocales[0]?.language ?: DEFAULT_LANGUAGE
            }
            // System/default mode: return whatever locale the resources are currently using
            return context.resources.configuration.locales[0].language
        }

        /** Returns black or white, whichever contrasts better with [color]. */
        fun contrastColor(color: Int): Int {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0
            val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return if (luminance > 0.35) Color.BLACK else Color.WHITE
        }

        /**
         * Returns [color] with [alpha] (0–255) applied, used for ripple overlays on buttons
         * so the press highlight matches the theme color at reduced opacity.
         */
        fun rippleColor(color: Int, alpha: Int = 50): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private lateinit var rowLanguage: LinearLayout
    private lateinit var tvLanguageValue: TextView
    private lateinit var etSector: TextInputEditText
    private lateinit var tilSector: TextInputLayout
    private lateinit var etStaticKey: TextInputEditText
    private lateinit var tilStaticKey: TextInputLayout
    private lateinit var btnToggleKeyVisibility: MaterialButton
    private lateinit var btnGenerateKey: MaterialButton
    private lateinit var swDynamicKey: SwitchMaterial
    private lateinit var swFlashEnabled: SwitchMaterial
    private lateinit var swVerifyIntegrity: SwitchMaterial
    private lateinit var swDebugEnabled: SwitchMaterial
    private lateinit var swSellerMode: SwitchMaterial
    private lateinit var swKeepScreenOn: SwitchMaterial
    private lateinit var swSoundEnabled: SwitchMaterial
    private lateinit var swVibrationEnabled: SwitchMaterial
    private lateinit var swDecimalMode: SwitchMaterial
    private lateinit var swDistributedPos: SwitchMaterial
    private lateinit var swBroadcastEnabled: SwitchMaterial
    private lateinit var etLegalAge: TextInputEditText
    private lateinit var tilLegalAge: TextInputLayout
    private lateinit var colorSelectorLayout: LinearLayout
    private lateinit var btnSaveSettings: MaterialButton

    private var keyVisible = false
    private var selectedThemeColor: Int = DEFAULT_THEME_COLOR
    /** Tracks the language code selected via dialog (API < 33 only); null means unchanged. */
    private var pendingLangCode: String? = null
    private var originalSettingsSnapshot: SettingsSnapshot? = null

    private val isCustomColor get() = selectedThemeColor !in COLOR_OPTIONS

    private data class SettingsSnapshot(
        val sector: Int,
        val staticKey: String,
        val dynamicKeyEnabled: Boolean,
        val flashEnabled: Boolean,
        val verifyIntegrity: Boolean,
        val sellerMode: Boolean,
        val debugEnabled: Boolean,
        val keepScreenOn: Boolean,
        val soundEnabled: Boolean,
        val vibrationEnabled: Boolean,
        val decimalMode: Boolean,
        val distributedPos: Boolean,
        val broadcastEnabled: Boolean,
        val legalAge: Int,
        val themeColor: Int,
        val languageCode: String?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.advanced_settings)

        rowLanguage            = findViewById(R.id.rowLanguage)
        tvLanguageValue        = findViewById(R.id.tvLanguageValue)
        etSector               = findViewById(R.id.etSector)
        tilSector              = findViewById(R.id.tilSector)
        etStaticKey            = findViewById(R.id.etStaticKey)
        tilStaticKey           = findViewById(R.id.tilStaticKey)
        btnToggleKeyVisibility = findViewById(R.id.btnToggleKeyVisibility)
        btnGenerateKey         = findViewById(R.id.btnGenerateKey)
        swDynamicKey           = findViewById(R.id.swDynamicKey)
        swFlashEnabled         = findViewById(R.id.swFlashEnabled)
        swVerifyIntegrity      = findViewById(R.id.swVerifyIntegrity)
        swDebugEnabled         = findViewById(R.id.swDebugEnabled)
        swSellerMode           = findViewById(R.id.swSellerMode)
        swKeepScreenOn         = findViewById(R.id.swKeepScreenOn)
        swSoundEnabled         = findViewById(R.id.swSoundEnabled)
        swVibrationEnabled     = findViewById(R.id.swVibrationEnabled)
        swDecimalMode          = findViewById(R.id.swDecimalMode)
        swDistributedPos       = findViewById(R.id.swDistributedPos)
        swBroadcastEnabled     = findViewById(R.id.swBroadcastEnabled)
        etLegalAge             = findViewById(R.id.etLegalAge)
        tilLegalAge            = findViewById(R.id.tilLegalAge)
        colorSelectorLayout    = findViewById(R.id.colorSelectorLayout)
        btnSaveSettings        = findViewById(R.id.btnSaveSettings)


        rowLanguage.setOnClickListener { openLanguagePicker() }

        // Tapping anywhere in a switch row (outside the thumb) toggles that switch
        mapOf(
            R.id.rowDynamicKey      to swDynamicKey,
            R.id.rowFlashEnabled    to swFlashEnabled,
            R.id.rowVerifyIntegrity to swVerifyIntegrity,
            R.id.rowSellerMode      to swSellerMode,
            R.id.rowDebugEnabled    to swDebugEnabled,
            R.id.rowKeepScreenOn    to swKeepScreenOn,
            R.id.rowSoundEnabled    to swSoundEnabled,
            R.id.rowVibrationEnabled to swVibrationEnabled,
            R.id.rowDecimalMode     to swDecimalMode,
            R.id.rowDistributedPos  to swDistributedPos,
            R.id.rowBroadcastEnabled to swBroadcastEnabled,
        ).forEach { (rowId, sw) ->
            findViewById<LinearLayout>(rowId)?.setOnClickListener { sw.toggle() }
        }

        loadCurrentSettings()
        applyThemeToButtons()

        btnToggleKeyVisibility.setOnClickListener { toggleKeyVisibility() }
        btnGenerateKey.setOnClickListener { confirmGenerateKey() }
        btnSaveSettings.setOnClickListener { saveSettings() }

        onBackPressedDispatcher.addCallback(this) {
            saveSettings()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        saveSettings()
        return true
    }

    private fun openLanguagePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intent = Intent(Settings.ACTION_APP_LOCALE_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } else {
            val langEntries = getLanguageEntries(this)
            val langNames = langEntries.map { it.first }.toTypedArray()
            val currentCode = pendingLangCode ?: getEffectiveLanguage(this)
            val currentIndex = langEntries.indexOfFirst { it.second == currentCode }.coerceAtLeast(0)

            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.setting_language))
                .setSingleChoiceItems(langNames, currentIndex) { dlg, which ->
                    val chosen = langEntries[which]
                    pendingLangCode = chosen.second
                    tvLanguageValue.text = chosen.first
                    dlg.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            applyThemeToDialogButtons(dialog)
        }
    }

    private fun applyThemeToButtons() {
        val color = selectedThemeColor
        val tintList = ColorStateList.valueOf(color)
        val rippleTint = ColorStateList.valueOf(rippleColor(color))

        // Action bar: surface/window background color (not the theme color) so topbar is unified
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val bgColor = ta.getColor(0, Color.WHITE)
        ta.recycle()
        supportActionBar?.setBackgroundDrawable(ColorDrawable(bgColor))
        @Suppress("DEPRECATION")
        window.statusBarColor = bgColor
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            contrastColor(bgColor) == Color.BLACK

        // Icon-only buttons: filled with theme color, icon in contrast color for legibility
        val contrastTint = ColorStateList.valueOf(contrastColor(color))
        btnToggleKeyVisibility.backgroundTintList = tintList
        btnToggleKeyVisibility.iconTint = contrastTint
        btnToggleKeyVisibility.rippleColor = rippleTint
        btnGenerateKey.backgroundTintList = tintList
        btnGenerateKey.iconTint = contrastTint
        btnGenerateKey.rippleColor = rippleTint

        // Save button: filled background + contrast text + ripple
        btnSaveSettings.backgroundTintList = tintList
        btnSaveSettings.setTextColor(contrastColor(color))
        btnSaveSettings.rippleColor = ColorStateList.valueOf(rippleColor(color, alpha = 80))

        // Language system-settings button (API 33+): now handled by row click

        // Section headers: tint with theme color for visual grouping
        for (id in listOf(R.id.tvSectionLanguage, R.id.tvSectionCard,
                           R.id.tvSectionOptions, R.id.tvSectionOther)) {
            (findViewById<TextView>(id))?.setTextColor(color)
        }

        // Switches: thumb = theme color when checked, unchecked uses theme defaults
        val switchThumbTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color, 0xFFBDBDBD.toInt())
        )
        val switchTrackTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(rippleColor(color, alpha = 128), 0xFF9E9E9E.toInt())
        )
        for (sw in listOf(swDynamicKey, swFlashEnabled, swVerifyIntegrity,
                          swSellerMode, swDebugEnabled, swKeepScreenOn, swSoundEnabled, swVibrationEnabled,
                          swDecimalMode, swDistributedPos, swBroadcastEnabled)) {
            sw.thumbTintList = switchThumbTint
            sw.trackTintList = switchTrackTint
        }

        // TextInputLayouts: box stroke and floating label when focused
        val focusedColorList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(color, Color.GRAY)
        )
        for (til in listOf(tilSector, tilStaticKey, tilLegalAge)) {
            til.setBoxStrokeColorStateList(focusedColorList)
            til.setHintTextColor(focusedColorList)
        }
    }

    private fun loadCurrentSettings() {
        val currentLangCode = getEffectiveLanguage(this)
        val langEntries = getLanguageEntries(this)
        val selectedLangName = langEntries.firstOrNull { it.second == currentLangCode }?.first
            ?: langEntries.firstOrNull()?.first
        tvLanguageValue.text = selectedLangName ?: getString(R.string.setting_language_desc)
        pendingLangCode = null  // null = user has not changed language during this session

        etSector.setText(getTargetSector(this).toString())
        etStaticKey.setText(getStaticKey(this))
        swDynamicKey.isChecked = isDynamicKeyEnabled(this)
        swFlashEnabled.isChecked = isFlashEnabled(this)
        swVerifyIntegrity.isChecked = isVerifyIntegrityEnabled(this)
        swSellerMode.isChecked = isSellerModeEnabled(this)
        swDebugEnabled.isChecked = isDebugEnabled(this)
        swKeepScreenOn.isChecked = isKeepScreenOnEnabled(this)
        swSoundEnabled.isChecked = isSoundEnabled(this)
        swVibrationEnabled.isChecked = isVibrationEnabled(this)
        swDecimalMode.isChecked = isDecimalModeEnabled(this)
        swDistributedPos.isChecked = isDistributedPosEnabled(this)
        swBroadcastEnabled.isChecked = isBroadcastEnabled(this)
        etLegalAge.setText(getLegalAge(this).toString())
        selectedThemeColor = getThemeColor(this)
        originalSettingsSnapshot = collectCurrentSettingsSnapshot()

        // Key is hidden by default
        etStaticKey.transformationMethod = PasswordTransformationMethod.getInstance()
        etStaticKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyVisible = false
        btnToggleKeyVisibility.setIconResource(R.drawable.ic_eye)

        renderColorSwatches()
    }

    private fun collectCurrentSettingsSnapshot(): SettingsSnapshot {
        val currentLanguageCode = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            getEffectiveLanguage(this)
        } else {
            null
        }

        return SettingsSnapshot(
            sector = getTargetSector(this),
            staticKey = getStaticKey(this),
            dynamicKeyEnabled = isDynamicKeyEnabled(this),
            flashEnabled = isFlashEnabled(this),
            verifyIntegrity = isVerifyIntegrityEnabled(this),
            sellerMode = isSellerModeEnabled(this),
            debugEnabled = isDebugEnabled(this),
            keepScreenOn = isKeepScreenOnEnabled(this),
            soundEnabled = isSoundEnabled(this),
            vibrationEnabled = isVibrationEnabled(this),
            decimalMode = isDecimalModeEnabled(this),
            distributedPos = isDistributedPosEnabled(this),
            broadcastEnabled = isBroadcastEnabled(this),
            legalAge = getLegalAge(this),
            themeColor = getThemeColor(this),
            languageCode = currentLanguageCode,
        )
    }

    private fun renderColorSwatches() {
        colorSelectorLayout.removeAllViews()
        val density = resources.displayMetrics.density
        val selectedSizePx = (44 * density).toInt()
        val normalSizePx = (28 * density).toInt()
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
                applyThemeToButtons()
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
            customSwatch.textSize = SWATCH_NORMAL_TEXT_SP
        }
        customSwatch.background = drawable
        customSwatch.contentDescription = getString(R.string.color_custom_swatch)

        customSwatch.setOnClickListener { showCustomColorPicker() }
        colorSelectorLayout.addView(customSwatch)
    }

    private fun showCustomColorPicker() {
        val wheel = ColorWheelView(this)
        if (isCustomColor) {
            wheel.setColor(selectedThemeColor)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.color_pick_title))
            .setView(wheel)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                selectedThemeColor = wheel.getColor()
                applyThemeToButtons()
                renderColorSwatches()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        applyThemeToDialogButtons(dialog)
    }

    private fun applyThemeToDialogButtons(dialog: AlertDialog) {
        val color = selectedThemeColor
        val rippleTint = ColorStateList.valueOf(rippleColor(color))
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { which ->
                val btn = dialog.getButton(which) ?: return@forEach
                btn.setTextColor(color)
                (btn as? MaterialButton)?.rippleColor = rippleTint
            }
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
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.generate_key_confirm_title)
            .setMessage(R.string.generate_key_confirm_message)
            .setPositiveButton(android.R.string.ok) { _, _ -> generateRandomKey() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        applyThemeToDialogButtons(dialog)
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
        // Language is only managed in-app on API < 33; on API 33+ the system handles it.
        val newLangCode: String?
        val oldLangCode: String?
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            newLangCode = pendingLangCode ?: getEffectiveLanguage(this)
            oldLangCode = getEffectiveLanguage(this)
        } else {
            newLangCode = null
            oldLangCode = null
        }

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
        val dynamicKeyEnabled = swDynamicKey.isChecked
        val flashEnabled = swFlashEnabled.isChecked
        val verifyIntegrity = swVerifyIntegrity.isChecked
        val sellerMode = swSellerMode.isChecked
        val debugEnabled = swDebugEnabled.isChecked
        val keepScreenOn = swKeepScreenOn.isChecked
        val soundEnabled = swSoundEnabled.isChecked
        val vibrationEnabled = swVibrationEnabled.isChecked
        val decimalMode = swDecimalMode.isChecked
        val distributedPos = swDistributedPos.isChecked
        val broadcastEnabled = swBroadcastEnabled.isChecked
        val legalAge = etLegalAge.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 99) ?: DEFAULT_LEGAL_AGE

        val newSettingsSnapshot = SettingsSnapshot(
            sector = sector,
            staticKey = staticKey.ifEmpty { BuildConfig.NFC_PSK },
            dynamicKeyEnabled = dynamicKeyEnabled,
            flashEnabled = flashEnabled,
            verifyIntegrity = verifyIntegrity,
            sellerMode = sellerMode,
            debugEnabled = debugEnabled,
            keepScreenOn = keepScreenOn,
            soundEnabled = soundEnabled,
            vibrationEnabled = vibrationEnabled,
            decimalMode = decimalMode,
            distributedPos = distributedPos,
            broadcastEnabled = broadcastEnabled,
            legalAge = legalAge,
            themeColor = selectedThemeColor,
            languageCode = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) newLangCode else null,
        )
        val hasChanges = newSettingsSnapshot != originalSettingsSnapshot

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TARGET_SECTOR, sector)
            .putString(KEY_STATIC_KEY, staticKey.ifEmpty { BuildConfig.NFC_PSK })
            .putBoolean(KEY_DYNAMIC_KEY_ENABLED, dynamicKeyEnabled)
            .putBoolean(KEY_FLASH_ENABLED, flashEnabled)
            .putBoolean(KEY_VERIFY_INTEGRITY, verifyIntegrity)
            .putBoolean(KEY_SELLER_MODE, sellerMode)
            .putBoolean(KEY_DEBUG_ENABLED, debugEnabled)
            .putBoolean(KEY_KEEP_SCREEN_ON, keepScreenOn)
            .putBoolean(KEY_SOUND_ENABLED, soundEnabled)
            .putBoolean(KEY_VIBRATION_ENABLED, vibrationEnabled)
            .putBoolean(KEY_DECIMAL_MODE, decimalMode)
            .putBoolean(KEY_DISTRIBUTED_POS, distributedPos)
            .putBoolean(KEY_BROADCAST_ENABLED, broadcastEnabled)
            .putInt(KEY_LEGAL_AGE, legalAge)
            .putInt(KEY_THEME_COLOR, selectedThemeColor)
            .apply()

        originalSettingsSnapshot = newSettingsSnapshot
        if (hasChanges) {
            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        }

        if (newLangCode != null && newLangCode != oldLangCode) {
            // Apply locale via AppCompat; it triggers a configuration change that recreates activities.
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(newLangCode))
        } else {
            finish()
        }
    }
}
