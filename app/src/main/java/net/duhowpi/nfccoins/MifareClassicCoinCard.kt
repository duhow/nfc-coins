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
    private val mifare: MifareClassic,
    private val sector: Int,
    psk: String,
    useDynamic: Boolean
) : BaseCoinCard(tag, psk) {

    override val maxBalance: Int = MifareClassicHelper.MAX_BALANCE

    private val cardKey: ByteArray = MifareClassicHelper.deriveCardKey(uid, psk, useDynamic)

    // Sector layout cached after the first successful read.
    private var sectorStart: Int = 0
    private var blocksInSector: Int = 0

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
                txBlock = sd.txBlock,
                ageByte = MifareClassicHelper.getAgeByte(sd.trailerData),
                isSingleRecharge = MifareClassicHelper.isSingleRecharge(sd.trailerData),
                counterData = sd.counterData,
                txBlock1 = sd.txBlock1,
                txBlock2 = sd.txBlock2
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
        mifare.writeBlock(sectorStart + MifareClassicHelper.DATA_BLOCK_OFFSET, pending.counterBlock)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_1_OFFSET, pending.txBlock1)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_2_OFFSET, pending.txBlock2)
    }

    // -------------------------------------------------------------------------
    // Deduct
    // -------------------------------------------------------------------------

    override fun deductBalance(amount: Int, newTxBlock1: ByteArray, newTxBlock2: ByteArray) {
        val blockIndex = sectorStart + MifareClassicHelper.DATA_BLOCK_OFFSET
        mifare.decrement(blockIndex, amount)
        mifare.transfer(blockIndex)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_1_OFFSET, newTxBlock1)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_2_OFFSET, newTxBlock2)
    }

    // -------------------------------------------------------------------------
    // Add
    // -------------------------------------------------------------------------

    override fun addBalance(
        amount: Int,
        cardData: CardData,
        newTxBlock1: ByteArray,
        newTxBlock2: ByteArray
    ) {
        val trailerIdx = sectorStart + blocksInSector - 1

        if (cardData.isSingleRecharge) {
            // Temporarily unlock block 0 so increment is allowed.
            val openTrailer = MifareClassicHelper.buildSectorTrailer(
                cardKey, standard = true, userByte = cardData.ageByte
            )
            mifare.writeBlock(trailerIdx, openTrailer)
        }

        val blockIndex = sectorStart + MifareClassicHelper.DATA_BLOCK_OFFSET
        mifare.increment(blockIndex, amount)
        mifare.transfer(blockIndex)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_1_OFFSET, newTxBlock1)
        mifare.writeBlock(sectorStart + MifareClassicHelper.TX_BLOCK_2_OFFSET, newTxBlock2)

        if (cardData.isSingleRecharge) {
            // Re-lock: restore restricted access bits.
            val restrictedTrailer = MifareClassicHelper.buildSectorTrailer(
                cardKey, standard = false, userByte = cardData.ageByte
            )
            mifare.writeBlock(trailerIdx, restrictedTrailer)
        }
    }

    // -------------------------------------------------------------------------
    // Format
    // -------------------------------------------------------------------------

    override fun formatCard(singleRecharge: Boolean, ageByte: Int): FormatResult {
        // Path 1: already formatted with the derived key → re-format (reset balance).
        if (mifare.authenticateSectorWithKeyA(sector, cardKey)) {
            return reformatExistingCard(singleRecharge, ageByte)
        }

        // Path 2: search for a standard key → format from scratch.
        return formatNewCard(singleRecharge, ageByte)
    }

    private fun reformatExistingCard(singleRecharge: Boolean, ageByte: Int): FormatResult {
        val start = mifare.sectorToBlock(sector)
        val blocks = mifare.getBlockCountInSector(sector)
        val trailerIdx = start + blocks - 1

        // Ensure block 0 is writable (in case card had single-recharge bits).
        mifare.writeBlock(
            trailerIdx,
            MifareClassicHelper.buildSectorTrailer(cardKey, standard = true, userByte = ageByte)
        )

        val counterData = mifare.readBlock(start + MifareClassicHelper.DATA_BLOCK_OFFSET)
        val oldBalance = MifareClassicHelper.readValueBlock(counterData) ?: 0

        val zeroValueBlock = MifareClassicHelper.makeValueBlock(0)
        val nowSecs = System.currentTimeMillis() / 1000L
        val freshTxBlock = TransactionBlock(nowSecs)
        val (txB1, txB2) = freshTxBlock.toBytes(zeroValueBlock, uid, psk)

        mifare.writeBlock(start + MifareClassicHelper.DATA_BLOCK_OFFSET, zeroValueBlock)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_1_OFFSET, txB1)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_2_OFFSET, txB2)

        // Write target access bits (restricted or standard).
        mifare.writeBlock(
            trailerIdx,
            MifareClassicHelper.buildSectorTrailer(cardKey, standard = !singleRecharge, userByte = ageByte)
        )

        return FormatResult.Reformatted(
            oldBalance = oldBalance,
            txBlock = freshTxBlock,
            counterData = zeroValueBlock,
            txB1 = txB1,
            txB2 = txB2
        )
    }

    private fun formatNewCard(singleRecharge: Boolean, ageByte: Int): FormatResult {
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
        val (txB1, txB2) = freshTxBlock.toBytes(zeroValueBlock, uid, psk)

        mifare.writeBlock(start + MifareClassicHelper.DATA_BLOCK_OFFSET, zeroValueBlock)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_1_OFFSET, txB1)
        mifare.writeBlock(start + MifareClassicHelper.TX_BLOCK_2_OFFSET, txB2)

        mifare.writeBlock(
            start + blocks - 1,
            MifareClassicHelper.buildSectorTrailer(cardKey, standard = !singleRecharge, userByte = ageByte)
        )

        return FormatResult.NewlyFormatted(
            txBlock = freshTxBlock,
            counterData = zeroValueBlock,
            txB1 = txB1,
            txB2 = txB2,
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
