package net.duhowpi.nfccoins

import android.content.Context
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
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
 * val card = BaseCoinCard.fromTag(tag) ?: return
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
     * [checksum] carries the stored integrity bytes for
     * integrity verification ([isDataValid]) and debug display.
     */
    data class CardData(
        val balance: Int = 0,
        /** Parsed transaction history domain model. */
        val transactions: TransactionBlock = TransactionBlock(),
        /** Stored checksum bytes (4 B) from transaction data. */
        val checksum: ByteArray = ByteArray(4),
        /** User birth year decoded from trailer GPB byte (e.g. 2005). */
        val userBirthYear: Int = 2005, // x69
        val isSingleRecharge: Boolean = false
    ) {
        /** Birth year encoded to GPB user byte. */
        val userBirthByte: Byte
            get() = (userBirthYear - 1900).coerceIn(0, 255).toByte()

        /** Rebuilds the full 32-byte transaction block using [transactions] + [checksum]. */
        val transactionsDataWithChecksum: ByteArray
            get() = transactions.toBytes + checksum
    }

    /**
     * Snapshot of the intended card state taken just before a write.
     *
     * If the write is interrupted (card removed mid-operation) the data stored
     * here lets the next tap retry the write instead of flagging the card as
     * tampered.
     */
    data class PendingWriteData(
        val uid: ByteArray,
        val balanceData: ByteArray,
        val transactions: ByteArray
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
            /** Init timestamp written during format (Unix seconds). */
            val formattedAtSeconds: Long,
            /** Raw transaction payload bytes written to card (32 B contiguous). */
            val transactionsData: ByteArray
        ) : FormatResult()

        /** Card formatted for the first time with a derived key. */
        data class NewlyFormatted(
            /** Init timestamp written during format (Unix seconds). */
            val formattedAtSeconds: Long,
            /** Raw transaction payload bytes written to card (32 B contiguous). */
            val transactionsData: ByteArray,
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
        * updated transaction payload.
     */
    abstract fun deductBalance(amount: Int, newTransactions: ByteArray)

    /**
     * Adds [amount] to the on-card balance and writes the updated transaction
     * payload.
     */
    abstract fun addBalance(amount: Int, newTransactions: ByteArray)

    /**
     * Temporarily unlocks recharge for single-recharge cards.
     * For technologies without this concept it can be a no-op.
     */
    abstract fun unlockRecharge(cardData: CardData)

    /**
     * Re-applies recharge lock for single-recharge cards.
     * For technologies without this concept it can be a no-op.
     */
    abstract fun lockRecharge(cardData: CardData)

    /**
     * Formats (or re-formats) the card, setting the balance to zero and
     * writing a fresh transaction block with a new timestamp.
     */
    abstract fun formatCard(formatOptions: CardData): FormatResult

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
        data.transactions.isValid(
            encodeBalance(data.balance),
            data.transactionsDataWithChecksum,
            uid,
            psk
        )

    /**
     * Returns the (stored, computed) checksum pair for debug display.
     */
    fun extractChecksums(
        balance: Int,
        txData: ByteArray
    ): Pair<ByteArray, ByteArray> =
        TransactionBlock.extractChecksums(encodeBalance(balance), txData, uid, psk)

    /**
    * Builds updated transaction payload after a balance operation.
    * Returns (updated model, serialised contiguous payload).
     */
    fun buildUpdatedTxBlocks(
        transactions: TransactionBlock,
        newBalanceData: ByteArray,
        operation: TxOperation,
        amount: Int
    ): Pair<TransactionBlock, ByteArray> {
        val nowSecs = System.currentTimeMillis() / 1000L
        val updated = transactions.addTransaction(nowSecs, operation, amount)
        val updatedTransactions = updated.toBytesWithChecksum(newBalanceData, uid, psk)
        return updated to updatedTransactions
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    companion object {
        /** Factory options needed to construct technology-specific card classes. */
        data class FactoryOptions(
            val psk: String,
            val mifareClassic: MifareClassicOptions = MifareClassicOptions(),
            val ntag: NtagOptions = NtagOptions()
        )

        /** Mifare Classic specific options. */
        data class MifareClassicOptions(
            val sector: Int = AdvancedSettingsActivity.DEFAULT_SECTOR,
            val useDynamic: Boolean = true
        )

        /**
         * Placeholder for future NTAG-specific options.
         *
         * Keeping this type in the base factory avoids changing call sites
         * when NTAG support is added.
         */
        data class NtagOptions(
            val userMemoryStartPage: Int = 4,
            val reservedConfigAndLockPages: Int = 5,
            val freeTailPages: Int = 10
        )

        private data class CardCreator(
            val supports: (Tag) -> Boolean,
            val create: (Tag, FactoryOptions) -> BaseCoinCard?
        )

        private val creators: List<CardCreator> = listOf(
            CardCreator(
                supports = { tag -> tag.techList.contains(MifareClassic::class.java.name) },
                create = { tag, options ->
                    runCatching {
                        MifareClassicCoinCard(
                            tag = tag,
                            sector = options.mifareClassic.sector,
                            psk = options.psk,
                            useDynamic = options.mifareClassic.useDynamic
                        )
                    }.getOrNull()
                }
            ),
            CardCreator(
                supports = { tag ->
                    tag.techList.contains(NfcA::class.java.name) &&
                    !tag.techList.contains(MifareClassic::class.java.name)
                },
                create = { tag, options ->
                    runCatching {
                        Ntag(
                            tag = tag,
                            psk = options.psk,
                            options = options.ntag
                        )
                    }.getOrNull()
                }
            )
        )

        /**
         * Creates the appropriate [BaseCoinCard] subclass for [tag] using built-in defaults.
         */
        fun fromTag(tag: Tag): BaseCoinCard? {
            val options = FactoryOptions(
                psk = BuildConfig.NFC_PSK,
                mifareClassic = MifareClassicOptions(
                    sector = AdvancedSettingsActivity.DEFAULT_SECTOR,
                    useDynamic = true
                )
            )
            return fromTag(tag, options)
        }

        /**
         * Creates the appropriate [BaseCoinCard] subclass for [tag] using
         * values from [AdvancedSettingsActivity].
         */
        fun fromTag(tag: Tag, context: Context): BaseCoinCard? {
            val options = FactoryOptions(
                psk = AdvancedSettingsActivity.getStaticKey(context),
                mifareClassic = MifareClassicOptions(
                    sector = AdvancedSettingsActivity.getTargetSector(context),
                    useDynamic = AdvancedSettingsActivity.isDynamicKeyEnabled(context)
                )
            )
            return fromTag(tag, options)
        }

        /**
         * Returns `true` when [tag] is a card type this app can work with.
         */
        fun isSupported(tag: Tag): Boolean =
            creators.any { it.supports(tag) }

        /**
         * Creates the appropriate [BaseCoinCard] subclass for [tag], or `null`
         * when the tag technology is not supported or cannot be obtained.
         *
         * [options] contains shared settings ([FactoryOptions.psk]) and optional
         * per-technology settings.
         */
        fun fromTag(
            tag: Tag,
            options: FactoryOptions
        ): BaseCoinCard? =
            creators.firstOrNull { it.supports(tag) }?.create?.invoke(tag, options)
    }
}
