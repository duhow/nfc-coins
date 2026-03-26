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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
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
import androidx.core.view.WindowCompat
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.provider.Settings
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * NFC POS – Monedero NFC
 *
 * Point-of-sale activity that reads, writes, and manages NFC coin cards.
 * Card-technology-specific operations are delegated to [BaseCoinCard]
 * subclasses (currently [MifareClassicCoinCard]; NTAG support planned).
 *
 * Los botones de descuento actúan como toggles: si ninguno está activo,
 * acercar la tarjeta muestra el saldo; si uno está activo, descuenta automáticamente.
 * Incluye gestión de tarjetas: añadir saldo y formatear con claves estándar.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val AUTO_RESET_DELAY_MS = 7000L
        private const val VIBRATE_DURATION_MS = 200L
        private val FLASH_TOKEN = Any()
        private val BEEP_TOKEN = Any()
        private var sharedToneGenerator: ToneGenerator? = null
        private val toneGeneratorLock = Any()
        private const val IME_FOCUS_DELAY_MS = 200L
        private val GIT_DESCRIBE_COMMIT_REGEX = Regex("-\\d+-g([0-9a-fA-F]+)$")
        private val NFC_DISABLED_TAG = Any()
        private const val TX_CHECKSUM_SIZE = 4
    }

    private enum class PendingAction { NONE, WITHDRAW_BALANCE, ADD_BALANCE, FORMAT_CARD, RESET_CARD }

    private lateinit var rootLayout: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBalance: EditText
    private lateinit var tvMinorIcon: TextView
    private lateinit var tvCardId: TextView
    private lateinit var tvCoinsLabel: TextView
    private lateinit var tvBalanceBefore: TextView
    private lateinit var tvBalanceAfter: TextView
    private lateinit var tvActualBalance: TextView
    private lateinit var layoutBeforeAfter: LinearLayout
    private lateinit var layoutCustomButtons: LinearLayout
    private lateinit var etHiddenInput: BackspaceEditText

    private var selectedButtonIndex: Int = -1
    private var customButtonList: List<CustomButton> = emptyList()
    private var customButtonViews: List<MaterialButton> = emptyList()
    private lateinit var layoutTransactionHistory: LinearLayout
    private lateinit var tvTx: Array<TextView>
    private lateinit var tvTxDebug: TextView

    private var nfcAdapter: NfcAdapter? = null

    private var currentBalance: Int = -1
    private var pendingAction: PendingAction = PendingAction.NONE
    private var pendingAddAmount: Int = 0
    private var customDeductAmount: Int = 0
    private var isCustomAmountMode = false
    private var isAddBalanceMode = false

    private var pendingWrite: BaseCoinCard.PendingWriteData? = null

    // Format card dialog options
    private var pendingSingleRecharge: Boolean = false
    private var pendingUserBirthYear: Int = MifareClassicHelper.toUserBirthYear(MifareClassicHelper.DEFAULT_USER_BYTE)

    private val handler = Handler(Looper.getMainLooper())
    private val autoResetRunnable = Runnable { resetToWaiting() }
    private val buttonModeIdleRunnable = Runnable { resetButtonModeState() }
    private val txDb: TransactionDatabase by lazy { TransactionDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        migrateLanguagePrefIfNeeded()
        setContentView(R.layout.activity_main)

        rootLayout        = findViewById(R.id.rootLayout)
        tvStatus          = findViewById(R.id.tvStatus)
        tvBalance         = findViewById(R.id.tvBalance)
        tvMinorIcon       = findViewById(R.id.tvMinorIcon)
        tvCardId          = findViewById(R.id.tvCardId)
        tvCoinsLabel      = findViewById(R.id.tvCoinsLabel)
        tvBalanceBefore   = findViewById(R.id.tvBalanceBefore)
        tvBalanceAfter    = findViewById(R.id.tvBalanceAfter)
        tvActualBalance   = findViewById(R.id.tvActualBalance)
        layoutBeforeAfter = findViewById(R.id.layoutBeforeAfter)
        layoutCustomButtons = findViewById(R.id.layoutCustomButtons)
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
        rebuildCustomButtons()
        applyThemeColor()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            layoutCustomButtons.isEnabled = false
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

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sellerMode = AdvancedSettingsActivity.isSellerModeEnabled(this)
        menu.findItem(R.id.action_management)?.isVisible = !sellerMode
        menu.findItem(R.id.action_custom_buttons)?.isVisible = !sellerMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_history -> {
                startActivity(Intent(this, OperationsHistoryActivity::class.java))
                true
            }
            R.id.action_custom_buttons -> {
                startActivity(Intent(this, CustomButtonsActivity::class.java))
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
        checkNfcEnabled()
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
        rebuildCustomButtons()
        invalidateOptionsMenu()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableReaderMode(this)
        // Deselect any active button and reset UI state when leaving so that returning from
        // another activity (history, settings, …) never leaves the interface in a stale mode.
        if (selectedButtonIndex >= 0 || isAddBalanceMode) {
            handler.removeCallbacks(autoResetRunnable)
            resetToWaiting()
        }
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
    // NFC enabled state check
    // -------------------------------------------------------------------------

    /**
     * Checks whether NFC is enabled on the device and updates the UI accordingly.
     * When NFC is disabled, shows the NFC icon in place of the balance and hides
     * all interactive elements. When NFC is enabled, restores normal app UI.
     */
    private fun checkNfcEnabled() {
        // NFC hardware not available - handled separately in onCreate
        if (nfcAdapter == null) return
        val nfcEnabled = nfcAdapter?.isEnabled ?: return
        if (nfcEnabled) {
            tvCardId.text = getString(R.string.no_card_detected)
            if (tvBalance.tag == NFC_DISABLED_TAG) {
                tvBalance.tag = null
                tvBalance.setCompoundDrawables(null, null, null, null)
                tvBalance.isClickable = false
                tvBalance.setOnClickListener(null)
                resetBalanceToInitial()
            }
            tvCoinsLabel.visibility = View.VISIBLE
            tvStatus.visibility = View.VISIBLE
            layoutCustomButtons.visibility = View.VISIBLE
        } else {
            handler.removeCallbacks(autoResetRunnable)
            tvCardId.text = getString(R.string.nfc_disabled)
            tvBalance.tag = NFC_DISABLED_TAG
            tvBalance.setText("")
            val drawable = ContextCompat.getDrawable(this, R.drawable.ic_nfc) ?: return
            val size = (80 * resources.displayMetrics.density).toInt()
            drawable.setBounds(0, 0, size, size)
            tvBalance.setCompoundDrawables(null, drawable, null, null)
            tvBalance.isClickable = true
            tvBalance.setOnClickListener { openNfcSettings() }
            tvCoinsLabel.visibility = View.GONE
            tvStatus.visibility = View.GONE
            layoutCustomButtons.visibility = View.GONE
            layoutBeforeAfter.visibility = View.GONE
            tvActualBalance.visibility = View.GONE
            tvMinorIcon.visibility = View.GONE
            layoutTransactionHistory.visibility = View.GONE
        }
    }

    private fun openNfcSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    // -------------------------------------------------------------------------
    // NFC handling
    // -------------------------------------------------------------------------

    /** Entry point for tags discovered via reader mode (called on main thread). */
    private fun handleTag(tag: Tag) {
        triggerVibration()
        if (!BaseCoinCard.isSupported(tag)) {
            return setScreenStatusError(
                message = getString(R.string.unsupported_card),
                background = null
            )
        }
        val card = createCard(tag) ?: run {
            return setScreenStatusError(
                message = getString(R.string.error_get_mifare),
                background = null
            )
        }


        // Mifare Classic: validate that the configured sector exists on this card.
        if (card is MifareClassicCoinCard) {
            val sector = AdvancedSettingsActivity.getTargetSector(this)
            if (sector >= card.mifare.sectorCount) {
                return setScreenStatusError(getString(
                    R.string.sector_unavailable,
                    sector,
                    card.mifare.sectorCount - 1
                ))
            }
        }

        handler.removeCallbacks(autoResetRunnable)
        tvCardId.text = getString(R.string.card_id_format, card.uid.toHex())

        when (pendingAction) {
            PendingAction.ADD_BALANCE -> {
                val isButtonMode = selectedButtonIndex >= 0
                if (!isButtonMode) pendingAction = PendingAction.NONE
                addBalanceToCard(card, isButtonMode = isButtonMode)
            }
            PendingAction.FORMAT_CARD -> {
                pendingAction = PendingAction.NONE
                formatCard(card)
            }
            PendingAction.RESET_CARD -> {
                pendingAction = PendingAction.NONE
                resetCard(card)
            }
            PendingAction.WITHDRAW_BALANCE -> {
                // Do not clear pendingAction here; readAndDeduct manages state depending on
                // success/failure and whether a custom button or custom amount is active.
                when {
                    selectedButtonIndex >= 0 -> {
                        val btn = customButtonList.getOrNull(selectedButtonIndex)
                        if (btn != null) readAndDeduct(card, btn.amount, isButtonMode = true)
                        else setPendingAction(PendingAction.NONE)
                    }
                    customDeductAmount > 0 -> {
                        isCustomAmountMode = false
                        clearHiddenInput()
                        readAndDeduct(card, customDeductAmount, isCustomAmount = true)
                    }
                    else -> setPendingAction(PendingAction.NONE)
                }
            }
            PendingAction.NONE -> {
                readAndShowBalance(card)
            }
        }
    }

    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        handleTag(tag)
    }

    /** Modo sin botón activo: solo muestra el saldo en grande. */
    private fun readAndShowBalance(card: BaseCoinCard) {
        try {
            card.connect()
            when (val result = card.readCardData()) {
                is BaseCoinCard.ReadResult.AuthFailed -> {
                    return setScreenStatusError(getString(R.string.auth_failed))
                }
                is BaseCoinCard.ReadResult.InvalidData -> {
                    return setScreenStatusError(invalidDataMessage(result.reason))
                }
                is BaseCoinCard.ReadResult.Success -> {
                    val data = result.data
                    val txBlock = data.transactions
                    currentBalance = data.balance
                    setBalanceDisplay(currentBalance)
                    updateMinorIndicator(data.userBirthYear)
                    layoutBeforeAfter.visibility = View.GONE
                    tvActualBalance.visibility = View.GONE
                    if (!card.isDataValid(data)) {
                        tvStatus.text = getString(R.string.card_tampered)
                        showTransactionHistory(txBlock)
                        showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                        flashRedBackground()
                        playNfcErrorBeep()
                        scheduleAutoReset()
                        return
                    }
                    showTransactionHistory(txBlock)
                    showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        val currentChecksum = data.checksum.toHex()
                        val lastState = txDb.getLastCardState(card.uid.toHex())
                        if (lastState != null && lastState.checksum != currentChecksum) {
                            tvStatus.text = getString(R.string.replay_attack_detected)
                            flashRedBackground()
                            playNfcErrorBeep()
                            scheduleAutoReset()
                            return
                        }
                        if (lastState == null) {
                            // Only record the initial state when this card is seen for the first time.
                            // Subsequent plain reads must not overwrite the stored checksum so that a
                            // replayed (rolled-back) card cannot reset the local source of truth.
                            txDb.recordCardState(
                                cardUid = card.uid.toHex(),
                                checksum = currentChecksum,
                                balanceBefore = currentBalance,
                                balanceAfter = currentBalance
                            )
                        }
                    }
                    txDb.insertTransaction(TransactionDatabase.TYPE_READ, balanceBefore = currentBalance, cardUid = card.uid.toHex())
                    setScreenStatusSuccess(
                        message = getString(R.string.card_read_ok),
                        background = null
                    )
                }
            }
        } catch (e: Exception) {
            setScreenStatusError(getString(R.string.error_reading, e.message))
        } finally {
            card.close()
        }
    }

    /** Modo con botón activo: descuenta monedas y muestra saldo inicial → final en grande. */
    private fun readAndDeduct(card: BaseCoinCard, amount: Int, isCustomAmount: Boolean = false, isButtonMode: Boolean = false) {
        try {
            card.connect()
            when (val result = card.readCardData()) {
                // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                is BaseCoinCard.ReadResult.AuthFailed -> {
                    return setScreenStatusError(
                        message = getString(R.string.auth_failed),
                        scheduleAutoReset = false
                    )
                }
                is BaseCoinCard.ReadResult.InvalidData -> {
                    return setScreenStatusError(
                        message = invalidDataMessage(result.reason),
                        scheduleAutoReset = false
                    )
                }
                is BaseCoinCard.ReadResult.Success -> {
                    val data = result.data
                    // Anti-tampering: verify checksum before performing any operation.
                    if (!card.isDataValid(data)) {
                        val pw = pendingWrite
                        if (pw != null && pw.matchesUid(card.uid)) {
                            // Interrupted write detected – retry the previous write and continue.
                            card.retryPendingWrite(pw)
                            pendingWrite = null
                            tvStatus.text = getString(R.string.write_retried)
                            // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                            return
                        }
                        if (AdvancedSettingsActivity.isVerifyIntegrityEnabled(this)) {
                            showTransactionHistory(data.transactions)
                            showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                            tvStatus.text = getString(R.string.card_tampered)
                            flashRedBackground()
                            playNfcErrorBeep()
                            // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                            return
                        }
                        // Integrity check disabled: invalid checksum is ignored and the transaction
                        // proceeds. The new write will produce a fresh valid checksum.
                    }
                    pendingWrite = null  // Previous write (if any) was successful.

                    // Replay-attack detection: when not in distributed POS mode, verify the card's
                    // current checksum matches the last state we recorded locally.
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        val currentChecksum = data.checksum.toHex()
                        if (!txDb.isChecksumValid(card.uid.toHex(), currentChecksum)) {
                            showTransactionHistory(data.transactions)
                            showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                            tvStatus.text = getString(R.string.replay_attack_detected)
                            flashRedBackground()
                            playNfcErrorBeep()
                            // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                            return
                        }
                    }

                    val balance = data.balance
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
                        showTransactionHistory(data.transactions)
                        showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                        tvStatus.text = getString(R.string.insufficient_balance)
                        flashRedBackground()
                        playInsufficientBalanceBeep()
                        // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
                        return
                    }

                    val newBalance = balance - amount
                    val newBalanceData = card.encodeBalance(newBalance)
                    val (updatedTxBlock, newTransactions) = card.buildUpdatedTxBlocks(
                        data.transactions, newBalanceData, TxOperation.SUBTRACT, amount
                    )

                    // Retain the intended state in memory so an interrupted write can be retried.
                    pendingWrite = BaseCoinCard.PendingWriteData(card.uid, newBalanceData, newTransactions)

                    card.deductBalance(amount, newTransactions)
                    pendingWrite = null

                    currentBalance = newBalance
                    setBalanceDisplay(newBalance)
                    updateMinorIndicator(data.userBirthYear)
                    tvBalanceBefore.text = formatBalanceDisplay(balance)
                    tvBalanceAfter.text = formatBalanceDisplay(newBalance)
                    layoutBeforeAfter.visibility = View.VISIBLE
                    tvActualBalance.visibility = View.GONE
                    showTransactionHistory(updatedTxBlock)
                    showDebugChecksums(card, newBalance, newTransactions)
                    txDb.insertTransaction(
                        type = TransactionDatabase.TYPE_SUBTRACT,
                        amount = -amount,
                        balanceBefore = balance,
                        balanceAfter = newBalance,
                        cardUid = card.uid.toHex(),
                        buttonValue = amount
                    )
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        txDb.recordCardState(
                            cardUid = card.uid.toHex(),
                            checksum = newTransactions.copyOfRange(newTransactions.size - TX_CHECKSUM_SIZE, newTransactions.size).toHex(),
                            balanceBefore = balance,
                            balanceAfter = newBalance
                        )
                    }
                    setScreenStatusSuccess(
                        message = getString(R.string.deduct_ok, formatBalanceDisplay(amount)),
                        scheduleAutoReset = false,
                        background = null
                    )
                    if (isButtonMode) {
                        // Schedule a soft UI reset after the auto-reset delay; button and pending
                        // action remain active so another card can be served immediately.
                        handler.removeCallbacks(buttonModeIdleRunnable)
                        handler.postDelayed(buttonModeIdleRunnable, AUTO_RESET_DELAY_MS)
                    } else {
                        // Custom-amount is a one-shot transaction: clear it and schedule a full reset.
                        customDeductAmount = 0
                        scheduleAutoReset()
                    }
                }
            }
        } catch (e: Exception) {
            setScreenStatusError(
                message = getString(R.string.error_writing, e.message),
                scheduleAutoReset = false
            )
            // Keep WITHDRAW_BALANCE state: do not schedule auto-reset.
        } finally {
            card.close()
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
    // Language migration
    // -------------------------------------------------------------------------

    /**
     * One-time migration: if a legacy KEY_LANGUAGE preference exists from before the
     * AppCompat per-app locale system was adopted, migrate it to
     * [AppCompatDelegate.setApplicationLocales] and remove the old preference.
     * After migration this function is a no-op.
     */
    private fun migrateLanguagePrefIfNeeded() {
        val prefs = getSharedPreferences(AdvancedSettingsActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(AdvancedSettingsActivity.KEY_LANGUAGE)) return

        val langCode = prefs.getString(AdvancedSettingsActivity.KEY_LANGUAGE, AdvancedSettingsActivity.DEFAULT_LANGUAGE) ?: AdvancedSettingsActivity.DEFAULT_LANGUAGE
        prefs.edit().remove(AdvancedSettingsActivity.KEY_LANGUAGE).apply()

        // Only override AppCompat's locale if it has not already been set (e.g. by the system
        // per-app language selector on API 33+).
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(langCode))
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
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars =
            AdvancedSettingsActivity.contrastColor(bgColor) == Color.BLACK

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

        // Custom buttons: apply theme colors
        applyThemeToCustomButtons()
    }

    private fun applyThemeToCustomButtons() {
        val color = AdvancedSettingsActivity.getThemeColor(this)
        val textOnColor = AdvancedSettingsActivity.contrastColor(color)
        val rippleTint = ColorStateList.valueOf(AdvancedSettingsActivity.rippleColor(color))

        // For buttons with a custom background color, use that color's contrast as text color
        // when selected; for transparent buttons, use theme color logic
        customButtonViews.forEachIndexed { idx, btn ->
            val customBg = customButtonList.getOrNull(idx)?.backgroundColor ?: 0
            val isSelected = idx == selectedButtonIndex

            if (customBg != 0) {
                // Custom background: show full color always, darken when selected
                val fillColor = if (isSelected) darkenColor(customBg) else customBg
                btn.backgroundTintList = ColorStateList.valueOf(fillColor)
                btn.setTextColor(AdvancedSettingsActivity.contrastColor(customBg))
                btn.strokeColor = ColorStateList.valueOf(color)
                btn.rippleColor = rippleTint
            } else {
                // No custom background: use theme color (filled when selected, outlined when not)
                val bgTint = if (isSelected) color else Color.TRANSPARENT
                btn.backgroundTintList = ColorStateList.valueOf(bgTint)
                btn.setTextColor(if (isSelected) textOnColor else color)
                btn.strokeColor = ColorStateList.valueOf(color)
                btn.rippleColor = rippleTint
            }
        }
    }

    private fun darkenColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] *= 0.8f
        return Color.HSVToColor(hsv)
    }

    // -------------------------------------------------------------------------
    // Dynamic custom buttons
    // -------------------------------------------------------------------------

    /**
     * Reads the saved custom button configuration and rebuilds the button grid.
     * Called from [onResume] so any changes made in [CustomButtonsActivity] are reflected.
     * Resets [selectedButtonIndex] to -1 (no selection) on every rebuild.
     */
    private fun rebuildCustomButtons() {
        customButtonList = CustomButton.loadButtons(this)
        selectedButtonIndex = -1
        layoutCustomButtons.removeAllViews()

        val themeColor = AdvancedSettingsActivity.getThemeColor(this)
        val textOnColor = AdvancedSettingsActivity.contrastColor(themeColor)
        val rippleTint = ColorStateList.valueOf(AdvancedSettingsActivity.rippleColor(themeColor))
        val dp = resources.displayMetrics.density
        val btnHeightPx = (100 * dp).toInt()
        val rowMarginPx = (2 * dp).toInt()
        val colMarginPx = (4 * dp).toInt()

        val btnViews = mutableListOf<MaterialButton>()

        var currentRow: LinearLayout? = null
        for ((idx, btn) in customButtonList.withIndex()) {
            if (idx % 3 == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        btnHeightPx
                    )
                    if (idx > 0) lp.topMargin = rowMarginPx
                    layoutParams = lp
                }
                layoutCustomButtons.addView(currentRow)
            }

            val customBg = btn.backgroundColor
            val btnView = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = btn.buttonDisplayText()
                textSize = if (btn.emoji.isNotEmpty()) 26f else 20f
                isAllCaps = false
                isSingleLine = false
                setTextColor(if (customBg != 0) AdvancedSettingsActivity.contrastColor(customBg) else themeColor)
                backgroundTintList = ColorStateList.valueOf(if (customBg != 0) customBg else Color.TRANSPARENT)
                strokeColor = ColorStateList.valueOf(themeColor)
                rippleColor = rippleTint

                val lp = LinearLayout.LayoutParams(0, btnHeightPx, 1f)
                if (idx % 3 > 0) lp.marginStart = colMarginPx
                layoutParams = lp

                val capturedIdx = idx
                setOnClickListener { onCustomButtonClicked(capturedIdx) }
            }

            currentRow?.addView(btnView)
            btnViews.add(btnView)
        }

        customButtonViews = btnViews
    }

    private fun onCustomButtonClicked(index: Int) {
        val btn = customButtonList.getOrNull(index) ?: return

        if (selectedButtonIndex == index) {
            // Tapping the active button deselects it; revert state based on operation
            val wasWithdraw = pendingAction == PendingAction.WITHDRAW_BALANCE
            val wasAdd = pendingAction == PendingAction.ADD_BALANCE
            handler.removeCallbacks(buttonModeIdleRunnable)
            clearCustomButtonSelection()
            when {
                wasWithdraw && customDeductAmount == 0 -> {
                    if (currentBalance >= 0) resetToWaiting()
                    else {
                        setPendingAction(PendingAction.NONE)
                        resetBalanceToInitial()
                        tvStatus.text = getString(R.string.waiting_card)
                    }
                }
                wasAdd -> {
                    setPendingAction(PendingAction.NONE)
                    pendingAddAmount = 0
                    resetBalanceToInitial()
                    tvStatus.text = getString(R.string.waiting_card)
                }
            }
            return
        }

        // Save before cancelAddBalance() may reset selectedButtonIndex via clearCustomButtonSelection.
        val prevIdx = selectedButtonIndex

        // Cancel any active inline add-balance mode
        if (isAddBalanceMode || pendingAction == PendingAction.ADD_BALANCE) {
            cancelAddBalance()
        } else if (customDeductAmount > 0) {
            resetBalanceToInitial()
        }

        // Deselect the previous button (if any) and select this one
        selectedButtonIndex = index
        if (prevIdx >= 0) applyButtonSelectionStyle(prevIdx, selected = false)
        // Always clear card-specific UI when switching to a new button so previous transaction
        // details don't bleed into the new session — regardless of how the previous state was set.
        handler.removeCallbacks(buttonModeIdleRunnable)
        handler.removeCallbacksAndMessages(FLASH_TOKEN)
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        tvCardId.text = getString(R.string.no_card_detected)
        currentBalance = -1
        layoutBeforeAfter.visibility = View.GONE
        tvActualBalance.visibility = View.GONE
        tvMinorIcon.visibility = View.GONE
        layoutTransactionHistory.visibility = View.GONE
        tvTx.forEach { it.visibility = View.GONE }
        applyButtonSelectionStyle(index, selected = true)

        if (btn.operation == CustomButton.OP_ADD) {
            pendingAddAmount = btn.amount
            setPendingAction(PendingAction.ADD_BALANCE)
            tvStatus.text = getString(R.string.tap_card_to_add)
        } else {
            setPendingAction(PendingAction.WITHDRAW_BALANCE)
            tvStatus.text = getString(R.string.tap_card_to_deduct)
        }

        // Show the button's amount in the big balance display as immediate visual feedback.
        // Only show the "+" prefix for ADD operations; WITHDRAW shows the plain amount.
        val prefix = if (btn.operation == CustomButton.OP_ADD) "+" else ""
        tvBalance.setText("$prefix${formatBalanceDisplay(btn.amount)}")
    }

    private fun applyButtonSelectionStyle(index: Int, selected: Boolean) {
        val btnView = customButtonViews.getOrNull(index) ?: return
        val btn = customButtonList.getOrNull(index) ?: return
        val themeColor = AdvancedSettingsActivity.getThemeColor(this)
        val textOnColor = AdvancedSettingsActivity.contrastColor(themeColor)
        val customBg = btn.backgroundColor

        if (customBg != 0) {
            val fillColor = if (selected) darkenColor(customBg) else customBg
            btnView.backgroundTintList = ColorStateList.valueOf(fillColor)
            btnView.setTextColor(AdvancedSettingsActivity.contrastColor(customBg))
        } else {
            val bgColor = if (selected) themeColor else Color.TRANSPARENT
            btnView.backgroundTintList = ColorStateList.valueOf(bgColor)
            btnView.setTextColor(if (selected) textOnColor else themeColor)
        }
    }

    /** Clears the current custom button selection without changing pending action. */
    private fun clearCustomButtonSelection() {
        val prevIdx = selectedButtonIndex
        selectedButtonIndex = -1
        if (prevIdx >= 0) applyButtonSelectionStyle(prevIdx, selected = false)
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
     * Returns the user-facing message for an [InvalidData] result.
     * When the reason indicates a decryption failure and debug mode is off, the generic
     * [R.string.auth_failed] message is returned to avoid leaking internals.
     * In debug mode (or for non-decryption errors) the full [R.string.error_reading] is returned.
     */
    private fun invalidDataMessage(reason: String): String {
        return if (reason.startsWith(NtagCoinCard.DECRYPT_ERROR_PREFIX)) {
            if (!AdvancedSettingsActivity.isDebugEnabled(this)) {
                getString(R.string.auth_failed)
            } else {
                val details = reason.removePrefix(NtagCoinCard.DECRYPT_ERROR_PREFIX)
                getString(R.string.error_reading, "${getString(R.string.error_decryption)}: ${details.trim()}")
            }
        } else {
            getString(R.string.error_reading, reason)
        }
    }

    private fun setScreenStatusError(
        message: String,
        scheduleAutoReset: Boolean = true,
        @ColorRes background: Int? = R.color.error_orange
    ) {
        tvStatus.text = message
        if (background != null) flashBackground(background)
        playNfcErrorBeep()
        if (scheduleAutoReset) {
            this.scheduleAutoReset()
        }
        return
    }

    private fun setScreenStatusSuccess(
        message: String,
        scheduleAutoReset: Boolean = true,
        @ColorRes background: Int? = R.color.success_green
    ) {
        tvStatus.text = message
        if (background != null) flashBackground(background)
        playSuccessBeep()
        if (scheduleAutoReset) {
            this.scheduleAutoReset()
        }
        return
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
        handler.removeCallbacks(buttonModeIdleRunnable)
        handler.removeCallbacksAndMessages(FLASH_TOKEN)
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        tvCardId.text = getString(R.string.no_card_detected)
        currentBalance = -1
        isAddBalanceMode = false
        clearCustomButtonSelection()
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

    /**
     * Clears card-specific UI after a button-mode transaction while keeping the button selected
     * and the pending action active, so the next card can be served immediately.
     */
    private fun resetButtonModeState() {
        handler.removeCallbacksAndMessages(FLASH_TOKEN)
        rootLayout.setBackgroundColor(Color.TRANSPARENT)
        tvCardId.text = getString(R.string.no_card_detected)
        currentBalance = -1
        layoutBeforeAfter.visibility = View.GONE
        tvActualBalance.visibility = View.GONE
        tvMinorIcon.visibility = View.GONE
        layoutTransactionHistory.visibility = View.GONE
        tvTx.forEach { it.visibility = View.GONE }
        val btn = customButtonList.getOrNull(selectedButtonIndex) ?: return
        val prefix = if (btn.operation == CustomButton.OP_ADD) "+" else ""
        tvBalance.setText("$prefix${formatBalanceDisplay(btn.amount)}")
        tvStatus.text = if (btn.operation == CustomButton.OP_ADD)
            getString(R.string.tap_card_to_add) else getString(R.string.tap_card_to_deduct)
    }

    private fun cancelAddBalance() {
        isAddBalanceMode = false
        pendingAction = PendingAction.NONE
        pendingAddAmount = 0
        clearCustomButtonSelection()
        clearHiddenInput()
        resetBalanceToInitial()
        tvStatus.text = getString(R.string.waiting_card)
    }

    // -------------------------------------------------------------------------
    // Acerca de
    // -------------------------------------------------------------------------

    private fun showAboutDialog() {
        val appName = getString(R.string.app_name)
        val githubUrl = getVersionAwareGithubUrl()
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title, appName))
            .setMessage(getString(R.string.about_message, appName, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.view_source) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                try {
                    startActivity(intent)
                } catch (_: ActivityNotFoundException) { }
            }
            .show()
        applyThemeToDialog(dialog)
    }

    private fun getVersionAwareGithubUrl(): String {
        val baseUrl = getString(R.string.github_url)
        val commitHash = GIT_DESCRIBE_COMMIT_REGEX.find(BuildConfig.VERSION_NAME)?.groupValues?.getOrNull(1)
        return if (commitHash.isNullOrEmpty()) baseUrl else "$baseUrl/commits/$commitHash"
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
                        clearCustomButtonSelection()
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
     *   Stores (birthYear - 1900) in the GPB user byte of the sector trailer access bits.
     *   Defaults to [MifareClassicHelper.DEFAULT_USER_BYTE] when empty or invalid.
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
                pendingUserBirthYear = parseUserBirthYear(editAge.text.toString())
                cancelAddBalance()
                clearCustomButtonSelection()
                setPendingAction(PendingAction.FORMAT_CARD)
                tvStatus.text = getString(R.string.tap_card_to_format)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        applyThemeToDialog(dialog)
    }

    /**
     * Interprets [input] as an age (1–99, resolved to currentYear - age) or birth year
     * (1900–currentYear) and returns the birth year directly.
     * Returns the default birth year when the input is empty or out of range.
     */
    private fun parseUserBirthYear(input: String): Int {
        val trimmed = input.trim()
        val defaultBirthYear = MifareClassicHelper.toUserBirthYear(MifareClassicHelper.DEFAULT_USER_BYTE)
        if (trimmed.isEmpty()) return defaultBirthYear
        val value = trimmed.toIntOrNull() ?: return defaultBirthYear
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val birthYear = when {
            value in 1..99 -> currentYear - value
            value in 1900..currentYear -> value
            else -> return defaultBirthYear
        }
        return birthYear
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
        clearCustomButtonSelection()
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
    private fun addBalanceToCard(card: BaseCoinCard, isButtonMode: Boolean = false) {
        if (!isButtonMode) hideKeyboardFrom(etHiddenInput)
        isAddBalanceMode = false
        try {
            card.connect()
            when (val result = card.readCardData()) {
                is BaseCoinCard.ReadResult.AuthFailed -> {
                    return setScreenStatusError(getString(R.string.card_not_formatted))
                }
                is BaseCoinCard.ReadResult.InvalidData -> {
                    return setScreenStatusError(invalidDataMessage(result.reason))
                }
                is BaseCoinCard.ReadResult.Success -> {
                    val data = result.data
                    val txBlock = data.transactions

                    // Anti-tampering: verify checksum before performing any operation.
                    if (!card.isDataValid(data)) {
                        val pw = pendingWrite
                        if (pw != null && pw.matchesUid(card.uid)) {
                            // Interrupted write detected – retry the previous write and continue.
                            card.retryPendingWrite(pw)
                            pendingWrite = null
                            tvStatus.text = getString(R.string.write_retried)
                            scheduleAutoReset()
                            return
                        }
                        if (AdvancedSettingsActivity.isVerifyIntegrityEnabled(this)) {
                            tvStatus.text = getString(R.string.card_tampered)
                            showTransactionHistory(txBlock)
                            showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                            flashRedBackground()
                            playNfcErrorBeep()
                            scheduleAutoReset()
                            return
                        }
                        // Integrity check disabled: proceed with fresh checksum.
                    }
                    pendingWrite = null

                    // Replay-attack detection: when not in distributed POS mode, verify the card's
                    // current checksum matches the last state we recorded locally.
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        val currentChecksum = data.checksum.toHex()
                        if (!txDb.isChecksumValid(card.uid.toHex(), currentChecksum)) {
                            showTransactionHistory(txBlock)
                            showDebugChecksums(card, data.balance, data.transactionsDataWithChecksum)
                            tvStatus.text = getString(R.string.replay_attack_detected)
                            flashRedBackground()
                            playNfcErrorBeep()
                            scheduleAutoReset()
                            return
                        }
                    }

                    val oldBalance = data.balance
                    val newBalance = oldBalance + pendingAddAmount
                    if (newBalance > card.maxBalance) {
                        Toast.makeText(this, getString(R.string.balance_too_high), Toast.LENGTH_SHORT).show()
                        pendingAddAmount = 0
                        scheduleAutoReset()
                        return
                    }

                    val newBalanceData = card.encodeBalance(newBalance)
                    val (updatedTxBlock, newTransactions) = card.buildUpdatedTxBlocks(
                        data.transactions, newBalanceData, TxOperation.ADD, pendingAddAmount
                    )

                    // Retain the intended state in memory so an interrupted write can be retried.
                    pendingWrite = BaseCoinCard.PendingWriteData(card.uid, newBalanceData, newTransactions)

                    val isFirstAdd = data.transactions.transactions.isEmpty()
                    if (data.isSingleRecharge) {
                        if (!isFirstAdd) {
                            // Card was already charged once; reject any further balance additions.
                            pendingWrite = null
                            return setScreenStatusError(getString(R.string.single_recharge_already_used))
                        }
                        card.unlockRecharge(data)
                    }

                    card.addBalance(pendingAddAmount, newTransactions)

                    if (data.isSingleRecharge) {
                        card.lockRecharge(data)
                    }

                    pendingWrite = null

                    currentBalance = newBalance
                    setBalanceDisplay(newBalance)
                    updateMinorIndicator(data.userBirthYear)
                    tvBalanceBefore.text = formatBalanceDisplay(oldBalance)
                    tvBalanceAfter.text = formatBalanceDisplay(newBalance)
                    layoutBeforeAfter.visibility = View.VISIBLE
                    showTransactionHistory(updatedTxBlock)
                    showDebugChecksums(card, newBalance, newTransactions)
                    txDb.insertTransaction(
                        type = TransactionDatabase.TYPE_ADD,
                        amount = pendingAddAmount,
                        balanceBefore = oldBalance,
                        balanceAfter = newBalance,
                        cardUid = card.uid.toHex()
                    )
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        txDb.recordCardState(
                            cardUid = card.uid.toHex(),
                            checksum = newTransactions.copyOfRange(newTransactions.size - TX_CHECKSUM_SIZE, newTransactions.size).toHex(),
                            balanceBefore = oldBalance,
                            balanceAfter = newBalance
                        )
                    }
                    setScreenStatusSuccess(
                        message = getString(R.string.balance_added_ok, formatBalanceDisplay(pendingAddAmount)),
                        scheduleAutoReset = !isButtonMode,
                        background = null
                    )
                    if (isButtonMode) {
                        // Button remains active: keep ADD_BALANCE state for back-to-back transactions.
                        setPendingAction(PendingAction.ADD_BALANCE)
                        // Schedule a soft UI reset after the auto-reset delay; button and pending
                        // action remain active so another card can be served immediately.
                        handler.removeCallbacks(buttonModeIdleRunnable)
                        handler.postDelayed(buttonModeIdleRunnable, AUTO_RESET_DELAY_MS)
                        tvStatus.text = getString(R.string.tap_card_to_add)
                    }
                }
            }
        } catch (e: Exception) {
            setScreenStatusError(getString(R.string.error_writing, e.message))
        } finally {
            card.close()
        }
    }

    /**
     * Attempts to format the configured sector of the card.
     * First tries the derived key: if already formatted, resets the balance to zero.
     * Otherwise searches for a standard key and writes the sector with the derived key.
     */
    private fun formatCard(card: BaseCoinCard) {
        try {
            card.connect()
            val formatOptions = BaseCoinCard.CardData(
                balance = 0,
                userBirthYear = pendingUserBirthYear,
                isSingleRecharge = pendingSingleRecharge
            )
            when (val result = card.formatCard(formatOptions)) {
                is BaseCoinCard.FormatResult.Reformatted -> {
                    currentBalance = 0
                    setBalanceDisplay(0)
                    tvBalanceBefore.text = formatBalanceDisplay(result.oldBalance)
                    tvBalanceAfter.text = formatBalanceDisplay(0)
                    layoutBeforeAfter.visibility = View.VISIBLE
                    showTransactionHistory(TransactionBlock(result.formattedAtSeconds))
                    showDebugChecksums(card, 0, result.transactionsData)
                    txDb.insertTransaction(TransactionDatabase.TYPE_FORMAT, cardUid = card.uid.toHex())
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        txDb.recordCardState(
                            cardUid = card.uid.toHex(),
                            checksum = result.transactionsData.copyOfRange(result.transactionsData.size - TX_CHECKSUM_SIZE, result.transactionsData.size).toHex(),
                            balanceBefore = result.oldBalance,
                            balanceAfter = 0
                        )
                    }
                    setScreenStatusSuccess(
                        message = getString(R.string.format_reset_success),
                        background = R.color.success_purple_dark
                    )
                }
                is BaseCoinCard.FormatResult.NewlyFormatted -> {
                    currentBalance = 0
                    setBalanceDisplay(0)
                    layoutBeforeAfter.visibility = View.GONE
                    showTransactionHistory(TransactionBlock(result.formattedAtSeconds))
                    showDebugChecksums(card, 0, result.transactionsData)
                    txDb.insertTransaction(TransactionDatabase.TYPE_FORMAT, cardUid = card.uid.toHex())
                    if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                        txDb.recordCardState(
                            cardUid = card.uid.toHex(),
                            checksum = result.transactionsData.copyOfRange(result.transactionsData.size - TX_CHECKSUM_SIZE, result.transactionsData.size).toHex(),
                            balanceBefore = 0,
                            balanceAfter = 0
                        )
                    }
                    setScreenStatusSuccess(
                        message = getString(R.string.format_success),
                        background = R.color.success_purple_dark
                    )

                    if (AdvancedSettingsActivity.isDebugEnabled(this) && result.foundKeyType != null) {
                        val debugDialog = AlertDialog.Builder(this)
                            .setTitle(R.string.format_success)
                            .setMessage(getString(R.string.format_success_message, result.foundKeyType, result.foundKeyHex, result.newKeyHex))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                        applyThemeToDialog(debugDialog)
                    }
                }
                is BaseCoinCard.FormatResult.NoKeyFound -> {
                    setScreenStatusError(getString(R.string.format_no_key_found))
                }
                is BaseCoinCard.FormatResult.AuthFailed -> {
                    setScreenStatusError(getString(R.string.auth_failed))
                }
            }
        } catch (e: Exception) {
            setScreenStatusError(getString(R.string.error_writing, e.message))
        } finally {
            card.close()
        }
    }

    // -------------------------------------------------------------------------
    // Reset card
    // -------------------------------------------------------------------------

    private fun resetCard(card: BaseCoinCard) {
        try {
            card.connect()
            if (!card.resetCard()) {
                return setScreenStatusError(getString(R.string.reset_card_no_key))
            }

            currentBalance = -1
            resetBalanceToInitial()
            layoutBeforeAfter.visibility = View.GONE
            txDb.insertTransaction(TransactionDatabase.TYPE_RESET, cardUid = card.uid.toHex())
            if (!AdvancedSettingsActivity.isDistributedPosEnabled(this)) {
                // Store a sentinel so any replay of the pre-reset card state is detected.
                txDb.recordCardState(
                    cardUid = card.uid.toHex(),
                    checksum = "",
                    balanceBefore = 0,
                    balanceAfter = 0
                )
            }
            setScreenStatusSuccess(
                message = getString(R.string.reset_card_success),
                background = R.color.success_purple_dark
            )
        } catch (e: Exception) {
            setScreenStatusError(getString(R.string.error_writing, e.message))
        } finally {
            card.close()
        }
    }

    /**
     * Creates a [BaseCoinCard] for the given [tag] using the current app settings.
     * Returns null if the tag technology is not supported or cannot be obtained.
     */
    private fun createCard(tag: Tag): BaseCoinCard? {
        return BaseCoinCard.fromTag(tag, this)
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
     * The icon is shown when the computed age from [userBirthYear] is below the
     * configured legal adult age and the year is plausible.
     */
    private fun updateMinorIndicator(userBirthYear: Int) {
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        val age = currentYear - userBirthYear
        val legalAge = AdvancedSettingsActivity.getLegalAge(this)
        tvMinorIcon.visibility = if (userBirthYear in 1900..currentYear && age in 0 until legalAge) View.VISIBLE else View.GONE
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
                    val cappedValue = storedValue.coerceIn(0, MifareClassicHelper.MAX_BALANCE)
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
                        clearCustomButtonSelection()
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
                        value > MifareClassicHelper.MAX_BALANCE -> {
                            isUpdating = true
                            s?.replace(0, s.length, MifareClassicHelper.MAX_BALANCE.toString())
                            isUpdating = false
                            MifareClassicHelper.MAX_BALANCE
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
                    value > MifareClassicHelper.MAX_BALANCE -> {
                        isUpdating = true
                        s?.replace(0, s.length, MifareClassicHelper.MAX_BALANCE.toString())
                        isUpdating = false
                        tvBalance.setText(MifareClassicHelper.MAX_BALANCE.toString())
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
                    clearCustomButtonSelection()
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

    /**
     * When debug mode is enabled, computes both the stored and expected checksums from the
     * given raw card blocks and displays them in [tvTxDebug] at the bottom of the
     * transaction history section.
     */
    private fun showDebugChecksums(
        card: BaseCoinCard,
        balance: Int,
        txData: ByteArray
    ) {
        if (!AdvancedSettingsActivity.isDebugEnabled(this)) {
            tvTxDebug.visibility = View.GONE
            return
        }
        val (stored, computed) = card.extractChecksums(balance, txData)
        tvTxDebug.text = getString(R.string.tx_debug_checksum, stored.toHex(), computed.toHex())
        layoutTransactionHistory.visibility = View.VISIBLE
        tvTxDebug.visibility = View.VISIBLE
    }
}
