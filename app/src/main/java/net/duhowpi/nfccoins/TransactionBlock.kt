package net.duhowpi.nfccoins

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Operation types stored in transaction records. */
enum class TxOperation(val code: Byte) {
    ADD(0x01),
    SUBTRACT(0x02);

    companion object {
        fun fromByte(b: Byte): TxOperation? = entries.firstOrNull { it.code == b }
    }
}

/** A single transaction record (6 bytes). */
data class TransactionEntry(
    /** Seconds elapsed since [TransactionBlock.initTimestamp]; stored as uint24. */
    val secondsOffset: Int,
    val operation: TxOperation,
    /** Transaction amount (uint16). */
    val amount: Int
)

/**
 * Transaction history stored in 2 consecutive Mifare Classic data blocks (32 bytes total).
 *
 * Memory layout across block1 (16 B) and block2 (16 B):
 *
 *   Byte  0– 3 : Init timestamp (uint32 big-endian, Unix seconds)
 *   Byte  4– 9 : Transaction 0  (3 B seconds offset | 1 B op | 2 B amount)
 *   Byte 10–15 : Transaction 1
 *   Byte 16–21 : Transaction 2
 *   Byte 22–27 : Transaction 3
 *   Byte 28–31 : HMAC-SHA256 checksum (first 4 bytes), keyed with PSK+UID,
 *                covering: counterBlock (16 B) + bytes 0–27 of this structure
 *
 * The checksum binds the counter block and the transaction history to the card's UID
 * and the application PSK, so any out-of-app modification is detectable.
 */
class TransactionBlock(
    /** Unix timestamp (seconds) when the card was initialised; 0 = not initialised. */
    val initTimestamp: Long,
    /** Up to [MAX_TRANSACTIONS] entries; oldest first. */
    val transactions: List<TransactionEntry> = emptyList()
) {

    companion object {
        const val MAX_TRANSACTIONS = 4

        /** Bytes used for timestamp + 4 transactions (before the checksum). */
        private const val TX_PAYLOAD_SIZE = 28  // 4 + 4 * 6
        private const val CHECKSUM_SIZE = 4
        private const val TOTAL_SIZE = TX_PAYLOAD_SIZE + CHECKSUM_SIZE  // 32 = 2 * 16

        /** Maximum value for the 24-bit seconds-offset field (≈ 194 days). */
        private const val MAX_SECONDS_OFFSET = 0xFF_FFFFL

        /**
         * Deserialises two 16-byte blocks into a [TransactionBlock].
         * Invalid or empty transaction slots are silently ignored.
         */
        fun fromBytes(block1: ByteArray, block2: ByteArray): TransactionBlock {
            require(block1.size == 16 && block2.size == 16)
            val data = block1 + block2  // 32 bytes

            val ts = ((data[0].toLong() and 0xFF) shl 24) or
                     ((data[1].toLong() and 0xFF) shl 16) or
                     ((data[2].toLong() and 0xFF) shl 8) or
                     (data[3].toLong() and 0xFF)

            val txList = mutableListOf<TransactionEntry>()
            for (i in 0 until MAX_TRANSACTIONS) {
                val base = 4 + i * 6
                val secOffset = ((data[base].toInt() and 0xFF) shl 16) or
                                ((data[base + 1].toInt() and 0xFF) shl 8) or
                                (data[base + 2].toInt() and 0xFF)
                val op = TxOperation.fromByte(data[base + 3]) ?: continue
                val amount = ((data[base + 4].toInt() and 0xFF) shl 8) or
                             (data[base + 5].toInt() and 0xFF)
                if (secOffset > 0 || amount > 0) {
                    txList.add(TransactionEntry(secOffset, op, amount))
                }
            }

            return TransactionBlock(ts, txList)
        }

        /**
         * Computes the 4-byte checksum that authenticates the counter block and the
         * transaction payload against the card's UID and the application PSK.
         *
         * checksum = HMAC-SHA256(key = PSK.utf8 + UID,
         *                        msg = counterBlock + txPayload)[0..3]
         */
        fun computeChecksum(
            counterBlock: ByteArray,
            txPayload: ByteArray,
            uid: ByteArray,
            psk: String
        ): ByteArray {
            require(counterBlock.size == 16)
            require(txPayload.size == TX_PAYLOAD_SIZE)
            return try {
                val mac = Mac.getInstance("HmacSHA256")
                val keyMaterial = psk.toByteArray(Charsets.UTF_8) + uid
                mac.init(SecretKeySpec(keyMaterial, "HmacSHA256"))
                mac.update(counterBlock)
                mac.update(txPayload)
                mac.doFinal().copyOf(CHECKSUM_SIZE)
            } catch (e: java.security.GeneralSecurityException) {
                // Fallback: SHA-256(PSK + UID + counterBlock + txPayload)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                digest.update(psk.toByteArray(Charsets.UTF_8))
                digest.update(uid)
                digest.update(counterBlock)
                digest.update(txPayload)
                digest.digest().copyOf(CHECKSUM_SIZE)
            }
        }

        /**
         * Returns the (storedChecksum, computedChecksum) pair for debugging.
         * [storedChecksum] is the 4-byte value embedded in [block1]+[block2];
         * [computedChecksum] is freshly calculated from the given inputs.
         */
        fun extractChecksums(
            counterBlock: ByteArray,
            block1: ByteArray,
            block2: ByteArray,
            uid: ByteArray,
            psk: String
        ): Pair<ByteArray, ByteArray> {
            require(block1.size == 16 && block2.size == 16)
            val data = block1 + block2
            val payload = data.copyOfRange(0, TX_PAYLOAD_SIZE)
            val stored = data.copyOfRange(TX_PAYLOAD_SIZE, TOTAL_SIZE)
            val computed = computeChecksum(counterBlock, payload, uid, psk)
            return stored to computed
        }
    }

    /**
     * Serialises this block to two 16-byte arrays (block1, block2) ready to write to the card,
     * including a freshly computed checksum.
     */
    fun toBytes(counterBlock: ByteArray, uid: ByteArray, psk: String): Pair<ByteArray, ByteArray> {
        val payload = buildPayload()
        val checksum = computeChecksum(counterBlock, payload, uid, psk)
        val full = payload + checksum  // 32 bytes
        return full.copyOfRange(0, 16) to full.copyOfRange(16, 32)
    }

    /**
     * Returns `true` when the checksum stored in [block1]+[block2] matches the expected value
     * computed from [counterBlock], the card [uid], and the application [psk].
     */
    fun isValid(
        counterBlock: ByteArray,
        block1: ByteArray,
        block2: ByteArray,
        uid: ByteArray,
        psk: String
    ): Boolean {
        require(block1.size == 16 && block2.size == 16)
        val data = block1 + block2
        val payload = data.copyOfRange(0, TX_PAYLOAD_SIZE)
        val stored = data.copyOfRange(TX_PAYLOAD_SIZE, TOTAL_SIZE)
        val expected = computeChecksum(counterBlock, payload, uid, psk)
        return stored.contentEquals(expected)
    }

    /**
     * Returns a new [TransactionBlock] with [entry] appended.
     * If the list is already at [MAX_TRANSACTIONS], the oldest entry is dropped.
     */
    fun addTransaction(nowSeconds: Long, operation: TxOperation, amount: Int): TransactionBlock {
        val offset = (nowSeconds - initTimestamp).coerceIn(0L, MAX_SECONDS_OFFSET).toInt()
        val newList = (transactions + TransactionEntry(offset, operation, amount))
            .takeLast(MAX_TRANSACTIONS)
        return TransactionBlock(initTimestamp, newList)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildPayload(): ByteArray {
        val buf = ByteArray(TX_PAYLOAD_SIZE)
        buf[0] = ((initTimestamp shr 24) and 0xFF).toByte()
        buf[1] = ((initTimestamp shr 16) and 0xFF).toByte()
        buf[2] = ((initTimestamp shr 8) and 0xFF).toByte()
        buf[3] = (initTimestamp and 0xFF).toByte()

        val slots = transactions.takeLast(MAX_TRANSACTIONS)
        for (i in slots.indices) {
            val base = 4 + i * 6
            val tx = slots[i]
            buf[base]     = ((tx.secondsOffset shr 16) and 0xFF).toByte()
            buf[base + 1] = ((tx.secondsOffset shr 8) and 0xFF).toByte()
            buf[base + 2] = (tx.secondsOffset and 0xFF).toByte()
            buf[base + 3] = tx.operation.code
            buf[base + 4] = ((tx.amount shr 8) and 0xFF).toByte()
            buf[base + 5] = (tx.amount and 0xFF).toByte()
        }
        return buf
    }
}
