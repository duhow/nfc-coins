package net.duhowpi.nfccoins

import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException

/**
 * [BaseCoinCard] implementation for NTAG cards over [NfcA].
 *
 * Data is stored in 4-byte pages using a contiguous 44-byte layout:
 * - 4 B  magic: "COIN"
 * - 4 B  version + flags (includes birth year and single-recharge flag)
 * - 2 B  balance (uint16)
 * - 32 B transactions + checksum
 * - 2 B  padding
 */
class NtagCoinCard(
    tag: Tag,
    psk: String,
    private val options: BaseCoinCard.Companion.NtagOptions
) : BaseCoinCard(tag, psk) {

    private data class HeaderMeta(
        val version: Int,
        val userBirthYear: Int,
        val isSingleRecharge: Boolean
    )

    private val nfcA: NfcA = requireNotNull(NfcA.get(tag)) {
        "NfcA tech unavailable for this tag"
    }

    override val maxBalance: Int = MifareClassicHelper.MAX_BALANCE

    private var totalPages: Int = -1
    private var dataStartPage: Int = -1
    private var supportsGetVersion: Boolean = false
    private var supportsFastRead: Boolean = false
    private var cachedMeta: HeaderMeta = HeaderMeta(
        version = CURRENT_VERSION,
        userBirthYear = MifareClassicHelper.toUserBirthYear(MifareClassicHelper.DEFAULT_USER_BYTE),
        isSingleRecharge = false
    )
    private var cachedBalance: Int = 0

    override fun connect() {
        nfcA.connect()
        ensureLayoutResolved()
    }

    override fun close() {
        runCatching { nfcA.close() }
    }

    override fun readCardData(): ReadResult {
        ensureLayoutResolved()
        val payload = readPayload44()

        if (!payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return ReadResult.InvalidData("Unformatted or invalid NTAG payload")
        }

        val meta = decodeMeta(payload.copyOfRange(OFFSET_META, OFFSET_BALANCE))
        val balance = decodeBalance(payload.copyOfRange(OFFSET_BALANCE, OFFSET_TX))
        val txData = payload.copyOfRange(OFFSET_TX, OFFSET_PADDING)

        cachedMeta = meta
        cachedBalance = balance

        return ReadResult.Success(
            CardData(
                balance = balance,
                transactions = TransactionBlock.fromBytes(txData),
                checksum = txData.copyOfRange(28, 32),
                userBirthYear = meta.userBirthYear,
                isSingleRecharge = meta.isSingleRecharge
            )
        )
    }

    override fun encodeBalance(value: Int): ByteArray =
        MifareClassicHelper.makeValueBlock(value.coerceIn(0, maxBalance))

    override fun retryPendingWrite(pending: PendingWriteData) {
        val balance = MifareClassicHelper.readValueBlock(pending.balanceData)
            ?: throw IllegalStateException("Invalid pending balance block")
        writeState(balance = balance, txData = pending.transactions, meta = cachedMeta)
    }

    override fun deductBalance(amount: Int, newTransactions: ByteArray) {
        val newBalance = (cachedBalance - amount).coerceIn(0, maxBalance)
        writeState(balance = newBalance, txData = newTransactions, meta = cachedMeta)
        cachedBalance = newBalance
    }

    override fun addBalance(amount: Int, newTransactions: ByteArray) {
        val newBalance = (cachedBalance + amount).coerceIn(0, maxBalance)
        writeState(balance = newBalance, txData = newTransactions, meta = cachedMeta)
        cachedBalance = newBalance
    }

    // NTAG has no trailer access bits equivalent for this flow.
    override fun unlockRecharge(cardData: CardData) {}

    // NTAG has no trailer access bits equivalent for this flow.
    override fun lockRecharge(cardData: CardData) {}

    override fun formatCard(formatOptions: CardData): FormatResult {
        ensureLayoutResolved()

        val existing = runCatching { readPayload44() }.getOrNull()
        val hadMagic = existing
            ?.copyOfRange(0, MAGIC.size)
            ?.contentEquals(MAGIC)
            ?: false

        val oldBalance = if (hadMagic) {
            decodeBalance(existing!!.copyOfRange(OFFSET_BALANCE, OFFSET_TX))
        } else {
            0
        }

        val nowSecs = System.currentTimeMillis() / 1000L
        val txBlock = TransactionBlock(nowSecs)
        val balance = 0
        val txData = txBlock.toBytesWithChecksum(encodeBalance(balance), uid, psk)

        val meta = HeaderMeta(
            version = CURRENT_VERSION,
            userBirthYear = formatOptions.userBirthYear,
            isSingleRecharge = formatOptions.isSingleRecharge
        )

        writeState(balance = balance, txData = txData, meta = meta)
        cachedMeta = meta
        cachedBalance = balance

        return if (hadMagic) {
            FormatResult.Reformatted(
                oldBalance = oldBalance,
                formattedAtSeconds = nowSecs,
                transactionsData = txData
            )
        } else {
            FormatResult.NewlyFormatted(
                formattedAtSeconds = nowSecs,
                transactionsData = txData
            )
        }
    }

    override fun resetCard(): Boolean {
        ensureLayoutResolved()
        writePages(dataStartPage, ByteArray(TOTAL_BYTES))
        cachedBalance = 0
        return true
    }

    private fun ensureLayoutResolved() {
        if (dataStartPage >= 0) return
        totalPages = detectTotalPages()

        val lastWritablePage = totalPages - 1 - options.reservedConfigAndLockPages
        val dataEndPage = lastWritablePage - options.freeTailPages
        val computedStart = dataEndPage - (TOTAL_PAGES - 1)

        require(computedStart >= options.userMemoryStartPage) {
            "NTAG user area too small for ${TOTAL_BYTES} bytes"
        }

        dataStartPage = computedStart
    }

    private fun detectTotalPages(): Int {
        val versionPages = detectTotalPagesFromVersion()
        if (versionPages != null) {
            supportsGetVersion = true
            supportsFastRead = isFastReadSupported()
            return versionPages
        }

        // NTAG20x family may not support GET_VERSION; fall back to probing.
        supportsGetVersion = false
        supportsFastRead = isFastReadSupported()
        val lastPage = probeLastReadablePage()
        if (lastPage < options.userMemoryStartPage) {
            throw IllegalStateException("Unsupported NTAG: could not resolve total pages")
        }
        return lastPage + 1
    }

    private fun detectTotalPagesFromVersion(): Int? {
        val version = runCatching { transceive(byteArrayOf(CMD_GET_VERSION)) }.getOrNull() ?: return null
        if (version.size < VERSION_RESPONSE_LEN) return null
        val sizeCode = version[6].toInt() and 0xFF
        return TOTAL_PAGES_BY_SIZE_CODE[sizeCode]
    }

    private fun readPayload44(): ByteArray = readPages(dataStartPage, TOTAL_PAGES)

    private fun writeState(balance: Int, txData: ByteArray, meta: HeaderMeta) {
        require(txData.size == TX_WITH_CHECKSUM_BYTES) { "Invalid tx payload size" }

        val payload = ByteArray(TOTAL_BYTES)
        System.arraycopy(MAGIC, 0, payload, OFFSET_MAGIC, MAGIC.size)

        val metaBytes = encodeMeta(meta)
        System.arraycopy(metaBytes, 0, payload, OFFSET_META, metaBytes.size)

        val balanceBytes = encodeBalanceCompact(balance)
        System.arraycopy(balanceBytes, 0, payload, OFFSET_BALANCE, balanceBytes.size)

        System.arraycopy(txData, 0, payload, OFFSET_TX, txData.size)
        // Last 2 bytes are left as 0x00 padding.

        writePages(dataStartPage, payload)
    }

    private fun readPages(startPage: Int, pageCount: Int): ByteArray {
        if (supportsFastRead) {
            val fastRead = runCatching { fastReadPages(startPage, pageCount) }.getOrNull()
            if (fastRead != null) return fastRead
            supportsFastRead = false
        }
        return readPagesByReadCommand(startPage, pageCount)
    }

    private fun fastReadPages(startPage: Int, pageCount: Int): ByteArray {
        val endPage = startPage + pageCount - 1
        val response = transceive(byteArrayOf(CMD_FAST_READ, startPage.toByte(), endPage.toByte()))
        val expected = pageCount * BYTES_PER_PAGE
        require(response.size >= expected) { "Unexpected NTAG FAST_READ response" }
        return response.copyOf(expected)
    }

    private fun readPagesByReadCommand(startPage: Int, pageCount: Int): ByteArray {
        val out = ByteArray(pageCount * BYTES_PER_PAGE)
        var dst = 0
        var page = startPage
        var remaining = pageCount

        while (remaining > 0) {
            val response = transceive(byteArrayOf(CMD_READ, page.toByte()))
            require(response.size >= READ_CHUNK_PAGES * BYTES_PER_PAGE) {
                "Unexpected NTAG READ response"
            }

            val pagesToCopy = minOf(remaining, READ_CHUNK_PAGES)
            val bytesToCopy = pagesToCopy * BYTES_PER_PAGE
            System.arraycopy(response, 0, out, dst, bytesToCopy)

            dst += bytesToCopy
            page += READ_CHUNK_PAGES
            remaining -= pagesToCopy
        }

        return out
    }

    private fun writePages(startPage: Int, data: ByteArray) {
        require(data.size % BYTES_PER_PAGE == 0)
        var page = startPage

        for (offset in data.indices step BYTES_PER_PAGE) {
            val cmd = byteArrayOf(
                CMD_WRITE,
                page.toByte(),
                data[offset],
                data[offset + 1],
                data[offset + 2],
                data[offset + 3]
            )
            transceive(cmd)
            page++
        }
    }

    private fun transceive(command: ByteArray): ByteArray = nfcA.transceive(command)

    private fun isFastReadSupported(): Boolean =
        runCatching {
            // Probe a tiny span in the static area to avoid touching user payload layout.
            val rsp = transceive(byteArrayOf(CMD_FAST_READ, 0x00, 0x03))
            rsp.size >= READ_CHUNK_PAGES * BYTES_PER_PAGE
        }.getOrElse { false }

    private fun probeLastReadablePage(): Int {
        var low = options.userMemoryStartPage
        var high = MAX_PAGE_PROBE
        var best = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (canReadPage(mid)) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    private fun canReadPage(page: Int): Boolean =
        runCatching {
            val rsp = transceive(byteArrayOf(CMD_READ, page.toByte()))
            rsp.size >= READ_CHUNK_PAGES * BYTES_PER_PAGE
        }.getOrElse { err ->
            // NAK/IO errors are expected while probing beyond memory boundaries.
            if (err is IOException) return@getOrElse false
            false
        }

    private fun encodeMeta(meta: HeaderMeta): ByteArray {
        val versionNibble = (meta.version and 0x0F) shl 4
        val flagsNibble = if (meta.isSingleRecharge) FLAG_SINGLE_RECHARGE else 0
        return byteArrayOf(
            (versionNibble or flagsNibble).toByte(),
            MifareClassicHelper.toUserBirthByte(meta.userBirthYear),
            0x00,
            0x00
        )
    }

    private fun decodeMeta(raw: ByteArray): HeaderMeta {
        require(raw.size == META_BYTES)
        val b0 = raw[0].toInt() and 0xFF
        val version = (b0 ushr 4) and 0x0F
        val flags = b0 and 0x0F
        val isSingleRecharge = (flags and FLAG_SINGLE_RECHARGE) != 0
        val userBirthYear = MifareClassicHelper.toUserBirthYear(raw[1].toInt() and 0xFF)
        return HeaderMeta(version, userBirthYear, isSingleRecharge)
    }

    private fun encodeBalanceCompact(balance: Int): ByteArray {
        val value = balance.coerceIn(0, maxBalance)
        return byteArrayOf(
            ((value ushr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun decodeBalance(raw: ByteArray): Int {
        require(raw.size == BALANCE_BYTES)
        return ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    }

    companion object {
        private const val BYTES_PER_PAGE = 4
        private const val READ_CHUNK_PAGES = 4

        private const val TOTAL_BYTES = 44
        private const val TOTAL_PAGES = TOTAL_BYTES / BYTES_PER_PAGE

        private const val META_BYTES = 4
        private const val BALANCE_BYTES = 2
        private const val TX_WITH_CHECKSUM_BYTES = 32

        private const val OFFSET_MAGIC = 0
        private const val OFFSET_META = OFFSET_MAGIC + 4
        private const val OFFSET_BALANCE = OFFSET_META + META_BYTES
        private const val OFFSET_TX = OFFSET_BALANCE + BALANCE_BYTES
        private const val OFFSET_PADDING = OFFSET_TX + TX_WITH_CHECKSUM_BYTES

        private const val CURRENT_VERSION = 0x1
        private const val FLAG_SINGLE_RECHARGE = 0x1

        private const val CMD_READ: Byte = 0x30
        private const val CMD_FAST_READ: Byte = 0x3A
        private const val CMD_WRITE: Byte = 0xA2.toByte()
        private const val CMD_GET_VERSION: Byte = 0x60
        private const val VERSION_RESPONSE_LEN = 8
        private const val MAX_PAGE_PROBE = 255

        private val MAGIC = "COIN".toByteArray(Charsets.US_ASCII)

        private val TOTAL_PAGES_BY_SIZE_CODE = mapOf(
            0x0B to 20,  // NTAG210
            0x0E to 41,  // NTAG212
            0x0F to 45,  // NTAG213
            0x11 to 135, // NTAG215
            0x13 to 231  // NTAG216
        )
    }
}
