package com.example.nfcpos

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        private val PSK: String get() = BuildConfig.NFC_PSK

        private const val TARGET_SECTOR = 14
        private const val DATA_BLOCK_OFFSET = 0
        private const val KEY_LEN = 6

        // Claves NFC estándar a probar al formatear (fábrica y NDEF)
        private val STANDARD_KEYS = listOf(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), // Fábrica (por defecto)
            byteArrayOf(0xA0.toByte(), 0xA1.toByte(), 0xA2.toByte(), 0xA3.toByte(), 0xA4.toByte(), 0xA5.toByte()), // NDEF Key A
            byteArrayOf(0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte(), 0xD3.toByte(), 0xF7.toByte()), // NDEF datos
            byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00),                                                       // Todo ceros
            byteArrayOf(0x4D.toByte(), 0x3A.toByte(), 0x99.toByte(), 0xC3.toByte(), 0x51.toByte(), 0xDD.toByte()), // NDEF Key B común
            byteArrayOf(0x1A.toByte(), 0x98.toByte(), 0x2C.toByte(), 0x7E.toByte(), 0x45.toByte(), 0x9A.toByte()), // MAD key
            byteArrayOf(0xB0.toByte(), 0xB1.toByte(), 0xB2.toByte(), 0xB3.toByte(), 0xB4.toByte(), 0xB5.toByte())  // Key B común
        )

        // Máximo valor de saldo (uint16: 2 bytes big-endian)
        private const val MAX_BALANCE = 0xFFFF

        // Bits de acceso estándar: lectura y escritura con Key A en todos los bloques de datos
        private val ACCESS_BITS = byteArrayOf(0xFF.toByte(), 0x07.toByte(), 0x80.toByte(), 0x69.toByte())

        private const val AUTO_RESET_DELAY_MS = 7000L
    }

    private enum class PendingAction { NONE, ADD_BALANCE, FORMAT_CARD }

    private lateinit var rootLayout: View
    private lateinit var tvStatus: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvCardId: TextView
    private lateinit var tvBalanceBefore: TextView
    private lateinit var tvBalanceAfter: TextView
    private lateinit var layoutBeforeAfter: LinearLayout
    private lateinit var toggleGroup: MaterialButtonToggleGroup

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    private var currentBalance: Int = -1
    private var currentTag: Tag? = null
    private var pendingAction: PendingAction = PendingAction.NONE
    private var pendingAddAmount: Int = 0

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
        layoutBeforeAfter = findViewById(R.id.layoutBeforeAfter)
        toggleGroup       = findViewById(R.id.toggleGroup)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            toggleGroup.isEnabled = false
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            handleNfcIntent(intent)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_management) {
            showCardManagementDialog()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    // -------------------------------------------------------------------------
    // NFC handling
    // -------------------------------------------------------------------------

    private fun handleNfcIntent(intent: Intent) {
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return

        if (!tag.techList.contains(MifareClassic::class.java.name)) {
            tvStatus.text = getString(R.string.unsupported_card)
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
            PendingAction.NONE -> {
                val cardKey = deriveCardKey(uid)
                when (toggleGroup.checkedButtonId) {
                    R.id.btnDeduct1 -> readAndDeduct(tag, cardKey, 1)
                    R.id.btnDeduct2 -> readAndDeduct(tag, cardKey, 2)
                    else            -> readAndShowBalance(tag, cardKey)
                }
            }
        }
    }

    /** Modo sin botón activo: solo muestra el saldo en grande. */
    private fun readAndShowBalance(tag: Tag, cardKey: ByteArray) {
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            scheduleAutoReset()
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(TARGET_SECTOR, cardKey)) {
                tvStatus.text = getString(R.string.auth_failed)
                scheduleAutoReset()
                return
            }
            val blockIndex = mifare.sectorToBlock(TARGET_SECTOR) + DATA_BLOCK_OFFSET
            val data = mifare.readBlock(blockIndex)
            currentBalance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            tvBalance.text = currentBalance.toString()
            layoutBeforeAfter.visibility = View.GONE
            tvStatus.text = getString(R.string.card_read_ok)
            scheduleAutoReset()
        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_reading, e.message)
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    /** Modo con botón activo: descuenta monedas y muestra saldo inicial → final en grande. */
    private fun readAndDeduct(tag: Tag, cardKey: ByteArray, amount: Int) {
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            scheduleAutoReset()
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(TARGET_SECTOR, cardKey)) {
                tvStatus.text = getString(R.string.auth_failed)
                scheduleAutoReset()
                return
            }
            val blockIndex = mifare.sectorToBlock(TARGET_SECTOR) + DATA_BLOCK_OFFSET
            val data = mifare.readBlock(blockIndex)
            val balance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)

            if (balance < amount) {
                currentBalance = balance
                tvBalance.text = balance.toString()
                layoutBeforeAfter.visibility = View.GONE
                tvStatus.text = getString(R.string.insufficient_balance)
                flashRedBackground()
                scheduleAutoReset()
                return
            }

            val newBalance = balance - amount
            val block = ByteArray(MifareClassic.BLOCK_SIZE)
            block[0] = ((newBalance shr 8) and 0xFF).toByte()
            block[1] = (newBalance and 0xFF).toByte()
            mifare.writeBlock(blockIndex, block)

            currentBalance = newBalance
            tvBalance.text = newBalance.toString()
            tvBalanceBefore.text = balance.toString()
            tvBalanceAfter.text = newBalance.toString()
            layoutBeforeAfter.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.deduct_ok, amount)
            scheduleAutoReset()

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Fondo rojo para saldo insuficiente
    // -------------------------------------------------------------------------

    private fun flashRedBackground() {
        rootLayout.setBackgroundColor(ContextCompat.getColor(this, R.color.error_red_dark))
        handler.postDelayed({
            rootLayout.setBackgroundColor(Color.TRANSPARENT)
        }, 3000)
    }

    private fun scheduleAutoReset() {
        handler.removeCallbacks(autoResetRunnable)
        handler.postDelayed(autoResetRunnable, AUTO_RESET_DELAY_MS)
    }

    private fun resetToWaiting() {
        tvCardId.text = getString(R.string.no_card_detected)
        tvBalance.text = getString(R.string.balance_initial)
        layoutBeforeAfter.visibility = View.GONE
        tvStatus.text = getString(R.string.waiting_card)
        currentBalance = -1
        pendingAction = PendingAction.NONE
        pendingAddAmount = 0
    }

    // -------------------------------------------------------------------------
    // Gestión de tarjetas
    // -------------------------------------------------------------------------

    private fun showCardManagementDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.card_management)
            .setItems(
                arrayOf(
                    getString(R.string.action_add_balance),
                    getString(R.string.action_format_card)
                )
            ) { _, which ->
                when (which) {
                    0 -> showAddAmountDialog()
                    1 -> {
                        pendingAction = PendingAction.FORMAT_CARD
                        tvStatus.text = getString(R.string.tap_card_to_format)
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
        val input = EditText(this).apply {
            hint = getString(R.string.hint_add_amount)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_balance)
            .setView(input)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val amount = input.text.toString().toIntOrNull()
                if (amount == null || amount <= 0) {
                    Toast.makeText(this, getString(R.string.invalid_amount), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pendingAddAmount = amount
                pendingAction = PendingAction.ADD_BALANCE
                tvStatus.text = getString(R.string.tap_card_to_add)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAction = PendingAction.NONE
                pendingAddAmount = 0
            }
            .show()
    }

    /**
     * Aplica el saldo pendiente a la tarjeta. Solo intenta con la clave derivada del UID;
     * si falla la autenticación, la tarjeta no está formateada y se muestra un error.
     */
    private fun addBalanceToCard(tag: Tag) {
        val uid = tag.id
        val cardKey = deriveCardKey(uid)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            scheduleAutoReset()
            return
        }
        try {
            mifare.connect()
            if (!mifare.authenticateSectorWithKeyA(TARGET_SECTOR, cardKey)) {
                tvStatus.text = getString(R.string.card_not_formatted)
                scheduleAutoReset()
                return
            }
            val blockIndex = mifare.sectorToBlock(TARGET_SECTOR) + DATA_BLOCK_OFFSET
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
            tvBalance.text = newBalance.toString()
            tvBalanceBefore.text = oldBalance.toString()
            tvBalanceAfter.text = newBalance.toString()
            layoutBeforeAfter.visibility = View.VISIBLE
            tvStatus.text = getString(R.string.balance_added_ok, pendingAddAmount)
            scheduleAutoReset()
        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    /**
     * Intenta formatear el sector 14 de la tarjeta.
     * Primero prueba la clave derivada del UID: si ya está formateada, solo pone el saldo a cero.
     * Si no, busca una clave estándar y escribe el sector con la clave derivada.
     */
    private fun formatCard(tag: Tag) {
        val uid = tag.id
        val derivedKey = deriveCardKey(uid)
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            scheduleAutoReset()
            return
        }

        try {
            mifare.connect()

            // Si ya está formateada con la clave derivada, solo reinicia el saldo a 0
            if (mifare.authenticateSectorWithKeyA(TARGET_SECTOR, derivedKey)) {
                val blockIndex = mifare.sectorToBlock(TARGET_SECTOR) + DATA_BLOCK_OFFSET
                val data = mifare.readBlock(blockIndex)
                val oldBalance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                mifare.writeBlock(blockIndex, ByteArray(MifareClassic.BLOCK_SIZE))

                currentBalance = 0
                tvBalance.text = "0"
                tvBalanceBefore.text = oldBalance.toString()
                tvBalanceAfter.text = "0"
                layoutBeforeAfter.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.format_reset_success)
                scheduleAutoReset()
                return
            }

            // Buscar clave estándar que permita acceder al sector
            var foundKey: ByteArray? = null
            var usedKeyA = true

            for (key in STANDARD_KEYS) {
                if (mifare.authenticateSectorWithKeyA(TARGET_SECTOR, key)) {
                    foundKey = key
                    usedKeyA = true
                    break
                }
                if (mifare.authenticateSectorWithKeyB(TARGET_SECTOR, key)) {
                    foundKey = key
                    usedKeyA = false
                    break
                }
            }

            if (foundKey == null) {
                tvStatus.text = getString(R.string.format_no_key_found)
                scheduleAutoReset()
                return
            }

            // Reautenticar con la clave encontrada
            val reAuthed = if (usedKeyA) {
                mifare.authenticateSectorWithKeyA(TARGET_SECTOR, foundKey)
            } else {
                mifare.authenticateSectorWithKeyB(TARGET_SECTOR, foundKey)
            }
            if (!reAuthed) {
                tvStatus.text = getString(R.string.auth_failed)
                scheduleAutoReset()
                return
            }

            // Limpiar bloques de datos del sector
            val sectorStart = mifare.sectorToBlock(TARGET_SECTOR)
            val blocksInSector = mifare.getBlockCountInSector(TARGET_SECTOR)
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
            tvBalance.text = "0"
            layoutBeforeAfter.visibility = View.GONE
            tvStatus.text = getString(R.string.format_success)
            scheduleAutoReset()

            AlertDialog.Builder(this)
                .setTitle(R.string.format_success)
                .setMessage(getString(R.string.format_success_message, keyType, foundKeyHex, newKeyHex))
                .setPositiveButton(android.R.string.ok, null)
                .show()

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
            scheduleAutoReset()
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Derivación de clave: HMAC-SHA1(PSK, UID) → primeros 6 bytes
    // -------------------------------------------------------------------------

    private fun deriveCardKey(uid: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA1")
            val secretKey = SecretKeySpec(PSK.toByteArray(Charsets.UTF_8), "HmacSHA1")
            mac.init(secretKey)
            mac.doFinal(uid).take(KEY_LEN).toByteArray()
        } catch (e: Exception) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(PSK.toByteArray(Charsets.UTF_8))
            digest.update(uid)
            digest.digest().take(KEY_LEN).toByteArray()
        }
    }

    // -------------------------------------------------------------------------
    // Utilidades
    // -------------------------------------------------------------------------

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
