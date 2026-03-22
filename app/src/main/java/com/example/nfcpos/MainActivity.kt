package com.example.nfcpos

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NFC POS – Monedero con Mifare Classic
 *
 * Lee el sector 14 de una tarjeta Mifare Classic.
 * Genera una clave única por tarjeta derivada del UID + PSK.
 * Permite descontar 1 o 2 monedas del saldo almacenado.
 *
 * Formato del bloque de datos (bloque 56 = primer bloque del sector 14):
 *   Bytes 0-1 : saldo en big-endian (uint16)
 *   Bytes 2-15: reservados (0x00)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // PSK leído desde BuildConfig (configurable en tiempo de compilación)
        private val PSK: String get() = BuildConfig.NFC_PSK

        // Sector y bloque objetivo
        private const val TARGET_SECTOR = 14
        private const val DATA_BLOCK_OFFSET = 0   // bloque 0 dentro del sector (bloque 56 físico)

        // Longitud de clave Mifare Classic
        private const val KEY_LEN = 6
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvCardId: TextView
    private lateinit var btnDeduct1: Button
    private lateinit var btnDeduct2: Button

    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null

    // Estado actual
    private var currentBalance: Int = -1
    private var currentTag: Tag? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus   = findViewById(R.id.tvStatus)
        tvBalance  = findViewById(R.id.tvBalance)
        tvCardId   = findViewById(R.id.tvCardId)
        btnDeduct1 = findViewById(R.id.btnDeduct1)
        btnDeduct2 = findViewById(R.id.btnDeduct2)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_available)
            btnDeduct1.isEnabled = false
            btnDeduct2.isEnabled = false
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        btnDeduct1.isEnabled = false
        btnDeduct2.isEnabled = false

        btnDeduct1.setOnClickListener { deductCoins(1) }
        btnDeduct2.setOnClickListener { deductCoins(2) }

        // Si la actividad se lanzó desde un intent NFC, procesarlo
        if (intent?.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            handleNfcIntent(intent)
        }
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

        // Solo procesamos Mifare Classic
        if (!tag.techList.contains(MifareClassic::class.java.name)) {
            tvStatus.text = getString(R.string.unsupported_card)
            return
        }

        currentTag = tag
        val uid = tag.id
        val uidHex = uid.toHex()
        tvCardId.text = getString(R.string.card_id_format, uidHex)

        val cardKey = deriveCardKey(uid)

        readSector(tag, cardKey)
    }

    private fun readSector(tag: Tag, cardKey: ByteArray) {
        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            return
        }

        try {
            mifare.connect()

            val authenticated = mifare.authenticateSectorWithKeyA(TARGET_SECTOR, cardKey)
            if (!authenticated) {
                tvStatus.text = getString(R.string.auth_failed)
                setButtonsEnabled(false)
                return
            }

            val blockIndex = mifare.sectorToBlock(TARGET_SECTOR) + DATA_BLOCK_OFFSET
            val data = mifare.readBlock(blockIndex)

            currentBalance = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            tvBalance.text = getString(R.string.balance_format, currentBalance)
            tvStatus.text = getString(R.string.card_read_ok)
            setButtonsEnabled(true)

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_reading, e.message)
            setButtonsEnabled(false)
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Coin deduction
    // -------------------------------------------------------------------------

    private fun deductCoins(amount: Int) {
        val tag = currentTag ?: run {
            tvStatus.text = getString(R.string.no_card)
            return
        }

        if (currentBalance < amount) {
            tvStatus.text = getString(R.string.insufficient_balance)
            return
        }

        val uid = tag.id
        val cardKey = deriveCardKey(uid)
        val newBalance = currentBalance - amount

        val mifare = MifareClassic.get(tag) ?: run {
            tvStatus.text = getString(R.string.error_get_mifare)
            return
        }

        try {
            mifare.connect()

            val authenticated = mifare.authenticateSectorWithKeyA(TARGET_SECTOR, cardKey)
            if (!authenticated) {
                tvStatus.text = getString(R.string.auth_failed)
                setButtonsEnabled(false)
                return
            }

            val block = ByteArray(MifareClassic.BLOCK_SIZE)
            block[0] = ((newBalance shr 8) and 0xFF).toByte()
            block[1] = (newBalance and 0xFF).toByte()

            val blockIndex = mifare.sectorToBlock(TARGET_SECTOR) + DATA_BLOCK_OFFSET
            mifare.writeBlock(blockIndex, block)

            currentBalance = newBalance
            tvBalance.text = getString(R.string.balance_format, currentBalance)
            tvStatus.text = getString(R.string.deduct_ok, amount)
            Toast.makeText(this, getString(R.string.deduct_ok, amount), Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            tvStatus.text = getString(R.string.error_writing, e.message)
        } finally {
            runCatching { mifare.close() }
        }
    }

    // -------------------------------------------------------------------------
    // Key derivation: HMAC-SHA1(PSK, UID) → first 6 bytes
    // -------------------------------------------------------------------------

    private fun deriveCardKey(uid: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA1")
            val secretKey = SecretKeySpec(PSK.toByteArray(Charsets.UTF_8), "HmacSHA1")
            mac.init(secretKey)
            mac.doFinal(uid).take(KEY_LEN).toByteArray()
        } catch (e: Exception) {
            // Fallback: SHA-256(PSK + UID) first 6 bytes
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(PSK.toByteArray(Charsets.UTF_8))
            digest.update(uid)
            digest.digest().take(KEY_LEN).toByteArray()
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setButtonsEnabled(enabled: Boolean) {
        btnDeduct1.isEnabled = enabled
        btnDeduct2.isEnabled = enabled
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02X".format(it) }
}
