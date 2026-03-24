package net.duhowpi.nfccoins

import android.nfc.Tag
import android.nfc.tech.MifareClassic

/**
 * [BaseCoinCard] implementation for Mifare Classic tags.
 *
 * Delegates low-level encoding and trailer construction to
 * [MifareClassicHelper] and wraps [MifareClassic] hardware calls
 * (authentication, value-block increment/decrement, sector reads/writes).
 */
class MifareClassicCoinCard(
    tag: Tag,
    private val sector: Int,
    psk: String,
    useDynamic: Boolean
) : BaseCoinCard(tag, psk) {

    val mifare: MifareClassic = requireNotNull(MifareClassic.get(tag)) {
        "MifareClassic tech unavailable for this tag"
    }

    override val maxBalance: Int = MifareClassicHelper.MAX_BALANCE

    private val cardKey: ByteArray = MifareClassicHelper.deriveCardKey(uid, psk, useDynamic)

    // Sector layout cached after the first successful read.
    private var sectorStart: Int = 0
    private var blocksInSector: Int = 4

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    override fun connect() = mifare.connect()

    override fun close() {
        runCatching { mifare.close() }
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    override fun readCardData(): ReadResult {
        val sd = MifareClassicHelper.readSector(mifare, sector, cardKey)
            ?: return ReadResult.AuthFailed

        // Cache sector geometry for subsequent write operations.
        sectorStart = sd.sectorStart
        blocksInSector = sd.blocksInSector

        val balance = MifareClassicHelper.readValueBlock(sd.counterData)
            ?: return ReadResult.InvalidData("invalid value block")

        return ReadResult.Success(
            CardData(
                balance = balance,
                checksum = sd.transactions.copyOfRange(28, 32),
                transactions = TransactionBlock.fromBytes(sd.transactions),
                userBirthYear = MifareClassicHelper.getUserBirthYear(sd.trailerData),
                isSingleRecharge = MifareClassicHelper.isSingleRecharge(sd.trailerData)
            )
        )
    }

    // -------------------------------------------------------------------------
    // Balance encoding
    // -------------------------------------------------------------------------

    override fun encodeBalance(value: Int): ByteArray =
        MifareClassicHelper.makeValueBlock(value)

    // -------------------------------------------------------------------------
    // Write recovery
    // -------------------------------------------------------------------------

    override fun retryPendingWrite(pending: PendingWriteData) {
        val (txBlock1, txBlock2) = TransactionBlock.toMifareBlocks(pending.transactions)
        mifare.writeBlock(sectorStart + MifareClassicHelper.DATA_BLOCK_OFFSET, pending.counterBlock)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_1_OFFSET, txBlock1)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_2_OFFSET, txBlock2)
    }

    // -------------------------------------------------------------------------
    // Deduct
    // -------------------------------------------------------------------------

    override fun deductBalance(amount: Int, newTransactions: ByteArray) {
        val (newTxBlock1, newTxBlock2) = TransactionBlock.toMifareBlocks(newTransactions)
        val blockIndex = sectorStart + MifareClassicHelper.DATA_BLOCK_OFFSET
        mifare.decrement(blockIndex, amount)
        mifare.transfer(blockIndex)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_1_OFFSET, newTxBlock1)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_2_OFFSET, newTxBlock2)
    }

    // -------------------------------------------------------------------------
    // Add
    // -------------------------------------------------------------------------

    override fun addBalance(amount: Int, newTransactions: ByteArray) {
        val (newTxBlock1, newTxBlock2) = TransactionBlock.toMifareBlocks(newTransactions)

        val blockIndex = sectorStart + MifareClassicHelper.DATA_BLOCK_OFFSET
        mifare.increment(blockIndex, amount)
        mifare.transfer(blockIndex)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_1_OFFSET, newTxBlock1)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_2_OFFSET, newTxBlock2)
    }

    override fun unlockRecharge(cardData: CardData) {
        val trailerIdx = sectorStart + blocksInSector - 1
        val openTrailer = MifareClassicHelper.buildSectorTrailer(
            cardKey, standard = true, userByte = cardData.userBirthByte
        )
        mifare.writeBlock(trailerIdx, openTrailer)
    }

    override fun lockRecharge(cardData: CardData) {
        val trailerIdx = sectorStart + blocksInSector - 1
        val restrictedTrailer = MifareClassicHelper.buildSectorTrailer(
            cardKey, standard = false, userByte = cardData.userBirthByte
        )
        mifare.writeBlock(trailerIdx, restrictedTrailer)
    }

    // -------------------------------------------------------------------------
    // Format
    // -------------------------------------------------------------------------

    override fun formatCard(formatOptions: CardData): FormatResult {
        val singleRecharge = formatOptions.isSingleRecharge
        val userBirthYear = formatOptions.userBirthYear
        // Path 1: already formatted with the derived key → re-format (reset balance).
        if (mifare.authenticateSectorWithKeyA(sector, cardKey)) {
            return reformatExistingCard(singleRecharge, userBirthYear)
        }

        // Path 2: search for a standard key → format from scratch.
        return formatNewCard(singleRecharge, userBirthYear)
    }

    private fun reformatExistingCard(singleRecharge: Boolean, userBirthYear: Int): FormatResult {
        val userByte = MifareClassicHelper.toUserBirthByte(userBirthYear)
        val start = mifare.sectorToBlock(sector)
        val blocks = mifare.getBlockCountInSector(sector)
        val trailerIdx = start + blocks - 1

        // Ensure block 0 is writable (in case card had single-recharge bits).
        mifare.writeBlock(
            trailerIdx,
            MifareClassicHelper.buildSectorTrailer(cardKey, standard = true, userByte = userByte)
        )

        val counterData = mifare.readBlock(start + MifareClassicHelper.DATA_BLOCK_OFFSET)
        val oldBalance = MifareClassicHelper.readValueBlock(counterData) ?: 0

        val zeroValueBlock = MifareClassicHelper.makeValueBlock(0)
        val nowSecs = System.currentTimeMillis() / 1000L
        val freshTxBlock = TransactionBlock(nowSecs)
        val txData = freshTxBlock.toBytesWithChecksum(zeroValueBlock, uid, psk)
        val (txB1, txB2) = TransactionBlock.toMifareBlocks(txData)

        mifare.writeBlock(start + MifareClassicHelper.DATA_BLOCK_OFFSET, zeroValueBlock)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_1_OFFSET, txB1)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_2_OFFSET, txB2)

        // Write target access bits (restricted or standard).
        mifare.writeBlock(
            trailerIdx,
            MifareClassicHelper.buildSectorTrailer(cardKey, standard = !singleRecharge, userByte = userByte)
        )

        return FormatResult.Reformatted(
            oldBalance = oldBalance,
            formattedAtSeconds = nowSecs,
            transactionsData = txData
        )
    }

    private fun formatNewCard(singleRecharge: Boolean, userBirthYear: Int): FormatResult {
        val userByte = MifareClassicHelper.toUserBirthByte(userBirthYear)
        var foundKey: ByteArray? = null
        var usedKeyA = true

        for (key in MifareClassicHelper.STANDARD_KEYS) {
            if (mifare.authenticateSectorWithKeyA(sector, key)) {
                foundKey = key; usedKeyA = true; break
            }
            if (mifare.authenticateSectorWithKeyB(sector, key)) {
                foundKey = key; usedKeyA = false; break
            }
        }

        if (foundKey == null) return FormatResult.NoKeyFound

        // Re-authenticate with the found key.
        val reAuthed = if (usedKeyA) {
            mifare.authenticateSectorWithKeyA(sector, foundKey)
        } else {
            mifare.authenticateSectorWithKeyB(sector, foundKey)
        }
        if (!reAuthed) return FormatResult.AuthFailed

        val start = mifare.sectorToBlock(sector)
        val blocks = mifare.getBlockCountInSector(sector)

        val zeroValueBlock = MifareClassicHelper.makeValueBlock(0)
        val nowSecs = System.currentTimeMillis() / 1000L
        val freshTxBlock = TransactionBlock(nowSecs)
        val txData = freshTxBlock.toBytesWithChecksum(zeroValueBlock, uid, psk)
        val (txB1, txB2) = TransactionBlock.toMifareBlocks(txData)

        mifare.writeBlock(start + MifareClassicHelper.DATA_BLOCK_OFFSET, zeroValueBlock)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_1_OFFSET, txB1)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_2_OFFSET, txB2)

        mifare.writeBlock(
            start + blocks - 1,
            MifareClassicHelper.buildSectorTrailer(cardKey, standard = !singleRecharge, userByte = userByte)
        )

        return FormatResult.NewlyFormatted(
            formattedAtSeconds = nowSecs,
            transactionsData = txData,
            foundKeyType = if (usedKeyA) "A" else "B",
            foundKeyHex = foundKey.toHex(),
            newKeyHex = cardKey.toHex()
        )
    }

    // -------------------------------------------------------------------------
    // Reset
    // -------------------------------------------------------------------------

    override fun resetCard(): Boolean {
        // Try derived key first, then standard keys.
        var authenticated = mifare.authenticateSectorWithKeyA(sector, cardKey)
        if (!authenticated) {
            for (key in MifareClassicHelper.STANDARD_KEYS) {
                if (mifare.authenticateSectorWithKeyA(sector, key) ||
                    mifare.authenticateSectorWithKeyB(sector, key)
                ) {
                    authenticated = true
                    break
                }
            }
        }
        if (!authenticated) return false

        val start = mifare.sectorToBlock(sector)
        val blocks = mifare.getBlockCountInSector(sector)
        val emptyBlock = ByteArray(MifareClassic.BLOCK_SIZE)
        for (i in 0 until blocks - 1) {
            mifare.writeBlock(start + i, emptyBlock)
        }

        // Restore factory key and standard access bits.
        mifare.writeBlock(
            start + blocks - 1,
            MifareClassicHelper.buildSectorTrailer(
                MifareClassicHelper.FACTORY_KEY,
                standard = true,
                userByte = MifareClassicHelper.DEFAULT_USER_BYTE
            )
        )
        return true
    }
}
