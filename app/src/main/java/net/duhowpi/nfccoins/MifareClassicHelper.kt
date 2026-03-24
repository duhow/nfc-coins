package net.duhowpi.nfccoins

import android.nfc.tech.MifareClassic
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Low-level helpers for Mifare Classic NFC operations.
 *
 * Encapsulates value-block encoding, key derivation, sector-trailer
 * construction, and raw sector I/O so that the Activity layer stays
 * technology-agnostic and future tag types (e.g. NTAG) can plug in
 * with a parallel helper.
 */
object MifareClassicHelper {

    // -------------------------------------------------------------------------
    // Block offsets inside the target sector
    // -------------------------------------------------------------------------

    const val DATA_BLOCK_OFFSET = 0
    const val TX_BLOCK_1_OFFSET = 1
    const val TX_BLOCK_2_OFFSET = 2

    // -------------------------------------------------------------------------
    // Key constants
    // -------------------------------------------------------------------------

    const val KEY_LEN = 6

    // -------------------------------------------------------------------------
    // Balance limits
    // -------------------------------------------------------------------------

    const val MAX_BALANCE = 0xFFFF

    // -------------------------------------------------------------------------
    // Default GPB (General Purpose Byte) in sector trailer
    // -------------------------------------------------------------------------

    const val DEFAULT_USER_BYTE = 0x69

    // -------------------------------------------------------------------------
    // Key helpers
    // -------------------------------------------------------------------------

    fun hexKey(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    /** Standard NFC keys to try when formatting (factory and NDEF). */
    val STANDARD_KEYS: List<ByteArray> = listOf(
        hexKey(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF), // Factory (default)
        hexKey(0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5), // NDEF Key A
        hexKey(0xD3, 0xF7, 0xD3, 0xF7, 0xD3, 0xF7), // NDEF data
        hexKey(0x00, 0x00, 0x00, 0x00, 0x00, 0x00), // All zeros
        hexKey(0x4D, 0x3A, 0x99, 0xC3, 0x51, 0xDD), // NDEF Key B common
        hexKey(0x1A, 0x98, 0x2C, 0x7E, 0x45, 0x9A), // MAD key
        hexKey(0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5), // Key B common
    )

    /** Mifare Classic factory key (all bytes 0xFF). */
    val FACTORY_KEY: ByteArray = hexKey(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)

    // -------------------------------------------------------------------------
    // Access bits
    // -------------------------------------------------------------------------

    /** Standard access bits: read and write with Key A on all data blocks (condition 000),
     *  trailer condition 001. */
    val ACCESS_BITS: ByteArray = hexKey(0xFF, 0x07, 0x80, DEFAULT_USER_BYTE)

    /** Control bytes (3) for the standard access bits. */
    val ACCESS_BITS_STANDARD_CTRL: ByteArray = hexKey(0xFF, 0x07, 0x80)

    /** Control bytes (3) for the single-recharge restricted access bits:
     *  Block 0 condition 001: read+decrement allowed, write+increment blocked.
     *  Blocks 1,2 condition 000 (open); Trailer condition 001 (same as standard). */
    val ACCESS_BITS_SINGLE_RECHARGE_CTRL: ByteArray = hexKey(0xFF, 0x06, 0x90)

    // -------------------------------------------------------------------------
    // Value Block operations (Mifare Classic 16-byte format, little-endian)
    // -------------------------------------------------------------------------

    /** Bitwise inverse of a single byte. */
    private fun Byte.inv8(): Byte = (this.toInt() xor 0xFF).toByte()

    /**
     * Creates a 16-byte Mifare Classic Value Block for [value].
     *
     * Layout: value(4B LE) | ~value(4B) | value(4B) | addr ~addr addr ~addr
     *
     * The address field is set to [DATA_BLOCK_OFFSET] (the block's position
     * within its sector), which is the conventional choice.
     */
    fun makeValueBlock(value: Int): ByteArray {
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
    fun readValueBlock(data: ByteArray): Int? {
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

    // -------------------------------------------------------------------------
    // Sector trailer
    // -------------------------------------------------------------------------

    /**
     * Builds a 16-byte Mifare Classic sector trailer block:
     *   [Key A (6 bytes)] [Access bits (4 bytes)] [Key B (6 bytes)]
     *
     * When [standard] is true, uses the standard access bits (FF 07 80) which allow
     * increment and write on all data blocks. When false, uses the single-recharge
     * restricted bits (FF 06 90) which block increment and write on block 0.
     * The [userByte] (GPB, General Purpose Byte) is placed at position 3 of the access bits.
     */
    fun buildSectorTrailer(key: ByteArray, standard: Boolean, userByte: Int): ByteArray {
        val ctrlBytes = if (standard) ACCESS_BITS_STANDARD_CTRL
                        else          ACCESS_BITS_SINGLE_RECHARGE_CTRL
        val trailer = ByteArray(MifareClassic.BLOCK_SIZE)
        System.arraycopy(key, 0, trailer, 0, KEY_LEN)
        System.arraycopy(ctrlBytes, 0, trailer, KEY_LEN, ctrlBytes.size)
        trailer[KEY_LEN + ctrlBytes.size] = userByte.toByte()
        System.arraycopy(key, 0, trailer, KEY_LEN + ctrlBytes.size + 1, KEY_LEN)
        return trailer
    }

    // -------------------------------------------------------------------------
    // Key derivation
    // -------------------------------------------------------------------------

    /**
     * Converts a hex string (with optional spaces and ':') into a [KEY_LEN]-byte array,
     * or null if the string is not valid hex of the right length.
     */
    fun tryParseHexKey(raw: String): ByteArray? {
        val hex = raw.replace(" ", "").replace(":", "").uppercase()
        if (hex.length != KEY_LEN * 2) return null
        return try {
            ByteArray(KEY_LEN) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /**
     * Derives the authentication key for a Mifare Classic card.
     *
     * In dynamic mode: HMAC-SHA1(PSK, UID) → first [KEY_LEN] bytes.
     * In static mode: tries to parse [psk] as hex; otherwise SHA-256(PSK) → first [KEY_LEN] bytes.
     */
    fun deriveCardKey(uid: ByteArray, psk: String, useDynamic: Boolean): ByteArray {
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
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                digest.digest(psk.toByteArray(Charsets.UTF_8)).take(KEY_LEN).toByteArray()
            }
        } catch (_: Exception) {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            digest.update(psk.toByteArray(Charsets.UTF_8))
            if (useDynamic) digest.update(uid)
            digest.digest().take(KEY_LEN).toByteArray()
        }
    }

    // -------------------------------------------------------------------------
    // Sector I/O
    // -------------------------------------------------------------------------

    /**
     * Holds the raw data read from a Mifare Classic sector.
     */
    data class SectorData(
        val sectorStart: Int,
        val blocksInSector: Int,
        val counterData: ByteArray,
        val txBlock1: ByteArray,
        val txBlock2: ByteArray,
        val trailerData: ByteArray,
        val txBlock: TransactionBlock
    )

    /**
     * Authenticates with Key A and reads the data blocks and trailer from [sector].
     * Returns the parsed [SectorData] or `null` if authentication fails.
     * The caller must ensure [mifare] is already connected.
     */
    fun readSector(mifare: MifareClassic, sector: Int, cardKey: ByteArray): SectorData? {
        if (!mifare.authenticateSectorWithKeyA(sector, cardKey)) return null
        val sectorStart = mifare.sectorToBlock(sector)
        val blocksInSector = mifare.getBlockCountInSector(sector)
        val counterData = mifare.readBlock(sectorStart + DATA_BLOCK_OFFSET)
        val txBlock1    = mifare.readBlock(sectorStart + TX_BLOCK_1_OFFSET)
        val txBlock2    = mifare.readBlock(sectorStart + TX_BLOCK_2_OFFSET)
        val trailerData = mifare.readBlock(sectorStart + blocksInSector - 1)
        val txBlock     = TransactionBlock.fromBytes(txBlock1, txBlock2)
        return SectorData(sectorStart, blocksInSector, counterData, txBlock1, txBlock2, trailerData, txBlock)
    }

    /**
     * Returns the age byte stored in the GPB position of the sector trailer.
     */
    fun getAgeByte(trailerData: ByteArray): Int = trailerData[KEY_LEN + 3].toInt() and 0xFF

    /**
     * Checks whether the sector trailer has single-recharge restricted access bits.
     */
    fun isSingleRecharge(trailerData: ByteArray): Boolean =
        trailerData[KEY_LEN] == ACCESS_BITS_SINGLE_RECHARGE_CTRL[0] &&
        trailerData[KEY_LEN + 1] == ACCESS_BITS_SINGLE_RECHARGE_CTRL[1] &&
        trailerData[KEY_LEN + 2] == ACCESS_BITS_SINGLE_RECHARGE_CTRL[2]
}

// -------------------------------------------------------------------------
// Shared extension (usable by any card technology)
// -------------------------------------------------------------------------

/** Converts a byte array to its hexadecimal string representation. */
fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }
