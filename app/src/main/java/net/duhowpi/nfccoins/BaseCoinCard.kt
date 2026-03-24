package net.duhowpi.nfccoins

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import java.io.Closeable

/**
 * Technology-agnostic abstraction for NFC coin-card operations.
 *
 * Each concrete subclass (e.g. [MifareClassicCoinCard]) encapsulates
 * all hardware-specific details (authentication, block layout, atomic
 * operations) behind this common interface so the Activity layer can
 * work with any supported card type without technology-specific branches.
 *
 * Typical lifecycle inside an Activity operation method:
 * ```
 * val card = BaseCoinCard.fromTag(tag, sector, psk, useDynamic) ?: return
 * try {
 *     card.connect()
 *     val result = card.readCardData()
 *     // … use result …
 * } finally {
 *     card.close()
 * }
 * ```
 */
abstract class BaseCoinCard(val tag: Tag, protected val psk: String) : Closeable {

    val uid: ByteArray = tag.id

    /** Maximum balance value this card type can hold. */
    abstract val maxBalance: Int

    // -------------------------------------------------------------------------
    // Data classes
    // -------------------------------------------------------------------------

    /**
     * All coin-relevant data read from the card in a single tap.
     *
     * [counterData], [txBlock1], and [txBlock2] carry the raw bytes for
     * integrity verification ([isDataValid]) and debug display.
     */
    data class CardData(
        val balance: Int,
        val txBlock: TransactionBlock,
        val ageByte: Int,
        val isSingleRecharge: Boolean,
        /** Raw counter / value block bytes as read from the card. */
        val counterData: ByteArray,
        /** Raw transaction block 1 bytes. */
        val txBlock1: ByteArray,
        /** Raw transaction block 2 bytes. */
        val txBlock2: ByteArray
    )

    /**
     * Snapshot of the intended card state taken just before a write.
     *
     * If the write is interrupted (card removed mid-operation) the data stored
     * here lets the next tap retry the write instead of flagging the card as
     * tampered.
     */
    data class PendingWriteData(
        val uid: ByteArray,
        val counterBlock: ByteArray,
        val txBlock1: ByteArray,
        val txBlock2: ByteArray
    ) {
        fun matchesUid(other: ByteArray): Boolean = uid.contentEquals(other)
    }

    // -------------------------------------------------------------------------
    // Result types
    // -------------------------------------------------------------------------

    /** Result of a [readCardData] call. */
    sealed class ReadResult {
        data class Success(val data: CardData) : ReadResult()
        /** Card authentication failed (wrong key / unformatted card). */
        object AuthFailed : ReadResult()
        /** Card data could not be parsed (e.g. invalid value block). */
        data class InvalidData(val reason: String) : ReadResult()
    }

    /** Result of a [formatCard] call. */
    sealed class FormatResult {
        /** Card was already formatted; balance has been reset to zero. */
        data class Reformatted(
            val oldBalance: Int,
            val txBlock: TransactionBlock,
            val counterData: ByteArray,
            val txB1: ByteArray,
            val txB2: ByteArray
        ) : FormatResult()

        /** Card formatted for the first time with a derived key. */
        data class NewlyFormatted(
            val txBlock: TransactionBlock,
            val counterData: ByteArray,
            val txB1: ByteArray,
            val txB2: ByteArray,
            /** Key type used (e.g. "A"/"B"), or null when not applicable. */
            val foundKeyType: String? = null,
            /** Hex string of the standard key that was found. */
            val foundKeyHex: String? = null,
            /** Hex string of the new derived key written to the card. */
            val newKeyHex: String? = null
        ) : FormatResult()

        /** No usable key was found on the card. */
        object NoKeyFound : FormatResult()

        /** Re-authentication failed during format. */
        object AuthFailed : FormatResult()
    }

    // -------------------------------------------------------------------------
    // Abstract operations
    // -------------------------------------------------------------------------

    /** Opens a connection to the card hardware. */
    abstract fun connect()

    /**
     * Authenticates and reads balance, transactions, and metadata from the card.
     *
     * Must be called after [connect]. Returns a [ReadResult] describing the
     * outcome; [ReadResult.Success] carries the parsed [CardData].
     */
    abstract fun readCardData(): ReadResult

    /**
     * Encodes [value] into the card's native counter / balance format.
     *
     * For Mifare Classic this produces a 16-byte Value Block; future card
     * types may use a different encoding.
     */
    abstract fun encodeBalance(value: Int): ByteArray

    /**
     * Writes the previously intended state stored in [pending] back to
     * the card, recovering from an interrupted write.
     */
    abstract fun retryPendingWrite(pending: PendingWriteData)

    /**
     * Atomically deducts [amount] from the on-card balance and writes the
     * updated transaction blocks.
     */
    abstract fun deductBalance(amount: Int, newTxBlock1: ByteArray, newTxBlock2: ByteArray)

    /**
     * Adds [amount] to the on-card balance and writes the updated transaction
     * blocks. For cards with single-recharge restrictions the implementation
     * handles the unlock / relock dance transparently.
     */
    abstract fun addBalance(
        amount: Int,
        cardData: CardData,
        newTxBlock1: ByteArray,
        newTxBlock2: ByteArray
    )

    /**
     * Formats (or re-formats) the card, setting the balance to zero and
     * writing a fresh transaction block with a new timestamp.
     */
    abstract fun formatCard(singleRecharge: Boolean, ageByte: Int): FormatResult

    /**
     * Resets the card to factory defaults (clears data, restores factory key).
     * Returns `true` on success, `false` when no usable key was found.
     */
    abstract fun resetCard(): Boolean

    // -------------------------------------------------------------------------
    // Common helpers (technology-agnostic)
    // -------------------------------------------------------------------------

    /**
     * Verifies the HMAC checksum stored on the card against the expected
     * value computed from [data]'s raw blocks, the card [uid], and the
     * application [psk].
     */
    fun isDataValid(data: CardData): Boolean =
        data.txBlock.isValid(data.counterData, data.txBlock1, data.txBlock2, uid, psk)

    /**
     * Returns the (stored, computed) checksum pair for debug display.
     */
    fun extractChecksums(
        counterData: ByteArray,
        txBlock1: ByteArray,
        txBlock2: ByteArray
    ): Pair<ByteArray, ByteArray> =
        TransactionBlock.extractChecksums(counterData, txBlock1, txBlock2, uid, psk)

    /**
     * Builds updated transaction blocks after a balance operation.
     *
     * Returns a [Triple] of (updatedTxBlock, serialised block 1, serialised block 2).
     */
    fun buildUpdatedTxBlocks(
        txBlock: TransactionBlock,
        newCounterBlock: ByteArray,
        operation: TxOperation,
        amount: Int
    ): Triple<TransactionBlock, ByteArray, ByteArray> {
        val nowSecs = System.currentTimeMillis() / 1000L
        val updated = txBlock.addTransaction(nowSecs, operation, amount)
        val (b1, b2) = updated.toBytes(newCounterBlock, uid, psk)
        return Triple(updated, b1, b2)
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    companion object {
        /**
         * Returns `true` when [tag] is a card type this app can work with.
         */
        fun isSupported(tag: Tag): Boolean =
            tag.techList.contains(MifareClassic::class.java.name)
            // Future: || tag.techList.contains(NfcA::class.java.name) for NTAG

        /**
         * Creates the appropriate [BaseCoinCard] subclass for [tag], or `null`
         * when the tag technology is not supported or cannot be obtained.
         *
         * [sector] is the target sector index (Mifare Classic only; ignored by
         * other card types).
         */
        fun fromTag(
            tag: Tag,
            sector: Int,
            psk: String,
            useDynamic: Boolean
        ): BaseCoinCard? {
            if (tag.techList.contains(MifareClassic::class.java.name)) {
                val mifare = MifareClassic.get(tag) ?: return null
                return MifareClassicCoinCard(tag, mifare, sector, psk, useDynamic)
            }
            // Future: NTAG, NFC Forum Type 2/4, etc.
            return null
        }
    }
}
