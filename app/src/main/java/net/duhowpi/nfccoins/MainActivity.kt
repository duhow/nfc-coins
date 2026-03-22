package net.duhowpi.nfccoins

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NFC POS – Monedero con Mifare Classic
 *
 * Lee el sector 14 de una tarjeta Mifare Classic.
 * Genera una clave única por tarjeta derivada del UID + PSK.
 * Los botones de descuento actúan como toggles: si ninguno está activo,
 * acercar la tarjeta muestra el saldo; si uno está activo, descuenta automáticamente.
 * Incluye gestión de tarjetas: añadir saldo y formatear con claves estándar.
 *
 * Formato del bloque de datos (bloque 56 = primer bloque del sector 14):
 *   Bytes 0-1 : saldo en big-endian (uint16)
 *   Bytes 2-15: reservados (0x00)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DATA_BLOCK_OFFSET = 0
        private const val KEY_LEN = 6

        private fun hexKey(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

        // Claves NFC estándar a probar al formatear (fábrica y NDEF)
        private val STANDARD_KEYS = listOf(
            hexKey(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), // Fábrica (por defecto)
            hexKey(0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5), // NDEF Key A
            hexKey(0xD3, 0xF7, 0xD3, 0xF7, 0xD3, 0xF7), // NDEF datos
            hexKey(0x00, 0x00, 0x00, 0x00, 0x00, 0x00), // Todo ceros
            hexKey(0x4D, 0x3A, 0x99, 0xC3, 0x51, 0xDD), // NDEF Key B común
            hexKey(0x1A, 0x98, 0x2C, 0x7E, 0x45, 0x9A), // MAD key
            hexKey(0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5), // Key B común
        )

        // Máximo valor de saldo (uint16: 2 bytes big-endian)
        private const val MAX_BALANCE = 0xFFFF

        // Bits de acceso estándar: lectura y escritura con Key A en todos los bloques de datos
        private val ACCESS_BITS = hexKey(0xFF, 0x07, 0x80, 0x69)

        // Clave de fábrica Mifare Classic (todos los bytes a 0xFF)
        private val FACTORY_KEY = hexKey(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)

        private const val AUTO_RESET_DELAY_MS = 7000L
        private const val VIBRATE_DURATION_MS = 200L
        private val FLASH_TOKEN = Any()
        private val BEEP_TOKEN = Any()
    }

    private enum class PendingAction { NONE, ADD_BALANCE, FORMAT_CARD, RESET_CARD }

    private lateinit var rootLayout: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBalance: EditText
    private lateinit var tvCardId: TextView
    private lateinit var tvBalanceBefore: TextView
    private lateinit var tvBalanceAfter: TextView
    private lateinit var tvActualBalance: TextView
    private lateinit var layoutBeforeAfter: LinearLayout
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var etHiddenInput: EditText

    private var nfcAdapter: NfcAdapter? = null
    private var toneGenerator: ToneGenerator? = null

    private var currentBalance: Int = -1
    private var currentTag: Tag? = null
    private var pendingAction: PendingAction = PendingAction.NONE
    private var pendingAddAmount: Int = 0
    private var customDeductAmount: Int = 0
    private var isCustomAmountMode = false

    private val handler = Handler(Looper.getMainLooper())
    private val autoResetRunnable = Runnable { resetToWaiting() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout        = findViewById(R.id.rootLayout)
        tvStatus          = findViewById(R.id.tvStatus)
        tvBalance         = findViewById(R.id.tvBalance)
        tvCardId          = findViewById(R.id.tvCardId)
        tvBalanceBefore   = findViewById(R.id.tvBalanceBefore)
        tvBalanceAfter    = findViewById(R.id.tvBalanceAfter)
        tvActualBalance   = findViewById(R.id.tvActualBalance)
        layoutBeforeAfter = findViewById(R.id.layoutBeforeAfter)
        toggleGroup       = findViewById(R.id.toggleGroup)
        etHiddenInput     = findViewById(R.id.etHiddenInput)

        setupBalanceEditText()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            toggleGroup.isEnabled = false
            return
        }

        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            handleNfcIntent(intent)
        }

        // Pre-warm the audio hardware so the first startTone() call fires without the
        // cold-start latency that collapses the first two beeps of a multi-beep sequence.
        initToneGenerator()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_management -> {
                showCardManagementDialog()
                true
            }
            R.id.action_advanced_settings -> {
                startActivity(Intent(this, AdvancedSettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> handler.post { handleTag(tag) } },
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null
        )
        if (AdvancedSettingsActivity.isKeepScreenOnEnabled(this)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(BEEP_TOKEN)
        toneGenerator?.release()
        toneGenerator = null
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    // -------------------------------------------------------------------------
    // NFC handling
    // -------------------------------------------------------------------------

    /** Entry point for tags discovered via reader mode (called on main thread). */
    private fun handleTag(tag: Tag) {
        triggerVibration()
        if (!tag.techList.contains(MifareClassic::class.java.name)) {
            tvStatus.text = getString(R.string.unsupported_card)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }

        handler.removeCallbacks(autoResetRunnable)
        currentTag = tag
        val uid = tag.id
        tvCardId.text = getString(R.string.card_id_format, uid.toHex())

        when (pendingAction) {
            PendingAction.ADD_BALANCE -> {
                pendingAction = PendingAction.NONE
                addBalanceToCard(tag)
            }
            PendingAction.FORMAT_CARD -> {
                pendingAction = PendingAction.NONE
                formatCard(tag)
            }
            PendingAction.RESET_CARD -> {
                pendingAction = PendingAction.NONE
                resetCard(tag)
            }
            PendingAction.NONE -> {
                val cardKey = deriveCardKey(uid)
                when {
                    toggleGroup.checkedButtonId == R.id.btnDeduct1 -> readAndDeduct(tag, cardKey, 1)
                    toggleGroup.checkedButtonId == R.id.btnDeduct2 -> readAndDeduct(tag, cardKey, 2)
                    customDeductAmount > 0 -> {
                        val amount = customDeductAmount
                        customDeductAmount = 0
                        isCustomAmountMode = false
                        clearHiddenInput()
                        readAndDeduct(tag, cardKey, amount, isCustomAmount = true)
                    }
                    else -> readAndShowBalance(tag, cardKey)
                }
            }
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        handleTag(tag)
    }

    /** Modo sin botón activo: solo muestra el saldo en grande. */
    private fun readAndShowBalance(tag: Tag, cardKey: ByteArray) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(sector, cardKey)) {
                tvStatus.text = getString(R.string.auth_failed)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }
            val blockIndex = mifare.sectorToBlock(sector) + DATA_BLOCK_OFFSET
            val data = mifare.readBlock(blockIndex)
            currentBalance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            setBalanceText(currentBalance.toString())
            layoutBeforeAfter.visibility = View.GONE
            tvActualBalance.visibility = View.GONE
            tvStatus.text = getString(R.string.card_read_ok)
            playSuccessBeep()
            scheduleAutoReset()
        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_reading, e.message)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    /** Modo con botón activo: descuenta monedas y muestra saldo inicial → final en grande. */
    private fun readAndDeduct(tag: Tag, cardKey: ByteArray, amount: Int, isCustomAmount: Boolean = false) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(sector, cardKey)) {
                tvStatus.text = getString(R.string.auth_failed)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }
            val blockIndex = mifare.sectorToBlock(sector) + DATA_BLOCK_OFFSET
            val data = mifare.readBlock(blockIndex)
            val balance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

            if (balance < amount) {
                currentBalance = balance
                if (isCustomAmount) {
                    setBalanceText(amount.toString())
                    tvActualBalance.text = balance.toString()
                    tvActualBalance.visibility = View.VISIBLE
                } else {
                    setBalanceText(balance.toString())
                    tvActualBalance.visibility = View.GONE
                }
                layoutBeforeAfter.visibility = View.GONE
                tvStatus.text = getString(R.string.insufficient_balance)
                flashRedBackground()
                playInsufficientBalanceBeep()
                scheduleAutoReset()
                return
            }

            val newBalance = balance - amount
            val block = ByteArray(MifareClassic.BLOCK_SIZE)
            block[0] = ((newBalance shr 8) and 0xFF).toByte()
            block[1] = (newBalance and 0xFF).toByte()
            mifare.writeBlock(blockIndex, block)

            currentBalance = newBalance
            setBalanceText(newBalance.toString())
            tvBalanceBefore.text = balance.toString()
            tvBalanceAfter.text = newBalance.toString()
            layoutBeforeAfter.visibility = View.VISIBLE
            tvActualBalance.visibility = View.GONE
            tvStatus.text = getString(R.string.deduct_ok, amount)
            playSuccessBeep()
            scheduleAutoReset()

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Flash background color temporarily
    // -------------------------------------------------------------------------

    private fun flashBackground(@ColorRes colorRes: Int) {
        if (!AdvancedSettingsActivity.isFlashEnabled(this)) return
        handler.removeCallbacksAndMessages(FLASH_TOKEN)
        rootLayout.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        handler.postDelayed({
            rootLayout.setBackgroundColor(Color.TRANSPARENT)
        }, FLASH_TOKEN, 3000)
    }

    private fun flashRedBackground() = flashBackground(R.color.error_red_dark)

    // -------------------------------------------------------------------------
    // Beep feedback: 1 beep = success, 2 beeps = NFC error, 3 beeps = no balance
    // -------------------------------------------------------------------------

    // Creates the ToneGenerator eagerly (called from onCreate) so the audio hardware session is
    // already open before the first NFC tap. A cold ToneGenerator causes the first startTone()
    // to block briefly while the hardware initialises, making the first beep start late while
    // subsequent handler-scheduled beeps fire on time — causing them to collapse together.
    //
    // Each ToneGenerator has its own internal AudioTrack and native tone thread. That thread is
    // started by the first startTone() call — not by construction. To warm up the pipeline we
    // must call startTone() on the exact same instance that will be used for real beeps; warming
    // a throwaway instance (even at volume=0) does nothing for a separately constructed instance.
    //
    // We call startTone() with a 1 ms duration on the real instance immediately after
    // construction. Because the Android audio output latency is typically 50–200 ms, the 1 ms of
    // generated audio is flushed before it ever reaches the speaker, so the user hears nothing.
    // startTone() blocks internally until its native thread has started and AudioTrack is open,
    // so by the time it returns the instance is fully warm for all subsequent calls.
    private fun initToneGenerator() {
        if (toneGenerator != null) return
        try {
            val tg = ToneGenerator(AudioManager.STREAM_DTMF, ToneGenerator.MAX_VOLUME)
            toneGenerator = tg
            tg.startTone(ToneGenerator.TONE_CDMA_LOW_L, 1)
        } catch (_: Exception) {}
    }

    // Reuses the single ToneGenerator created in initToneGenerator() for the lifetime of the
    // activity. startTone() stops any in-progress tone before playing the new one, so no explicit
    // teardown is needed between calls.
    private fun playBeep(count: Int, toneType: Int, durationMs: Int, intervalMs: Int = 100) {
        handler.removeCallbacksAndMessages(BEEP_TOKEN)
        if (count <= 0) return
        if (!AdvancedSettingsActivity.isSoundEnabled(this)) return
        initToneGenerator()
        val toneGen = toneGenerator ?: return
        playBeepChain(toneGen, count, toneType, durationMs, intervalMs)
    }

    // Plays beeps one at a time by scheduling each next beep from inside the previous callback,
    // so inter-beep intervals are measured from when the previous beep actually fired rather than
    // from the original call site. This prevents timing drift caused by main-thread congestion.
    private fun playBeepChain(
        toneGen: ToneGenerator, remaining: Int, toneType: Int, durationMs: Int, intervalMs: Int
    ) {
        val started = try { toneGen.startTone(toneType, durationMs) } catch (_: Exception) { false }
        if (!started) {
            // ToneGenerator has become invalid (e.g. audio system interrupted); discard and
            // recreate it immediately so the next beep call finds a warm instance.
            toneGenerator = null
            initToneGenerator()
            return
        }
        if (remaining > 1) {
            handler.postDelayed({
                playBeepChain(toneGen, remaining - 1, toneType, durationMs, intervalMs)
            }, BEEP_TOKEN, (durationMs + intervalMs).toLong())
        }
    }

    // 1 long high-pitched beep → transaction confirmed (like a payment terminal approval)
    private fun playSuccessBeep() = playBeep(1, ToneGenerator.TONE_CDMA_LOW_L, 150)
    // 2 short mid-pitched beeps (900 Hz) → NFC reading error
    private fun playNfcErrorBeep() = playBeep(2, ToneGenerator.TONE_CDMA_MED_L, 150, 300)
    // 3 short low-pitched beeps (600 Hz) → insufficient balance (rejection)
    private fun playInsufficientBalanceBeep() = playBeep(3, ToneGenerator.TONE_CDMA_LOW_L, 120)

    private fun triggerVibration() {
        if (!AdvancedSettingsActivity.isVibrationEnabled(this)) return
        @Suppress("DEPRECATION")
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(VIBRATE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(VIBRATE_DURATION_MS)
        }
    }

    private fun scheduleAutoReset() {
        handler.removeCallbacks(autoResetRunnable)
        handler.postDelayed(autoResetRunnable, AUTO_RESET_DELAY_MS)
    }

    /**
     * Sets pendingAction and manages the auto-reset timer accordingly.
     * For ADD_BALANCE the timer is cancelled so the state persists until the user taps the
     * balance display or a deduction button.
     * For other non-NONE actions the existing timer is cancelled (via scheduleAutoReset, which
     * always removes the callback before re-posting) and a fresh 7-second countdown is started,
     * preventing stale timers from clearing the new state.
     * For NONE the timer is simply removed (full reset is handled by resetToWaiting).
     */
    private fun setPendingAction(action: PendingAction) {
        pendingAction = action
        when (action) {
            PendingAction.NONE -> handler.removeCallbacks(autoResetRunnable)
            PendingAction.ADD_BALANCE -> handler.removeCallbacks(autoResetRunnable)
            else -> scheduleAutoReset() // removes any existing callback before posting the new one
        }
    }

    private fun resetToWaiting() {
        handler.removeCallbacksAndMessages(FLASH_TOKEN)
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        tvCardId.text = getString(R.string.no_card_detected)
        currentBalance = -1
        resetBalanceToInitial()
        layoutBeforeAfter.visibility = View.GONE
        tvActualBalance.visibility = View.GONE
        tvStatus.text = getString(R.string.waiting_card)
        pendingAction = PendingAction.NONE
        pendingAddAmount = 0
    }

    private fun cancelAddBalance() {
        pendingAction = PendingAction.NONE
        pendingAddAmount = 0
        resetBalanceToInitial()
        tvStatus.text = getString(R.string.waiting_card)
    }

    // -------------------------------------------------------------------------
    // Gestión de tarjetas
    // -------------------------------------------------------------------------

    private fun showCardManagementDialog() {
        // Cancel any running auto-reset so it doesn't interfere while the user
        // is actively interacting with the dialog.
        handler.removeCallbacks(autoResetRunnable)
        AlertDialog.Builder(this)
            .setTitle(R.string.card_management)
            .setItems(
                arrayOf(
                    getString(R.string.action_add_balance),
                    getString(R.string.action_format_card),
                    getString(R.string.action_reset_card)
                )
            ) { _, which ->
                when (which) {
                    0 -> showAddAmountDialog()
                    1 -> {
                        setPendingAction(PendingAction.FORMAT_CARD)
                        tvStatus.text = getString(R.string.tap_card_to_format)
                    }
                    2 -> {
                        setPendingAction(PendingAction.RESET_CARD)
                        tvStatus.text = getString(R.string.tap_card_to_reset)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAction = PendingAction.NONE
            }
            .show()
    }

    /** Muestra el diálogo para introducir la cantidad a añadir antes de acercar la tarjeta. */
    private fun showAddAmountDialog() {
        val paddingHoriz = (24 * resources.displayMetrics.density).toInt()
        val paddingVert = (8 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingHoriz, paddingVert, paddingHoriz, 0)
        }

        val input = EditText(this).apply {
            hint = getString(R.string.hint_add_amount)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        container.addView(input)

        // Use a nullable var so the TextWatcher can reference confirmButton
        // after the dialog is shown (buttons are created by show())
        var confirmButton: android.widget.Button? = null

        input.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val value = s?.toString()?.toIntOrNull() ?: 0
                confirmButton?.isEnabled = value > 0
                val normalized = if (value > 0) value.toString() else null
                when {
                    value > MAX_BALANCE -> {
                        isUpdating = true
                        s?.replace(0, s.length, MAX_BALANCE.toString())
                        isUpdating = false
                    }
                    normalized != null && s.toString() != normalized -> {
                        // Normalize leading zeros (e.g. "0001" → "1")
                        isUpdating = true
                        s?.replace(0, s.length, normalized)
                        isUpdating = false
                    }
                }
            }
        })

        // Hide keyboard when focus leaves the EditText (e.g. when tabbing to dialog buttons)
        input.setOnFocusChangeListener { v, hasFocus ->
            if (!hasFocus) {
                hideKeyboardFrom(v)
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.action_add_balance)
            .setView(container)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val amount = input.text.toString().toIntOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(this, getString(R.string.invalid_amount), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingAddAmount = amount
                setPendingAction(PendingAction.ADD_BALANCE)
                setBalanceText("+$amount")
                tvStatus.text = getString(R.string.tap_card_to_add)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAction = PendingAction.NONE
                pendingAddAmount = 0
            }
            .setNeutralButton("+1", null) // null prevents auto-dismiss; listener set below
            .show()

        confirmButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        confirmButton?.isEnabled = false

        // Override neutral button click to increment without dismissing the dialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            val current = input.text.toString().toIntOrNull() ?: 0
            val newValue = (current + 1).coerceAtMost(MAX_BALANCE)
            input.setText(newValue.toString())
            input.setSelection(input.text.length)
        }
    }

    /**
     * Aplica el saldo pendiente a la tarjeta. Solo intenta con la clave derivada del UID;
     * si falla la autenticación, la tarjeta no está formateada y se muestra un error.
     */
    private fun addBalanceToCard(tag: Tag) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val uid = tag.id
        val cardKey = deriveCardKey(uid)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(sector, cardKey)) {
                tvStatus.text = getString(R.string.card_not_formatted)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }
            val blockIndex = mifare.sectorToBlock(sector) + DATA_BLOCK_OFFSET
            val data = mifare.readBlock(blockIndex)
            val oldBalance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            val newBalance = oldBalance + pendingAddAmount
            if (newBalance > MAX_BALANCE) {
                Toast.makeText(this, getString(R.string.balance_too_high), Toast.LENGTH_SHORT).show()
                pendingAddAmount = 0
                scheduleAutoReset()
                return
            }
            val block = ByteArray(MifareClassic.BLOCK_SIZE)
            block[0] = ((newBalance shr 8) and 0xFF).toByte()
            block[1] = (newBalance and 0xFF).toByte()
            mifare.writeBlock(blockIndex, block)

            currentBalance = newBalance
            setBalanceText(newBalance.toString())
            tvBalanceBefore.text = oldBalance.toString()
            tvBalanceAfter.text = newBalance.toString()
            layoutBeforeAfter.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.balance_added_ok, pendingAddAmount)
            flashBackground(R.color.success_green)
            playSuccessBeep()
            scheduleAutoReset()
        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    /**
     * Intenta formatear el sector configurado de la tarjeta.
     * Primero prueba la clave derivada del UID: si ya está formateada, solo pone el saldo a cero.
     * Si no, busca una clave estándar y escribe el sector con la clave derivada.
     */
    private fun formatCard(tag: Tag) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val uid = tag.id
        val derivedKey = deriveCardKey(uid)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }

        try {
            mifare.connect()

            // Si ya está formateada con la clave derivada, solo reinicia el saldo a 0
            if (mifare.authenticateSectorWithKeyA(sector, derivedKey)) {
                val blockIndex = mifare.sectorToBlock(sector) + DATA_BLOCK_OFFSET
                val data = mifare.readBlock(blockIndex)
                val oldBalance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                mifare.writeBlock(blockIndex, ByteArray(MifareClassic.BLOCK_SIZE))

                currentBalance = 0
                setBalanceText("0")
                tvBalanceBefore.text = oldBalance.toString()
                tvBalanceAfter.text = "0"
                layoutBeforeAfter.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.format_reset_success)
                flashBackground(R.color.success_purple_dark)
                playSuccessBeep()
                scheduleAutoReset()
                return
            }

            // Buscar clave estándar que permita acceder al sector
            var foundKey: ByteArray? = null
            var usedKeyA = true

            for (key in STANDARD_KEYS) {
                if (mifare.authenticateSectorWithKeyA(sector, key)) {
                    foundKey = key
                    usedKeyA = true
                    break
                }
                if (mifare.authenticateSectorWithKeyB(sector, key)) {
                    foundKey = key
                    usedKeyA = false
                    break
                }
            }

            if (foundKey == null) {
                tvStatus.text = getString(R.string.format_no_key_found)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }

            // Reautenticar con la clave encontrada
            val reAuthed = if (usedKeyA) {
                mifare.authenticateSectorWithKeyA(sector, foundKey)
            } else {
                mifare.authenticateSectorWithKeyB(sector, foundKey)
            }
            if (!reAuthed) {
                tvStatus.text = getString(R.string.auth_failed)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }

            // Limpiar bloques de datos del sector
            val sectorStart = mifare.sectorToBlock(sector)
            val blocksInSector = mifare.getBlockCountInSector(sector)
            val emptyBlock = ByteArray(MifareClassic.BLOCK_SIZE)
            for (i in 0 until blocksInSector - 1) {
                mifare.writeBlock(sectorStart + i, emptyBlock)
            }

            // Escribir trailer del sector con la clave derivada
            // [Key A (6 bytes)] [Access bits (4 bytes)] [Key B (6 bytes)]
            val trailer = ByteArray(MifareClassic.BLOCK_SIZE)
            System.arraycopy(derivedKey, 0, trailer, 0, KEY_LEN)
            System.arraycopy(ACCESS_BITS, 0, trailer, KEY_LEN, ACCESS_BITS.size)
            System.arraycopy(derivedKey, 0, trailer, KEY_LEN + ACCESS_BITS.size, KEY_LEN)
            mifare.writeBlock(sectorStart + blocksInSector - 1, trailer)

            val foundKeyHex = foundKey.toHex()
            val newKeyHex = derivedKey.toHex()
            val keyType = if (usedKeyA) "A" else "B"

            currentBalance = 0
            setBalanceText("0")
            layoutBeforeAfter.visibility = View.GONE
            tvStatus.text = getString(R.string.format_success)
            flashBackground(R.color.success_purple_dark)
            playSuccessBeep()
            scheduleAutoReset()

            if (AdvancedSettingsActivity.isDebugEnabled(this)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.format_success)
                    .setMessage(getString(R.string.format_success_message, keyType, foundKeyHex, newKeyHex))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Derivación de clave: HMAC-SHA1(PSK, UID) → primeros 6 bytes
    // Si la clave es exactamente 12 caracteres hex (6 bytes), se usa directamente
    // en modo estático. En modo dinámico siempre se hace HMAC con el PSK.
    // -------------------------------------------------------------------------

    /** Convierte una cadena hex (con posibles espacios y ':') en ByteArray, o null si no es válida. */
    private fun tryParseHexKey(raw: String): ByteArray? {
        val hex = raw.replace(" ", "").replace(":", "").uppercase()
        if (hex.length != KEY_LEN * 2) return null
        return try {
            ByteArray(KEY_LEN) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            null
        }
    }

    private fun deriveCardKey(uid: ByteArray): ByteArray {
        val psk = AdvancedSettingsActivity.getStaticKey(this)
        val useDynamic = AdvancedSettingsActivity.isDynamicKeyEnabled(this)

        // Si la clave estática es directamente un valor hex de 6 bytes y estamos en modo estático,
        // la usamos tal cual como clave de la tarjeta.
        if (!useDynamic) {
            tryParseHexKey(psk)?.let { return it }
        }

        return try {
            if (useDynamic) {
                val mac = Mac.getInstance("HmacSHA1")
                val secretKey = SecretKeySpec(psk.toByteArray(Charsets.UTF_8), "HmacSHA1")
                mac.init(secretKey)
                mac.doFinal(uid).take(KEY_LEN).toByteArray()
            } else {
                // Clave estática no-hex: usar los primeros 6 bytes del hash SHA-256 de la clave
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                digest.digest(psk.toByteArray(Charsets.UTF_8)).take(KEY_LEN).toByteArray()
            }
        } catch (e: Exception) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(psk.toByteArray(Charsets.UTF_8))
            if (useDynamic) digest.update(uid)
            digest.digest().take(KEY_LEN).toByteArray()
        }
    }

    // -------------------------------------------------------------------------
    // Reinicio de tarjeta: pone datos a 0x00 y restaura clave de fábrica FF:FF
    // -------------------------------------------------------------------------

    private fun resetCard(tag: Tag) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val uid = tag.id
        val derivedKey = deriveCardKey(uid)

        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }

        try {
            mifare.connect()

            // Intentar con clave derivada primero, luego con claves estándar
            var authenticated = mifare.authenticateSectorWithKeyA(sector, derivedKey)
            if (!authenticated) {
                for (key in STANDARD_KEYS) {
                    if (mifare.authenticateSectorWithKeyA(sector, key) ||
                        mifare.authenticateSectorWithKeyB(sector, key)) {
                        authenticated = true
                        break
                    }
                }
            }

            if (!authenticated) {
                tvStatus.text = getString(R.string.reset_card_no_key)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }

            // Limpiar bloques de datos del sector (poner a 0x00)
            val sectorStart = mifare.sectorToBlock(sector)
            val blocksInSector = mifare.getBlockCountInSector(sector)
            val emptyBlock = ByteArray(MifareClassic.BLOCK_SIZE)
            for (i in 0 until blocksInSector - 1) {
                mifare.writeBlock(sectorStart + i, emptyBlock)
            }

            // Escribir trailer con clave de fábrica FF:FF y bits de acceso estándar
            // [Key A (6 bytes)] [Access bits (4 bytes)] [Key B (6 bytes)]
            val trailer = ByteArray(MifareClassic.BLOCK_SIZE)
            System.arraycopy(FACTORY_KEY, 0, trailer, 0, KEY_LEN)
            System.arraycopy(ACCESS_BITS, 0, trailer, KEY_LEN, ACCESS_BITS.size)
            System.arraycopy(FACTORY_KEY, 0, trailer, KEY_LEN + ACCESS_BITS.size, KEY_LEN)
            mifare.writeBlock(sectorStart + blocksInSector - 1, trailer)

            currentBalance = -1
            resetBalanceToInitial()
            layoutBeforeAfter.visibility = View.GONE
            tvStatus.text = getString(R.string.reset_card_success)
            flashBackground(R.color.success_purple_dark)
            playSuccessBeep()
            scheduleAutoReset()

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    /**
     * Sets the balance display text programmatically (non-editable mode).
     */
    private fun setBalanceText(text: String) {
        isCustomAmountMode = false
        tvBalance.setText(text)
        tvBalance.inputType = InputType.TYPE_NULL
        tvBalance.isFocusable = false
        tvBalance.isFocusableInTouchMode = false
    }

    /**
     * Resets the balance display to the initial "--" state and allows
     * the user to click on it to enter a custom deduction amount.
     */
    private fun resetBalanceToInitial() {
        isCustomAmountMode = false
        customDeductAmount = 0
        clearHiddenInput()
        tvBalance.setText(getString(R.string.balance_initial))
        tvBalance.inputType = InputType.TYPE_NULL
        tvBalance.isFocusable = false
        tvBalance.isFocusableInTouchMode = false
    }

    /**
     * Sets up the balance EditText so that:
     * - Tapping it when a balance is shown (card just read) immediately resets to waiting state.
     * - Tapping it when showing "--" focuses the hidden input and opens the keyboard for
     *   custom deduction entry, mirroring typed digits into the visible balance display.
     */
    private fun setupBalanceEditText() {
        tvBalance.isFocusable = false
        tvBalance.isFocusableInTouchMode = false
        tvBalance.inputType = InputType.TYPE_NULL

        tvBalance.setOnClickListener {
            when {
                currentBalance >= 0 -> {
                    // Tapping the displayed balance cancels the auto-reset and resets immediately.
                    handler.removeCallbacks(autoResetRunnable)
                    resetToWaiting()
                }
                pendingAction == PendingAction.ADD_BALANCE -> {
                    // Tapping the balance display while waiting to add balance cancels the operation.
                    cancelAddBalance()
                }
                currentBalance == -1 && !isCustomAmountMode && pendingAction == PendingAction.NONE -> {
                    enterCustomAmountMode()
                }
            }
        }

        // Mirror hidden-input digits into tvBalance while typing.
        etHiddenInput.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                if (!isCustomAmountMode) return
                val raw = s?.toString() ?: ""
                val value = raw.toIntOrNull() ?: 0
                when {
                    raw.isEmpty() -> {
                        tvBalance.setText(getString(R.string.balance_initial))
                    }
                    value > MAX_BALANCE -> {
                        isUpdating = true
                        s?.replace(0, s.length, MAX_BALANCE.toString())
                        isUpdating = false
                        tvBalance.setText(MAX_BALANCE.toString())
                    }
                    raw != value.toString() && value > 0 -> {
                        // Normalize leading zeros
                        isUpdating = true
                        s?.replace(0, s.length, value.toString())
                        isUpdating = false
                        tvBalance.setText(value.toString())
                    }
                    else -> {
                        tvBalance.setText(raw)
                    }
                }
                customDeductAmount = etHiddenInput.text.toString().toIntOrNull() ?: 0
                if (customDeductAmount > 0) toggleGroup.clearChecked()
            }
        })

        etHiddenInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && isCustomAmountMode) {
                isCustomAmountMode = false
                val amount = etHiddenInput.text.toString().toIntOrNull() ?: 0
                clearHiddenInput()
                if (amount > 0) {
                    // Keep the typed value visible and update status
                    customDeductAmount = amount
                    tvBalance.setText(amount.toString())
                    tvStatus.text = getString(R.string.tap_card_to_deduct)
                } else {
                    resetBalanceToInitial()
                }
            }
        }

        // Selecting a fixed-deduction toggle button clears any custom deduction amount or pending add-balance action
        toggleGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                if (pendingAction == PendingAction.ADD_BALANCE) {
                    cancelAddBalance()
                } else if (customDeductAmount > 0) {
                    resetBalanceToInitial()
                }
            }
        }
    }

    /** Focuses the hidden input and opens the numeric keyboard for custom deduction entry. */
    private fun enterCustomAmountMode() {
        isCustomAmountMode = true
        etHiddenInput.text.clear()
        tvBalance.setText(getString(R.string.balance_initial))
        etHiddenInput.post {
            etHiddenInput.requestFocus()
            showKeyboardFor(etHiddenInput)
        }
    }

    private fun clearHiddenInput() {
        etHiddenInput.text.clear()
        hideKeyboardFrom(etHiddenInput)
    }

    private fun showKeyboardFor(view: View) {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.showSoftInput(view, 0)
    }

    private fun hideKeyboardFrom(view: View) {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
