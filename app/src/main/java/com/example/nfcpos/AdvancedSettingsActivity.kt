package com.example.nfcpos

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
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
        const val DEFAULT_SECTOR = 14

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
    }

    private lateinit var etSector: TextInputEditText
    private lateinit var etStaticKey: TextInputEditText
    private lateinit var btnToggleKeyVisibility: MaterialButton
    private lateinit var btnGenerateKey: MaterialButton
    private lateinit var cbDynamicKey: MaterialCheckBox
    private lateinit var btnSaveSettings: MaterialButton

    private var keyVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advanced_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.advanced_settings)

        etSector              = findViewById(R.id.etSector)
        etStaticKey           = findViewById(R.id.etStaticKey)
        btnToggleKeyVisibility = findViewById(R.id.btnToggleKeyVisibility)
        btnGenerateKey        = findViewById(R.id.btnGenerateKey)
        cbDynamicKey          = findViewById(R.id.cbDynamicKey)
        btnSaveSettings       = findViewById(R.id.btnSaveSettings)

        loadCurrentSettings()

        btnToggleKeyVisibility.setOnClickListener { toggleKeyVisibility() }
        btnGenerateKey.setOnClickListener { confirmGenerateKey() }
        btnSaveSettings.setOnClickListener { saveSettings() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadCurrentSettings() {
        etSector.setText(getTargetSector(this).toString())
        etStaticKey.setText(getStaticKey(this))
        cbDynamicKey.isChecked = isDynamicKeyEnabled(this)

        // Key is hidden by default
        etStaticKey.transformationMethod = PasswordTransformationMethod.getInstance()
        etStaticKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyVisible = false
        btnToggleKeyVisibility.setIconResource(R.drawable.ic_eye)
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

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_TARGET_SECTOR, sector)
            .putString(KEY_STATIC_KEY, staticKey.ifEmpty { BuildConfig.NFC_PSK })
            .putBoolean(KEY_DYNAMIC_KEY_ENABLED, dynamicKeyEnabled)
            .apply()

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }
}
