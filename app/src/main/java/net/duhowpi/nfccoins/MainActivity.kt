package net.duhowpi.nfccoins

import androidx.appcompat.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
 * Formato del bloque contador (bloque 56 = primer bloque del sector 14):
 *   Mifare Classic Value Block (16 bytes, little-endian):
 *   Bytes  0– 3: valor int32 (saldo) en little-endian
 *   Bytes  4– 7: ~valor (complemento bit a bit)
 *   Bytes  8–11: valor (copia redundante)
 *   Byte  12   : dirección del bloque (addr)
 *   Byte  13   : ~addr
 *   Byte  14   : addr
 *   Byte  15   : ~addr
 *
 * Las operaciones de incremento/decremento del chip se realizan con
 * MifareClassic.increment() / decrement() + transfer(), que son atómicas
 * a nivel de chip. La recuperación de escrituras interrumpidas se hace
 * con writeBlock() del bloque en formato Value Block.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DATA_BLOCK_OFFSET = 0
        private const val TX_BLOCK_1_OFFSET = 1
        private const val TX_BLOCK_2_OFFSET = 2
        private const val KEY_LEN = 6

        private fun hexKey(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

        // Standard NFC keys to try when formatting (factory and NDEF)
        private val STANDARD_KEYS = listOf(
            hexKey(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), // Factory (default)
            hexKey(0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5), // NDEF Key A
            hexKey(0xD3, 0xF7, 0xD3, 0xF7, 0xD3, 0xF7), // NDEF data
            hexKey(0x00, 0x00, 0x00, 0x00, 0x00, 0x00), // All zeros
            hexKey(0x4D, 0x3A, 0x99, 0xC3, 0x51, 0xDD), // NDEF Key B common
            hexKey(0x1A, 0x98, 0x2C, 0x7E, 0x45, 0x9A), // MAD key
            hexKey(0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5), // Key B common
        )

        // Maximum balance value (uint16: 2 bytes big-endian)
        private const val MAX_BALANCE = 0xFFFF

        // Default user byte in access bits (GPB position in the trailer)
        private const val DEFAULT_USER_BYTE = 0x69

        // Standard access bits: read and write with Key A on all data blocks (condition 000),
        // trailer condition 001.
        private val ACCESS_BITS = hexKey(0xFF, 0x07, 0x80, DEFAULT_USER_BYTE)
        private val ACCESS_BITS_STANDARD_CTRL = hexKey(0xFF, 0x07, 0x80)

        // Control bytes (3) for the single-recharge restricted access bits:
        // Block 0 condition 001: read+decrement allowed, write+increment blocked.
        // Blocks 1,2 condition 000 (open); Trailer condition 001 (same as standard).
        private val ACCESS_BITS_SINGLE_RECHARGE_CTRL = hexKey(0xFF, 0x06, 0x90)

        // Mifare Classic factory key (all bytes 0xFF)
        private val FACTORY_KEY = hexKey(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)

        private const val AUTO_RESET_DELAY_MS = 7000L
        private const val VIBRATE_DURATION_MS = 200L
        private val FLASH_TOKEN = Any()
        private val BEEP_TOKEN = Any()
        // Keep one ToneGenerator across Activity recreation (e.g. rotation) to avoid
        // audible click/pop when a fresh audio session is opened again.
        private var sharedToneGenerator: ToneGenerator? = null
        private val toneGeneratorLock = Any()
        // Delay (ms) to allow the window to regain IME focus after a dialog closes,
        // so that showSoftInput succeeds on the first attempt.
        private const val IME_FOCUS_DELAY_MS = 200L
    }

    private enum class PendingAction { NONE, WITHDRAW_BALANCE, ADD_BALANCE, FORMAT_CARD, RESET_CARD }

    private lateinit var rootLayout: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBalance: EditText
    private lateinit var tvMinorIcon: TextView
    private lateinit var tvCardId: TextView
    private lateinit var tvBalanceBefore: TextView
    private lateinit var tvBalanceAfter: TextView
    private lateinit var tvActualBalance: TextView
    private lateinit var layoutBeforeAfter: LinearLayout
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var etHiddenInput: BackspaceEditText
    private lateinit var layoutTransactionHistory: LinearLayout
    private lateinit var tvTx: Array<TextView>
    private lateinit var tvTxDebug: TextView

    private var nfcAdapter: NfcAdapter? = null

    private var currentBalance: Int = -1
    private var currentTag: Tag? = null
    private var pendingAction: PendingAction = PendingAction.NONE
    private var pendingAddAmount: Int = 0
    private var customDeductAmount: Int = 0
    private var isCustomAmountMode = false
    private var isAddBalanceMode = false

    /**
     * Holds the full intended card state (counter + 2 transaction blocks) that was computed
     * just before a write operation. If the write is interrupted (e.g. card removed mid-write),
     * the data here lets us retry on the next tap instead of flagging the card as tampered.
     */
    private data class PendingWrite(
        val uid: ByteArray,
        val counterBlock: ByteArray,
        val txBlock1: ByteArray,
        val txBlock2: ByteArray
    ) {
        fun matchesUid(other: ByteArray) = uid.contentEquals(other)
    }
    private var pendingWrite: PendingWrite? = null

    // Format card dialog options
    private var pendingSingleRecharge: Boolean = false
    private var pendingAgeByte: Int = DEFAULT_USER_BYTE

    private val handler = Handler(Looper.getMainLooper())
    private val autoResetRunnable = Runnable { resetToWaiting() }
    private val txDb: TransactionDatabase by lazy { TransactionDatabase(this) }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AdvancedSettingsActivity.wrapContextWithLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initLanguageIfNeeded()
        setContentView(R.layout.activity_main)

        rootLayout        = findViewById(R.id.rootLayout)
        tvStatus          = findViewById(R.id.tvStatus)
        tvBalance         = findViewById(R.id.tvBalance)
        tvMinorIcon       = findViewById(R.id.tvMinorIcon)
        tvCardId          = findViewById(R.id.tvCardId)
        tvBalanceBefore   = findViewById(R.id.tvBalanceBefore)
        tvBalanceAfter    = findViewById(R.id.tvBalanceAfter)
        tvActualBalance   = findViewById(R.id.tvActualBalance)
        layoutBeforeAfter = findViewById(R.id.layoutBeforeAfter)
        toggleGroup       = findViewById(R.id.toggleGroup)
        etHiddenInput     = findViewById(R.id.etHiddenInput)
        layoutTransactionHistory = findViewById(R.id.layoutTransactionHistory)
        tvTx = arrayOf(
            findViewById(R.id.tvTx0),
            findViewById(R.id.tvTx1),
            findViewById(R.id.tvTx2),
            findViewById(R.id.tvTx3)
        )
        tvTxDebug = findViewById(R.id.tvTxDebug)

        setupBalanceEditText()
        applyThemeColor()

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
            R.id.action_history -> {
                startActivity(Intent(this, OperationsHistoryActivity::class.java))
                true
            }
            R.id.action_management -> {
                showCardManagementDialog()
                true
            }
            R.id.action_advanced_settings -> {
                startActivity(Intent(this, AdvancedSettingsActivity::class.java))
                true
            }
            R.id.action_about -> {
                showAboutDialog()
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
        applyThemeColor()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(BEEP_TOKEN)
        if (isFinishing) {
            synchronized(toneGeneratorLock) {
                sharedToneGenerator?.release()
                sharedToneGenerator = null
            }
        }
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

        val mifare = MifareClassic.get(tag)
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        if (mifare != null && sector >= mifare.sectorCount) {
            tvStatus.text = getString(
                R.string.sector_unavailable,
                sector,
                mifare.sectorCount - 1
            )
            flashBackground(R.color.error_orange)
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
            PendingAction.WITHDRAW_BALANCE -> {
                // Do not clear pendingAction here; readAndDeduct manages state depending on
                // success/failure and whether a toggle button or custom amount is active.
                val cardKey = deriveCardKey(uid)
                when {
                    toggleGroup.checkedButtonId == R.id.btnDeduct1 -> readAndDeduct(tag, cardKey, deductUnitAmount(1), isButtonMode = true)
                    toggleGroup.checkedButtonId == R.id.btnDeduct2 -> readAndDeduct(tag, cardKey, deductUnitAmount(2), isButtonMode = true)
                    customDeductAmount > 0 -> {
                        isCustomAmountMode = false
                        clearHiddenInput()
                        readAndDeduct(tag, cardKey, customDeductAmount, isCustomAmount = true)
                    }
                    else -> setPendingAction(PendingAction.NONE)
                }
            }
            PendingAction.NONE -> {
                val cardKey = deriveCardKey(uid)
                readAndShowBalance(tag, cardKey)
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
        val uid = tag.id
        val psk = AdvancedSettingsActivity.getStaticKey(this)
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
            val sectorStart = mifare.sectorToBlock(sector)
            val blocksInSector = mifare.getBlockCountInSector(sector)
            val counterData = mifare.readBlock(sectorStart + DATA_BLOCK_OFFSET)
            val txBlock1    = mifare.readBlock(sectorStart + TX_BLOCK_1_OFFSET)
            val txBlock2    = mifare.readBlock(sectorStart + TX_BLOCK_2_OFFSET)
            val trailerData = mifare.readBlock(sectorStart + blocksInSector - 1)
            currentBalance = readValueBlock(counterData) ?: run {
                tvStatus.text = getString(R.string.error_reading, "invalid value block")
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }
            val txBlock = TransactionBlock.fromBytes(txBlock1, txBlock2)
            setBalanceDisplay(currentBalance)
            updateMinorIndicator(trailerData[KEY_LEN + 3].toInt() and 0xFF)
            layoutBeforeAfter.visibility = View.GONE
            tvActualBalance.visibility = View.GONE
            if (!txBlock.isValid(counterData, txBlock1, txBlock2, uid, psk)) {
                tvStatus.text = getString(R.string.card_tampered)
                showTransactionHistory(txBlock)
                showDebugChecksums(counterData, txBlock1, txBlock2, uid, psk)
                flashRedBackground()
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }
            tvStatus.text = getString(R.string.card_read_ok)
            showTransactionHistory(txBlock)
            showDebugChecksums(counterData, txBlock1, txBlock2, uid, psk)
            txDb.insertTransaction(TransactionDatabase.TYPE_READ, balanceBefore = currentBalance, cardUid = uid.toHex())
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
    private fun readAndDeduct(tag: Tag, cardKey: ByteArray, amount: Int, isCustomAmount: Boolean = false, isButtonMode: Boolean = false) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val uid = tag.id
        val psk = AdvancedSettingsActivity.getStaticKey(this)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(sector, cardKey)) {
                tvStatus.text = getString(R.string.auth_failed)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                return
            }
            val sectorStart = mifare.sectorToBlock(sector)
            val blocksInSector = mifare.getBlockCountInSector(sector)
            val counterData = mifare.readBlock(sectorStart + DATA_BLOCK_OFFSET)
            val txBlock1    = mifare.readBlock(sectorStart + TX_BLOCK_1_OFFSET)
            val txBlock2    = mifare.readBlock(sectorStart + TX_BLOCK_2_OFFSET)
            val trailerData = mifare.readBlock(sectorStart + blocksInSector - 1)
            val txBlock     = TransactionBlock.fromBytes(txBlock1, txBlock2)

            // Anti-tampering: verify checksum before performing any operation.
            if (!txBlock.isValid(counterData, txBlock1, txBlock2, uid, psk)) {
                val pw = pendingWrite
                if (pw != null && pw.matchesUid(uid)) {
                    // Interrupted write detected – retry the previous write and continue.
                    mifare.writeBlock(sectorStart + DATA_BLOCK_OFFSET, pw.counterBlock)
                    mifare.writeBlock(sectorStart + TX_BLOCK_1_OFFSET, pw.txBlock1)
                    mifare.writeBlock(sectorStart + TX_BLOCK_2_OFFSET, pw.txBlock2)
                    pendingWrite = null
                    tvStatus.text = getString(R.string.write_retried)
                    // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                    return
                }
                if (AdvancedSettingsActivity.isVerifyIntegrityEnabled(this)) {
                    tvStatus.text = getString(R.string.card_tampered)
                    showTransactionHistory(txBlock)
                    showDebugChecksums(counterData, txBlock1, txBlock2, uid, psk)
                    flashRedBackground()
                    playNfcErrorBeep()
                    // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                    return
                }
                // Integrity check disabled: invalid checksum is ignored and the transaction
                // proceeds. The new write will produce a fresh valid checksum.
            }
            pendingWrite = null  // Previous write (if any) was successful.

            val balance = readValueBlock(counterData) ?: run {
                tvStatus.text = getString(R.string.error_reading, "invalid value block")
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                return
            }

            if (balance < amount) {
                currentBalance = balance
                if (isCustomAmount) {
                    setBalanceDisplay(amount)
                    tvActualBalance.text = formatBalanceDisplay(balance)
                    tvActualBalance.visibility = View.VISIBLE
                } else {
                    setBalanceDisplay(balance)
                    tvActualBalance.visibility = View.GONE
                }
                layoutBeforeAfter.visibility = View.GONE
                tvStatus.text = getString(R.string.insufficient_balance)
                showTransactionHistory(txBlock)
                showDebugChecksums(counterData, txBlock1, txBlock2, uid, psk)
                flashRedBackground()
                playInsufficientBalanceBeep()
                // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                return
            }

            val newBalance = balance - amount
            // Build the Value Block bytes that the chip will hold after decrement+transfer.
            // These bytes are passed to the checksum so the transaction blocks are bound
            // to the expected new counter state, not the old one.
            val newCounterBlock = makeValueBlock(newBalance)

            val nowSecs = System.currentTimeMillis() / 1000L
            val updatedTxBlock = txBlock.addTransaction(nowSecs, TxOperation.SUBTRACT, amount)
            val (newTxBlock1, newTxBlock2) = updatedTxBlock.toBytes(newCounterBlock, uid, psk)

            // Retain the intended state in memory so an interrupted write can be retried.
            pendingWrite = PendingWrite(uid, newCounterBlock, newTxBlock1, newTxBlock2)

            // Atomically decrement the value block on the chip, then commit with transfer.
            val blockIndex = sectorStart + DATA_BLOCK_OFFSET
            mifare.decrement(blockIndex, amount)
            mifare.transfer(blockIndex)
            mifare.writeBlock(sectorStart + TX_BLOCK_1_OFFSET, newTxBlock1)
            mifare.writeBlock(sectorStart + TX_BLOCK_2_OFFSET, newTxBlock2)
            pendingWrite = null

            currentBalance = newBalance
            setBalanceDisplay(newBalance)
            updateMinorIndicator(trailerData[KEY_LEN + 3].toInt() and 0xFF)
            tvBalanceBefore.text = formatBalanceDisplay(balance)
            tvBalanceAfter.text = formatBalanceDisplay(newBalance)
            layoutBeforeAfter.visibility = View.VISIBLE
            tvActualBalance.visibility = View.GONE
            tvStatus.text = getString(R.string.deduct_ok, formatBalanceDisplay(amount))
            showTransactionHistory(updatedTxBlock)
            showDebugChecksums(newCounterBlock, newTxBlock1, newTxBlock2, uid, psk)
            txDb.insertTransaction(
                type = TransactionDatabase.TYPE_SUBTRACT,
                amount = -amount,
                balanceBefore = balance,
                balanceAfter = newBalance,
                cardUid = uid.toHex(),
                buttonValue = amount
            )
            playSuccessBeep()
            if (isButtonMode) {
                // Button remains active: keep WITHDRAW_BALANCE state for additional transactions.
                // No auto-reset scheduled; the user can tap another card immediately.
            } else {
                // Custom-amount is a one-shot transaction: clear it and schedule a full reset.
                customDeductAmount = 0
                scheduleAutoReset()
            }

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
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
    // Language initialisation
    // -------------------------------------------------------------------------

    /**
     * Called once on first launch to persist the device's system language as the app language.
     * If the system language is not one of the supported languages, English is used as fallback.
     * When the detected language differs from the default that was applied in [attachBaseContext],
     * the Activity is recreated so the correct locale takes effect.
     */
    private fun initLanguageIfNeeded() {
        val prefs = getSharedPreferences(AdvancedSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(AdvancedSettingsActivity.KEY_LANGUAGE)) {
            val systemLang = Locale.getDefault().language
            val supportedCodes = AdvancedSettingsActivity.getSupportedLanguageCodes(this)
            val detectedLang = if (systemLang in supportedCodes) {
                systemLang
            } else {
                AdvancedSettingsActivity.DEFAULT_LANGUAGE
            }
            prefs.edit().putString(AdvancedSettingsActivity.KEY_LANGUAGE, detectedLang).apply()
            // attachBaseContext defaulted to English (no pref existed yet); recreate if needed.
            if (detectedLang != AdvancedSettingsActivity.DEFAULT_LANGUAGE) {
                recreate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Theme color
    // -------------------------------------------------------------------------

    private fun applyThemeColor() {
        val color = AdvancedSettingsActivity.getThemeColor(this)
        val textOnColor = AdvancedSettingsActivity.contrastColor(color)
        val rippleTint = ColorStateList.valueOf(AdvancedSettingsActivity.rippleColor(color))

        // Action bar: surface/window background color so topbar is unified with content body
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
        val bgColor = ta.getColor(0, Color.WHITE)
        ta.recycle()
        supportActionBar?.setBackgroundDrawable(ColorDrawable(bgColor))
        @Suppress("DEPRECATION")
        window.statusBarColor = bgColor

        // Toggle buttons: opaque fill when checked, transparent when unchecked
        val bgTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(color, Color.TRANSPARENT)
        )
        // Text: contrast color (white/black) when checked, theme color when unchecked
        val textTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(textOnColor, color)
        )
        // Stroke: always the theme color
        val strokeTint = ColorStateList.valueOf(color)

        for (i in 0 until toggleGroup.childCount) {
            val child = toggleGroup.getChildAt(i) as? com.google.android.material.button.MaterialButton
                ?: continue
            child.backgroundTintList = bgTint
            child.setTextColor(textTint)
            child.strokeColor = strokeTint
            child.rippleColor = rippleTint
        }

        // Update deduct button labels to reflect decimal mode
        val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
        val btnDeduct1 = toggleGroup.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeduct1)
        val btnDeduct2 = toggleGroup.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDeduct2)
        val deductTextSizeSp = if (isDecimalMode) 20f else 26f
        btnDeduct1?.text = if (isDecimalMode) "-1.00" else getString(R.string.deduct_1_short)
        btnDeduct2?.text = if (isDecimalMode) "-2.00" else getString(R.string.deduct_2_short)
        btnDeduct1?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, deductTextSizeSp)
        btnDeduct2?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, deductTextSizeSp)
    }

    private fun applyThemeToDialog(dialog: AlertDialog) {
        val color = AdvancedSettingsActivity.getThemeColor(this)
        val rippleTint = ColorStateList.valueOf(AdvancedSettingsActivity.rippleColor(color))
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { which ->
                val btn = dialog.getButton(which) ?: return@forEach
                btn.setTextColor(color)
                (btn as? com.google.android.material.button.MaterialButton)?.rippleColor = rippleTint
            }
    }

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
    private fun initToneGenerator() {
        synchronized(toneGeneratorLock) {
            if (sharedToneGenerator != null) return
            try {
                val tg = ToneGenerator(AudioManager.STREAM_DTMF, ToneGenerator.MAX_VOLUME)
                sharedToneGenerator = tg
            } catch (_: Exception) {}
        }
    }

    // Reuses the single ToneGenerator created in initToneGenerator(). The instance is shared
    // across activity recreation (rotation), and startTone() stops any in-progress tone before
    // playing the new one, so no explicit teardown is needed between calls.
    private fun playBeep(count: Int, toneType: Int, durationMs: Int, intervalMs: Int = 100) {
        handler.removeCallbacksAndMessages(BEEP_TOKEN)
        if (count <= 0) return
        if (!AdvancedSettingsActivity.isSoundEnabled(this)) return
        initToneGenerator()
        val toneGen = synchronized(toneGeneratorLock) { sharedToneGenerator } ?: return
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
            synchronized(toneGeneratorLock) {
                sharedToneGenerator = null
            }
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
        vibrator.vibrate(VibrationEffect.createOneShot(VIBRATE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun scheduleAutoReset() {
        handler.removeCallbacks(autoResetRunnable)
        handler.postDelayed(autoResetRunnable, AUTO_RESET_DELAY_MS)
    }

    /**
     * Sets pendingAction and manages the auto-reset timer accordingly.
     * For WITHDRAW_BALANCE and ADD_BALANCE the timer is cancelled so the state persists until
     * the user taps the card, cancels via UI, or selects a different action.
     * For other non-NONE actions (FORMAT_CARD, RESET_CARD) the existing timer is cancelled
     * (via scheduleAutoReset, which always removes the callback before re-posting) and a fresh
     * 7-second countdown is started, preventing stale timers from clearing the new state.
     * For NONE the timer is simply removed (full reset is handled by resetToWaiting).
     */
    private fun setPendingAction(action: PendingAction) {
        pendingAction = action
        when (action) {
            PendingAction.NONE -> handler.removeCallbacks(autoResetRunnable)
            PendingAction.WITHDRAW_BALANCE -> handler.removeCallbacks(autoResetRunnable)
            PendingAction.ADD_BALANCE -> handler.removeCallbacks(autoResetRunnable)
            else -> scheduleAutoReset() // removes any existing callback before posting the new one
        }
    }

    private fun resetToWaiting() {
        handler.removeCallbacksAndMessages(FLASH_TOKEN)
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        tvCardId.text = getString(R.string.no_card_detected)
        currentBalance = -1
        isAddBalanceMode = false
        resetBalanceToInitial()
        layoutBeforeAfter.visibility = View.GONE
        tvActualBalance.visibility = View.GONE
        tvMinorIcon.visibility = View.GONE
        layoutTransactionHistory.visibility = View.GONE
        tvTx.forEach { it.visibility = View.GONE }
        tvStatus.text = getString(R.string.waiting_card)
        pendingAction = PendingAction.NONE
        pendingAddAmount = 0
    }

    private fun cancelAddBalance() {
        isAddBalanceMode = false
        pendingAction = PendingAction.NONE
        pendingAddAmount = 0
        clearHiddenInput()
        resetBalanceToInitial()
        tvStatus.text = getString(R.string.waiting_card)
    }

    // -------------------------------------------------------------------------
    // Acerca de
    // -------------------------------------------------------------------------

    private fun showAboutDialog() {
        val appName = getString(R.string.app_name)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title, appName))
            .setMessage(getString(R.string.about_message, appName, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.view_source) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_url)))
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) { }
            }
            .show()
        applyThemeToDialog(dialog)
    }

    // -------------------------------------------------------------------------
    // Gestión de tarjetas
    // -------------------------------------------------------------------------

    private fun showCardManagementDialog() {
        // Cancel any running auto-reset so it doesn't interfere while the user
        // is actively interacting with the dialog.
        handler.removeCallbacks(autoResetRunnable)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.card_management)
            .setItems(
                arrayOf(
                    getString(R.string.action_add_balance),
                    getString(R.string.action_format_card),
                    getString(R.string.action_reset_card)
                )
            ) { _, which ->
                when (which) {
                    0 -> enterAddBalanceModeInline()
                    1 -> showFormatOptionsDialog()
                    2 -> {
                        cancelAddBalance()
                        toggleGroup.clearChecked()
                        setPendingAction(PendingAction.RESET_CARD)
                        tvStatus.text = getString(R.string.tap_card_to_reset)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAction = PendingAction.NONE
            }
            .show()
        applyThemeToDialog(dialog)
    }

    /**
     * Shows a dialog with format options before formatting a card:
     * - "Recarga única" checkbox: when enabled, restricted access bits are written to the sector
     *   trailer during format (block 0 becomes increment-blocked). On the first balance add the
     *   app temporarily unlocks, adds balance, then re-locks.
     * - "Límite de edad" number input: accepts an age (1–99) or birth year (1900–currentYear).
     *   Stores (birthYear − 1900) in the GPB user byte of the sector trailer access bits.
     *   Defaults to DEFAULT_USER_BYTE when empty or invalid.
     */
    private fun showFormatOptionsDialog() {
        val pad = (24 * resources.displayMetrics.density).toInt()

        val checkboxSingleRecharge = CheckBox(this).apply {
            text = getString(R.string.format_recarga_unica)
            isChecked = false
        }

        val labelAge = TextView(this).apply {
            text = getString(R.string.format_limite_edad)
            setPadding(0, pad / 2, 0, 0)
        }

        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val editAge = EditText(this).apply {
            hint = getString(R.string.format_limite_edad_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : TextWatcher {
                private var updating = false
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (updating) return
                    val value = s?.toString()?.toIntOrNull() ?: return
                    if (value > currentYear) {
                        updating = true
                        s.replace(0, s.length, currentYear.toString())
                        updating = false
                    }
                }
            })
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(checkboxSingleRecharge)
            addView(labelAge)
            addView(editAge)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.action_format_card)
            .setView(layout)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                pendingSingleRecharge = checkboxSingleRecharge.isChecked
                pendingAgeByte = parseAgeByte(editAge.text.toString())
                cancelAddBalance()
                toggleGroup.clearChecked()
                setPendingAction(PendingAction.FORMAT_CARD)
                tvStatus.text = getString(R.string.tap_card_to_format)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        applyThemeToDialog(dialog)
    }

    /**
     * Interprets [input] as an age (1–99, resolved to currentYear − age) or birth year
     * (1900–currentYear) and returns the value to store in the GPB user byte (birthYear − 1900).
     * Returns [DEFAULT_USER_BYTE] when the input is empty or out of range.
     */
    private fun parseAgeByte(input: String): Int {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return DEFAULT_USER_BYTE
        val value = trimmed.toIntOrNull() ?: return DEFAULT_USER_BYTE
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val birthYear = when {
            value in 1..99 -> currentYear - value
            value in 1900..currentYear -> value
            else -> return DEFAULT_USER_BYTE
        }
        return (birthYear - 1900).coerceIn(0, 255)
    }

    /**
     * Builds a 16-byte Mifare Classic sector trailer block:
     *   [Key A (6 bytes)] [Access bits (4 bytes)] [Key B (6 bytes)]
     *
     * When [standard] is true, uses the standard access bits (FF 07 80) which allow
     * increment and write on all data blocks. When false, uses the single-recharge
     * restricted bits (FF 06 90) which block increment and write on block 0.
     * The [userByte] (GPB, General Purpose Byte) is placed at position 3 of the access bits.
     */
    private fun buildSectorTrailer(key: ByteArray, standard: Boolean, userByte: Int): ByteArray {
        val ctrlBytes = if (standard) ACCESS_BITS_STANDARD_CTRL
                        else          ACCESS_BITS_SINGLE_RECHARGE_CTRL
        val trailer = ByteArray(MifareClassic.BLOCK_SIZE)
        System.arraycopy(key, 0, trailer, 0, KEY_LEN)
        System.arraycopy(ctrlBytes, 0, trailer, KEY_LEN, ctrlBytes.size)
        trailer[KEY_LEN + ctrlBytes.size] = userByte.toByte()
        System.arraycopy(key, 0, trailer, KEY_LEN + ctrlBytes.size + 1, KEY_LEN)
        return trailer
    }

    /**
     * Enters inline add-balance mode: disables the toggle buttons, shows "+0" in the balance
     * display, opens the keyboard on the hidden input, and immediately sets the pending amount
     * as the user types (no dialog or confirm step needed).
     */
    private fun enterAddBalanceModeInline() {
        handler.removeCallbacks(autoResetRunnable)
        isAddBalanceMode = true
        isCustomAmountMode = false
        customDeductAmount = 0
        pendingAddAmount = 0
        currentBalance = -1
        toggleGroup.clearChecked()
        etHiddenInput.text?.clear()
        val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
        if (isDecimalMode) {
            etHiddenInput.keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
            etHiddenInput.setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        } else {
            etHiddenInput.keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789")
            etHiddenInput.setRawInputType(InputType.TYPE_CLASS_NUMBER)
        }
        tvBalance.setText("+" + formatBalanceDisplay(0))
        layoutBeforeAfter.visibility = View.GONE
        tvActualBalance.visibility = View.GONE
        layoutTransactionHistory.visibility = View.GONE
        tvTx.forEach { it.visibility = View.GONE }
        setPendingAction(PendingAction.NONE)
        tvStatus.text = getString(R.string.tap_card_to_add)
        etHiddenInput.postDelayed({
            etHiddenInput.requestFocus()
            showKeyboardFor(etHiddenInput)
        }, IME_FOCUS_DELAY_MS)
    }

    /**
     * Aplica el saldo pendiente a la tarjeta. Solo intenta con la clave derivada del UID;
     * si falla la autenticación, la tarjeta no está formateada y se muestra un error.
     */
    private fun addBalanceToCard(tag: Tag) {
        hideKeyboardFrom(etHiddenInput)
        isAddBalanceMode = false
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val uid = tag.id
        val cardKey = deriveCardKey(uid)
        val psk = AdvancedSettingsActivity.getStaticKey(this)
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
            val sectorStart = mifare.sectorToBlock(sector)
            val counterData = mifare.readBlock(sectorStart + DATA_BLOCK_OFFSET)
            val txBlock1    = mifare.readBlock(sectorStart + TX_BLOCK_1_OFFSET)
            val txBlock2    = mifare.readBlock(sectorStart + TX_BLOCK_2_OFFSET)
            val txBlock     = TransactionBlock.fromBytes(txBlock1, txBlock2)

            // Anti-tampering: verify checksum before performing any operation.
            if (!txBlock.isValid(counterData, txBlock1, txBlock2, uid, psk)) {
                val pw = pendingWrite
                if (pw != null && pw.matchesUid(uid)) {
                    // Interrupted write detected – retry the previous write and continue.
                    mifare.writeBlock(sectorStart + DATA_BLOCK_OFFSET, pw.counterBlock)
                    mifare.writeBlock(sectorStart + TX_BLOCK_1_OFFSET, pw.txBlock1)
                    mifare.writeBlock(sectorStart + TX_BLOCK_2_OFFSET, pw.txBlock2)
                    pendingWrite = null
                    tvStatus.text = getString(R.string.write_retried)
                    scheduleAutoReset()
                    return
                }
                if (AdvancedSettingsActivity.isVerifyIntegrityEnabled(this)) {
                    tvStatus.text = getString(R.string.card_tampered)
                    showTransactionHistory(txBlock)
                    showDebugChecksums(counterData, txBlock1, txBlock2, uid, psk)
                    flashRedBackground()
                    playNfcErrorBeep()
                    scheduleAutoReset()
                    return
                }
                // Integrity check disabled: invalid checksum is ignored and the transaction
                // proceeds. The new write will produce a fresh valid checksum.
            }
            pendingWrite = null

            val oldBalance = readValueBlock(counterData) ?: run {
                tvStatus.text = getString(R.string.error_reading, "invalid value block")
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                return
            }
            val newBalance = oldBalance + pendingAddAmount
            if (newBalance > MAX_BALANCE) {
                Toast.makeText(this, getString(R.string.balance_too_high), Toast.LENGTH_SHORT).show()
                pendingAddAmount = 0
                scheduleAutoReset()
                return
            }

            // Build the Value Block bytes that the chip will hold after increment+transfer.
            val newCounterBlock = makeValueBlock(newBalance)

            val nowSecs = System.currentTimeMillis() / 1000L
            val updatedTxBlock = txBlock.addTransaction(nowSecs, TxOperation.ADD, pendingAddAmount)
            val (newTxBlock1, newTxBlock2) = updatedTxBlock.toBytes(newCounterBlock, uid, psk)

            // Retain the intended state in memory so an interrupted write can be retried.
            pendingWrite = PendingWrite(uid, newCounterBlock, newTxBlock1, newTxBlock2)

            // Detect single-recharge mode by reading the sector trailer directly from the card.
            // A single-recharge card has ACCESS_BITS_SINGLE_RECHARGE_CTRL in bytes 6-8 of the
            // trailer. The ageByte (GPB) lives at byte 9. No per-device SharedPreferences are
            // used so that the recharge can be performed on any device/instance.
            val blocksInSector = mifare.getBlockCountInSector(sector)
            val trailerIdx = sectorStart + blocksInSector - 1
            val trailerData = mifare.readBlock(trailerIdx)
            val isSingleRecharge = trailerData[KEY_LEN] == ACCESS_BITS_SINGLE_RECHARGE_CTRL[0] &&
                                   trailerData[KEY_LEN + 1] == ACCESS_BITS_SINGLE_RECHARGE_CTRL[1] &&
                                   trailerData[KEY_LEN + 2] == ACCESS_BITS_SINGLE_RECHARGE_CTRL[2]
            val ageByte = trailerData[KEY_LEN + 3].toInt() and 0xFF
            val isFirstAdd = txBlock.transactions.isEmpty()

            if (isSingleRecharge && !isFirstAdd) {
                // Card was already charged once; reject any further balance additions.
                tvStatus.text = getString(R.string.single_recharge_already_used)
                flashBackground(R.color.error_orange)
                playNfcErrorBeep()
                scheduleAutoReset()
                pendingWrite = null
                return
            }

            if (isSingleRecharge) {
                // First and only allowed recharge: temporarily unlock block 0, increment, then
                // re-lock with the original restricted bits and preserved ageByte.
                val openTrailer = buildSectorTrailer(cardKey, standard = true, userByte = ageByte)
                mifare.writeBlock(trailerIdx, openTrailer)
            }

            // Atomically increment the value block on the chip, then commit with transfer.
            val blockIndex = sectorStart + DATA_BLOCK_OFFSET
            mifare.increment(blockIndex, pendingAddAmount)
            mifare.transfer(blockIndex)
            mifare.writeBlock(sectorStart + TX_BLOCK_1_OFFSET, newTxBlock1)
            mifare.writeBlock(sectorStart + TX_BLOCK_2_OFFSET, newTxBlock2)
            pendingWrite = null

            if (isSingleRecharge) {
                // Re-lock: restore restricted access bits so further increments are blocked.
                val restrictedTrailer = buildSectorTrailer(cardKey, standard = false, userByte = ageByte)
                mifare.writeBlock(trailerIdx, restrictedTrailer)
            }

            currentBalance = newBalance
            setBalanceDisplay(newBalance)
            updateMinorIndicator(ageByte)
            tvBalanceBefore.text = formatBalanceDisplay(oldBalance)
            tvBalanceAfter.text = formatBalanceDisplay(newBalance)
            layoutBeforeAfter.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.balance_added_ok, formatBalanceDisplay(pendingAddAmount))
            showTransactionHistory(updatedTxBlock)
            showDebugChecksums(newCounterBlock, newTxBlock1, newTxBlock2, uid, psk)
            txDb.insertTransaction(
                type = TransactionDatabase.TYPE_ADD,
                amount = pendingAddAmount,
                balanceBefore = oldBalance,
                balanceAfter = newBalance,
                cardUid = uid.toHex()
            )
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
     * Attempts to format the configured sector of the card.
     * First tries the derived key: if already formatted, resets the balance to zero.
     * Otherwise searches for a standard key and writes the sector with the derived key.
     */
    private fun formatCard(tag: Tag) {
        val sector = AdvancedSettingsActivity.getTargetSector(this)
        val uid = tag.id
        val derivedKey = deriveCardKey(uid)
        val psk = AdvancedSettingsActivity.getStaticKey(this)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            flashBackground(R.color.error_orange)
            playNfcErrorBeep()
            scheduleAutoReset()
            return
        }

        try {
            mifare.connect()

            // If already formatted with the derived key, reset balance to 0 and transaction history
            if (mifare.authenticateSectorWithKeyA(sector, derivedKey)) {
                val sectorStart = mifare.sectorToBlock(sector)
                val blocksInSector = mifare.getBlockCountInSector(sector)
                val trailerIdx = sectorStart + blocksInSector - 1

                // Always write open access bits first so block 0 is writable even if the card
                // was previously formatted with single-recharge restricted bits.
                mifare.writeBlock(trailerIdx, buildSectorTrailer(derivedKey, standard = true, userByte = pendingAgeByte))

                val counterData = mifare.readBlock(sectorStart + DATA_BLOCK_OFFSET)
                val oldBalance = readValueBlock(counterData) ?: 0

                val zeroValueBlock = makeValueBlock(0)
                val nowSecs = System.currentTimeMillis() / 1000L
                val freshTxBlock = TransactionBlock(nowSecs)
                val (txB1, txB2) = freshTxBlock.toBytes(zeroValueBlock, uid, psk)

                mifare.writeBlock(sectorStart + DATA_BLOCK_OFFSET, zeroValueBlock)
                mifare.writeBlock(sectorStart + TX_BLOCK_1_OFFSET, txB1)
                mifare.writeBlock(sectorStart + TX_BLOCK_2_OFFSET, txB2)

                // Write the target access bits (restricted or standard)
                mifare.writeBlock(trailerIdx, buildSectorTrailer(derivedKey, standard = !pendingSingleRecharge, userByte = pendingAgeByte))

                currentBalance = 0
                setBalanceDisplay(0)
                tvBalanceBefore.text = formatBalanceDisplay(oldBalance)
                tvBalanceAfter.text = formatBalanceDisplay(0)
                layoutBeforeAfter.visibility = View.VISIBLE
                showTransactionHistory(freshTxBlock)
                showDebugChecksums(zeroValueBlock, txB1, txB2, uid, psk)
                tvStatus.text = getString(R.string.format_reset_success)
                txDb.insertTransaction(TransactionDatabase.TYPE_FORMAT, cardUid = uid.toHex())
                flashBackground(R.color.success_purple_dark)
                playSuccessBeep()
                scheduleAutoReset()
                return
            }

            // Search for a standard key that grants access to the sector
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

            // Initialize counter as a Value Block (value=0) and transaction blocks
            val sectorStart = mifare.sectorToBlock(sector)
            val blocksInSector = mifare.getBlockCountInSector(sector)
            val zeroValueBlock = makeValueBlock(0)
            val nowSecs = System.currentTimeMillis() / 1000L
            val freshTxBlock = TransactionBlock(nowSecs)
            val (txB1, txB2) = freshTxBlock.toBytes(zeroValueBlock, uid, psk)

            mifare.writeBlock(sectorStart + DATA_BLOCK_OFFSET, zeroValueBlock)
            mifare.writeBlock(sectorStart + TX_BLOCK_1_OFFSET, txB1)
            mifare.writeBlock(sectorStart + TX_BLOCK_2_OFFSET, txB2)

            // Write sector trailer with the derived key and the chosen access bits.
            // Single-recharge cards get restricted bits immediately so block 0 is locked.
            // [Key A (6 bytes)] [Access bits (4 bytes)] [Key B (6 bytes)]
            mifare.writeBlock(
                sectorStart + blocksInSector - 1,
                buildSectorTrailer(derivedKey, standard = !pendingSingleRecharge, userByte = pendingAgeByte)
            )

            val foundKeyHex = foundKey.toHex()
            val newKeyHex = derivedKey.toHex()
            val keyType = if (usedKeyA) "A" else "B"

            currentBalance = 0
            setBalanceDisplay(0)
            layoutBeforeAfter.visibility = View.GONE
            showTransactionHistory(freshTxBlock)
            showDebugChecksums(zeroValueBlock, txB1, txB2, uid, psk)
            tvStatus.text = getString(R.string.format_success)
            txDb.insertTransaction(TransactionDatabase.TYPE_FORMAT, cardUid = uid.toHex())
            flashBackground(R.color.success_purple_dark)
            playSuccessBeep()
            scheduleAutoReset()

            if (AdvancedSettingsActivity.isDebugEnabled(this)) {
                val debugDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.format_success)
                    .setMessage(getString(R.string.format_success_message, keyType, foundKeyHex, newKeyHex))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                applyThemeToDialog(debugDialog)
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
            txDb.insertTransaction(TransactionDatabase.TYPE_RESET, cardUid = uid.toHex())
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
    // Decimal mode helpers
    // -------------------------------------------------------------------------

    /**
     * Formats an integer stored value for display. In decimal mode the value is treated
     * as cents (e.g. 125 → "1.25"); outside decimal mode it is returned as-is ("125").
     */
    private fun formatBalanceDisplay(value: Int): String {
        return if (AdvancedSettingsActivity.isDecimalModeEnabled(this)) {
            "%d.%02d".format(value / 100, Math.abs(value % 100))
        } else {
            value.toString()
        }
    }

    /**
     * Returns the base deduct amount for the fixed-amount buttons in the current mode.
     * In decimal mode the unit is 100 (= 1.00), otherwise it is [factor] itself.
     */
    private fun deductUnitAmount(factor: Int = 1): Int =
        if (AdvancedSettingsActivity.isDecimalModeEnabled(this)) factor * 100 else factor

    /**
     * Parses a raw string from the decimal input field into the stored integer value.
     *
     * Supports two input styles:
     * - Explicit decimal ("1.25" or "1,25") → 125 stored
     * - TPV push-from-right (no separator, "125") → 125 stored (= 1.25 display)
     *
     * Always normalise commas to dots before calling this function.
     */
    private fun parseDecimalInput(normalized: String): Int {
        return if ('.' in normalized) {
            val parts = normalized.split('.')
            val intPart = parts[0].toIntOrNull() ?: 0
            val fracStr = (parts.getOrNull(1) ?: "").take(2).padEnd(2, '0')
            val fracPart = fracStr.toIntOrNull() ?: 0
            intPart * 100 + fracPart
        } else {
            normalized.toIntOrNull() ?: 0
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
        isAddBalanceMode = false
        tvBalance.setText(text)
        tvBalance.inputType = InputType.TYPE_NULL
        tvBalance.isFocusable = false
        tvBalance.isFocusableInTouchMode = false
    }

    /**
     * Convenience overload that formats [value] according to the current decimal mode
     * before delegating to [setBalanceText].
     */
    private fun setBalanceDisplay(value: Int) = setBalanceText(formatBalanceDisplay(value))

    /**
     * Shows or hides the minor-age indicator icon next to the balance display.
     *
     * [ageByte] is the GPB byte stored in the sector trailer, where the birth year is
     * encoded as `birthYear - 1900`. The icon is shown when the computed age is below the
     * configured legal adult age and the byte represents a plausible birth year.
     */
    private fun updateMinorIndicator(ageByte: Int) {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val birthYear = 1900 + ageByte
        val age = currentYear - birthYear
        val legalAge = AdvancedSettingsActivity.getLegalAge(this)
        tvMinorIcon.visibility = if (age in 0 until legalAge) View.VISIBLE else View.GONE
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
                isAddBalanceMode -> {
                    // Keyboard was dismissed (e.g. via Back) without losing focus; reopen it.
                    etHiddenInput.post { showKeyboardFor(etHiddenInput) }
                }
                currentBalance >= 0 -> {
                    // Tapping the displayed balance cancels the auto-reset and resets immediately.
                    handler.removeCallbacks(autoResetRunnable)
                    resetToWaiting()
                }
                pendingAction == PendingAction.ADD_BALANCE -> {
                    // Tapping the balance display while waiting to add balance cancels the operation.
                    cancelAddBalance()
                }
                pendingAction == PendingAction.WITHDRAW_BALANCE && customDeductAmount > 0 -> {
                    // Tapping the balance display while waiting to withdraw with a custom amount
                    // cancels the operation and returns to idle.
                    setPendingAction(PendingAction.NONE)
                    resetBalanceToInitial()
                    tvStatus.text = getString(R.string.waiting_card)
                }
                isCustomAmountMode -> {
                    // Keyboard was dismissed (e.g. via Back) without etHiddenInput losing focus,
                    // so isCustomAmountMode stayed true but the IME is hidden. Reopen it.
                    etHiddenInput.post { showKeyboardFor(etHiddenInput) }
                }
                currentBalance == -1 && pendingAction == PendingAction.NONE -> {
                    enterCustomAmountMode()
                }
            }
        }

        // Mirror hidden-input digits into tvBalance while typing.
        etHiddenInput.onBackspaceWhenEmpty = {
            if (isAddBalanceMode) cancelAddBalance()
        }
        etHiddenInput.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                var raw = s?.toString() ?: ""

                val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this@MainActivity)
                if (isDecimalMode) {
                    // Normalize: replace comma separator with dot and continue processing
                    if (',' in raw) {
                        val normalized = raw.replace(',', '.')
                        isUpdating = true
                        s?.replace(0, s.length, normalized)
                        isUpdating = false
                        raw = normalized
                    }
                    // Allow at most one decimal separator
                    val dotCount = raw.count { it == '.' }
                    if (dotCount > 1) {
                        val firstDot = raw.indexOf('.')
                        val fixedStr = raw.substring(0, firstDot + 1) +
                            raw.substring(firstDot + 1).replace(".", "")
                        isUpdating = true
                        s?.replace(0, s.length, fixedStr)
                        isUpdating = false
                        return
                    }
                    // Limit to 2 decimal places
                    val dotIdx = raw.indexOf('.')
                    if (dotIdx >= 0 && raw.length - dotIdx - 1 > 2) {
                        isUpdating = true
                        s?.replace(0, s.length, raw.substring(0, dotIdx + 3))
                        isUpdating = false
                        return
                    }
                    // Compute stored integer value from input
                    val storedValue = parseDecimalInput(raw)
                    val cappedValue = storedValue.coerceIn(0, MAX_BALANCE)
                    // Cap if MAX_BALANCE exceeded
                    if (cappedValue != storedValue) {
                        isUpdating = true
                        s?.replace(0, s.length, formatBalanceDisplay(cappedValue))
                        isUpdating = false
                        return
                    }
                    if (isAddBalanceMode) {
                        tvBalance.setText("+" + formatBalanceDisplay(cappedValue))
                        pendingAddAmount = cappedValue
                        setPendingAction(if (cappedValue > 0) PendingAction.ADD_BALANCE else PendingAction.NONE)
                        tvStatus.text = getString(R.string.tap_card_to_add)
                        return
                    }
                    if (!isCustomAmountMode) return
                    if (raw.isEmpty()) {
                        tvBalance.setText(getString(R.string.balance_initial))
                    } else {
                        tvBalance.setText(formatBalanceDisplay(cappedValue))
                    }
                    customDeductAmount = cappedValue
                    if (customDeductAmount > 0) {
                        toggleGroup.clearChecked()
                        setPendingAction(PendingAction.WITHDRAW_BALANCE)
                        tvStatus.text = getString(R.string.tap_card_to_deduct)
                    } else {
                        setPendingAction(PendingAction.NONE)
                        tvStatus.text = getString(R.string.waiting_card)
                    }
                    return
                }

                // Non-decimal mode (original behaviour)
                val value = raw.toIntOrNull() ?: 0
                if (isAddBalanceMode) {
                    // Normalize input: cap at MAX_BALANCE and strip leading zeros
                    val normalizedValue = when {
                        value > MAX_BALANCE -> {
                            isUpdating = true
                            s?.replace(0, s.length, MAX_BALANCE.toString())
                            isUpdating = false
                            MAX_BALANCE
                        }
                        raw != value.toString() && value > 0 -> {
                            isUpdating = true
                            s?.replace(0, s.length, value.toString())
                            isUpdating = false
                            value
                        }
                        else -> value
                    }
                    tvBalance.setText("+$normalizedValue")
                    pendingAddAmount = normalizedValue
                    setPendingAction(if (normalizedValue > 0) PendingAction.ADD_BALANCE else PendingAction.NONE)
                    tvStatus.text = getString(R.string.tap_card_to_add)
                    return
                }
                if (!isCustomAmountMode) return
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
                if (customDeductAmount > 0) {
                    toggleGroup.clearChecked()
                    setPendingAction(PendingAction.WITHDRAW_BALANCE)
                    tvStatus.text = getString(R.string.tap_card_to_deduct)
                } else {
                    setPendingAction(PendingAction.NONE)
                    tvStatus.text = getString(R.string.waiting_card)
                }
            }
        })

        etHiddenInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                if (isAddBalanceMode) {
                    // In add balance mode: loss of focus (e.g. Back key) doesn't cancel the mode.
                    // The amount and pending action remain as typed.
                } else if (isCustomAmountMode) {
                    isCustomAmountMode = false
                    val raw = etHiddenInput.text.toString()
                    val amount = if (AdvancedSettingsActivity.isDecimalModeEnabled(this)) {
                        parseDecimalInput(raw.replace(',', '.'))
                    } else {
                        raw.toIntOrNull() ?: 0
                    }
                    clearHiddenInput()
                    if (amount > 0) {
                        // Keep the typed value visible and transition to withdraw state
                        customDeductAmount = amount
                        tvBalance.setText(formatBalanceDisplay(amount))
                        setPendingAction(PendingAction.WITHDRAW_BALANCE)
                        tvStatus.text = getString(R.string.tap_card_to_deduct)
                    } else {
                        resetBalanceToInitial()
                    }
                }
            }
        }

        // Selecting a fixed-deduction toggle button enters WITHDRAW_BALANCE; deselecting all
        // buttons (with no custom amount pending) returns to NONE.
        toggleGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                if (isAddBalanceMode || pendingAction == PendingAction.ADD_BALANCE) {
                    cancelAddBalance()
                } else if (customDeductAmount > 0) {
                    resetBalanceToInitial()
                }
                setPendingAction(PendingAction.WITHDRAW_BALANCE)
                tvStatus.text = getString(R.string.tap_card_to_deduct)
            } else {
                // Defer the check: when switching between buttons the unchecked event fires
                // before the newly-selected button's checked event, so we wait until both
                // events have been delivered before deciding whether to revert to NONE.
                handler.post {
                    if (toggleGroup.checkedButtonId == View.NO_ID
                        && customDeductAmount == 0
                        && pendingAction == PendingAction.WITHDRAW_BALANCE) {
                        if (currentBalance >= 0) {
                            resetToWaiting()
                        } else {
                            setPendingAction(PendingAction.NONE)
                            tvStatus.text = getString(R.string.waiting_card)
                        }
                    }
                }
            }
        }
    }

    /** Focuses the hidden input and opens the numeric keyboard for custom deduction entry. */
    private fun enterCustomAmountMode() {
        isCustomAmountMode = true
        etHiddenInput.text?.clear()
        tvBalance.setText(getString(R.string.balance_initial))
        if (AdvancedSettingsActivity.isDecimalModeEnabled(this)) {
            etHiddenInput.keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789.,")
            etHiddenInput.setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        } else {
            etHiddenInput.keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789")
            etHiddenInput.setRawInputType(InputType.TYPE_CLASS_NUMBER)
        }
        etHiddenInput.post {
            etHiddenInput.requestFocus()
            showKeyboardFor(etHiddenInput)
        }
    }

    private fun clearHiddenInput() {
        etHiddenInput.text?.clear()
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

    // -------------------------------------------------------------------------
    // Value Block helpers (Mifare Classic 16-byte format, little-endian)
    // -------------------------------------------------------------------------

    /** Bitwise inverse of a single byte (Kotlin's Byte.inv() is not available). */
    private fun Byte.inv8(): Byte = (this.toInt() xor 0xFF).toByte()

    /**
     * Creates a 16-byte Mifare Classic Value Block for [value].
     *
     * Layout: value(4B LE) | ~value(4B) | value(4B) | addr ~addr addr ~addr
     *
     * The address field is set to [DATA_BLOCK_OFFSET] (the block's position
     * within its sector), which is the conventional choice.
     */
    private fun makeValueBlock(value: Int): ByteArray {
        val block = ByteArray(MifareClassic.BLOCK_SIZE)
        val b0 = (value and 0xFF).toByte()
        val b1 = ((value shr 8) and 0xFF).toByte()
        val b2 = ((value shr 16) and 0xFF).toByte()
        val b3 = ((value shr 24) and 0xFF).toByte()
        // Bytes 0–3: value LE
        block[0] = b0; block[1] = b1; block[2] = b2; block[3] = b3
        // Bytes 4–7: ~value LE
        block[4] = b0.inv8(); block[5] = b1.inv8(); block[6] = b2.inv8(); block[7] = b3.inv8()
        // Bytes 8–11: value LE (redundant copy)
        block[8] = b0; block[9] = b1; block[10] = b2; block[11] = b3
        // Bytes 12–15: addr ~addr addr ~addr
        val addr = DATA_BLOCK_OFFSET.toByte()
        block[12] = addr; block[13] = addr.inv8(); block[14] = addr; block[15] = addr.inv8()
        return block
    }

    /**
     * Parses a Mifare Classic Value Block and returns its integer value,
     * or `null` when the block data does not have valid triple-redundancy.
     */
    private fun readValueBlock(data: ByteArray): Int? {
        if (data.size != MifareClassic.BLOCK_SIZE) return null
        // Check that bytes 0–3 == bytes 8–11 (redundant copy)
        if ((0..3).any { data[it] != data[it + 8] }) return null
        // Check that bytes 4–7 are bitwise inverse of bytes 0–3
        if ((0..3).any { data[it] != data[it + 4].inv8() }) return null
        // Parse little-endian int32
        return (data[0].toInt() and 0xFF) or
               ((data[1].toInt() and 0xFF) shl 8) or
               ((data[2].toInt() and 0xFF) shl 16) or
               ((data[3].toInt() and 0xFF) shl 24)
    }

    /**
     * Renders up to [TransactionBlock.MAX_TRANSACTIONS] transaction entries in the
     * history section at the bottom of the screen.
     *
     * Each entry timestamp is reconstructed as [initTimestamp] + [secondsOffset] and
     * shown as HH:mm:ss. When the transaction occurred on a day other than today,
     * the date (dd/MM) is prepended.
     */
    private fun showTransactionHistory(txBlock: TransactionBlock) {
        tvTxDebug.visibility = View.GONE
        if (txBlock.initTimestamp <= 0L && txBlock.transactions.isEmpty()) {
            layoutTransactionHistory.visibility = View.GONE
            return
        }
        layoutTransactionHistory.visibility = View.VISIBLE
        val entries = txBlock.transactions

        val timeFmt  = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val dateFmt  = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val isDecimalMode = AdvancedSettingsActivity.isDecimalModeEnabled(this)
        for (i in tvTx.indices) {
            val tv = tvTx[i]
            val entry = entries.getOrNull(i)
            if (entry == null) {
                tv.visibility = View.GONE
            } else {
                val txEpochMs = (txBlock.initTimestamp + entry.secondsOffset) * 1000L
                val fmt = if (txEpochMs >= todayStart) timeFmt else dateFmt
                val timeLabel = fmt.format(Date(txEpochMs))
                val amtLabel = if (isDecimalMode) {
                    when (entry.operation) {
                        TxOperation.ADD      -> "+${formatBalanceDisplay(entry.amount)}"
                        TxOperation.SUBTRACT -> "-${formatBalanceDisplay(entry.amount)}"
                    }
                } else {
                    when (entry.operation) {
                        TxOperation.ADD      -> getString(R.string.tx_add, entry.amount)
                        TxOperation.SUBTRACT -> getString(R.string.tx_subtract, entry.amount)
                    }
                }
                tv.text = getString(R.string.tx_entry_format, timeLabel, amtLabel)
                tv.visibility = View.VISIBLE
            }
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }

    /**
     * When debug mode is enabled, computes both the stored and expected checksums from the
     * given raw card blocks and displays them in [tvTxDebug] at the bottom of the
     * transaction history section.
     */
    private fun showDebugChecksums(
        counterData: ByteArray,
        txBlock1: ByteArray,
        txBlock2: ByteArray,
        uid: ByteArray,
        psk: String
    ) {
        if (!AdvancedSettingsActivity.isDebugEnabled(this)) {
            tvTxDebug.visibility = View.GONE
            return
        }
        val (stored, computed) = TransactionBlock.extractChecksums(counterData, txBlock1, txBlock2, uid, psk)
        tvTxDebug.text = getString(R.string.tx_debug_checksum, stored.toHex(), computed.toHex())
        layoutTransactionHistory.visibility = View.VISIBLE
        tvTxDebug.visibility = View.VISIBLE
    }
}
