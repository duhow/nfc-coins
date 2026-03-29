package net.duhowpi.nfccoins

import android.nfc.Tag
import android.nfc.tech.NfcA
import java.io.IOException
import java.security.MessageDigest
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [BaseCoinCard] implementation for NTAG cards over [NfcA].
 *
 * Data is stored in 4-byte pages using a contiguous 44-byte layout:
 * - 4 B  magic: "COIN"
 * - 4 B  version + flags (includes birth year and single-recharge flag)
 * - 2 B  balance (uint16)
 * - 32 B transactions + checksum
 * - 2 B  version magic: 0xC0 0x1V (where V = version nibble; 0x00 0x00 accepted for v1)
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
    private var cachedEncryptedPayload: ByteArray? = null

    override fun connect() {
        nfcA.connect()
        ensureLayoutResolved()
    }

    override fun close() {
        runCatching { nfcA.close() }
    }

    override fun readCardData(): ReadResult {
        ensureLayoutResolved()
        val rawPayload = readPayload44()

        if (!rawPayload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            return ReadResult.InvalidData("Unformatted or invalid NTAG payload")
        }

        val payload = decryptStoredPayload(rawPayload)

        val meta = decodeMeta(payload.copyOfRange(OFFSET_META, OFFSET_BALANCE))

        if (meta.version < 1 || meta.version > CURRENT_VERSION) {
            return ReadResult.InvalidData("$DECRYPT_ERROR_PREFIX invalid version ${meta.version}")
        }

        if (!isPaddingValid(payload, meta.version)) {
            return ReadResult.InvalidData("$DECRYPT_ERROR_PREFIX invalid padding magic")
        }

        val txData = payload.copyOfRange(OFFSET_TX, OFFSET_PADDING)
        val txBlock = TransactionBlock.fromBytes(txData)

        if (meta.version >= 2) {
            val cal = Calendar.getInstance()
            val currentYear = cal.get(Calendar.YEAR)
            cal.timeInMillis = txBlock.initTimestamp * 1000L
            val tsYear = cal.get(Calendar.YEAR)
            if (tsYear !in MIN_VALID_TIMESTAMP_YEAR..currentYear) {
                return ReadResult.InvalidData(
                    "$DECRYPT_ERROR_PREFIX invalid timestamp year $tsYear (expected $MIN_VALID_TIMESTAMP_YEAR-$currentYear)"
                )
            }
        }

        val balance = decodeBalance(payload.copyOfRange(OFFSET_BALANCE, OFFSET_TX))

        cachedMeta = meta
        cachedBalance = balance
        cachedEncryptedPayload = rawPayload.copyOf()

        return ReadResult.Success(
            CardData(
                balance = balance,
                transactions = txBlock,
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

        val existingRaw = runCatching { readPayload44() }.getOrNull()
        val hadMagic = existingRaw
            ?.copyOfRange(0, MAGIC.size)
            ?.contentEquals(MAGIC)
            ?: false

        val existing = if (hadMagic && existingRaw != null) {
            runCatching { decryptStoredPayload(existingRaw) }.getOrNull()
        } else {
            null
        }
        if (hadMagic && existing != null && existingRaw != null) {
            cachedEncryptedPayload = existingRaw.copyOf()
        }

        val oldBalance = if (hadMagic && existing != null) {
            decodeBalance(existing.copyOfRange(OFFSET_BALANCE, OFFSET_TX))
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
        val emptyPayload = ByteArray(TOTAL_BYTES)
        writePages(dataStartPage, emptyPayload)
        cachedEncryptedPayload = emptyPayload
        cachedBalance = 0
        return true
    }

    private fun ensureLayoutResolved() {
        if (dataStartPage >= 0) return
        totalPages = detectTotalPages()

        val lastWritablePage = totalPages - 1 - options.reservedConfigAndLockPages
        val tailReservationPages = calculateTailReservationPages(totalPages, lastWritablePage)
        val dataEndPage = lastWritablePage - tailReservationPages
        val computedStart = dataEndPage - (TOTAL_PAGES - 1)

        require(computedStart >= options.userMemoryStartPage) {
            "NTAG user area too small for ${TOTAL_BYTES} bytes"
        }

        dataStartPage = computedStart
    }

    private fun calculateTailReservationPages(totalPages: Int, lastWritablePage: Int): Int {
        val usablePages = (lastWritablePage - options.userMemoryStartPage + 1).coerceAtLeast(0)
        val usableBytes = usablePages * BYTES_PER_PAGE

        // For small NTAGs (e.g. NTAG203), keep the payload as close as possible to
        // lock/config pages so the beginning of user memory remains available for NDEF.
        return if (usableBytes < SMALL_TAG_THRESHOLD_BYTES) 0 else options.freeTailPages
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

        // Always write with the current version, migrating older cards on next write.
        val writeMeta = if (meta.version != CURRENT_VERSION) meta.copy(version = CURRENT_VERSION) else meta

        val plainPayload = ByteArray(TOTAL_BYTES)
        System.arraycopy(MAGIC, 0, plainPayload, OFFSET_MAGIC, MAGIC.size)

        val metaBytes = encodeMeta(writeMeta)
        System.arraycopy(metaBytes, 0, plainPayload, OFFSET_META, metaBytes.size)

        val balanceBytes = encodeBalanceCompact(balance)
        System.arraycopy(balanceBytes, 0, plainPayload, OFFSET_BALANCE, balanceBytes.size)

        System.arraycopy(txData, 0, plainPayload, OFFSET_TX, txData.size)

        // Write version magic instead of padding.
        plainPayload[OFFSET_PADDING] = PADDING_MAGIC.toByte()
        plainPayload[OFFSET_PADDING + 1] = (PADDING_VERSION_PREFIX or writeMeta.version).toByte()

        val payload = encryptStoredPayload(plainPayload)
        val previousPayload = cachedEncryptedPayload
        if (previousPayload != null) {
            writeChangedPages(dataStartPage, previousPayload, payload)
        } else {
            writePages(dataStartPage, payload)
        }
        cachedEncryptedPayload = payload
    }

    private fun encryptStoredPayload(plainPayload: ByteArray): ByteArray {
        require(plainPayload.size == TOTAL_BYTES)
        val encrypted = plainPayload.copyOf()
        val body = plainPayload.copyOfRange(OFFSET_META, TOTAL_BYTES)
        val encryptedBody = cryptBody(body)
        System.arraycopy(encryptedBody, 0, encrypted, OFFSET_META, encryptedBody.size)
        return encrypted
    }

    private fun decryptStoredPayload(rawPayload: ByteArray): ByteArray {
        require(rawPayload.size == TOTAL_BYTES)
        val decrypted = rawPayload.copyOf()
        val body = rawPayload.copyOfRange(OFFSET_META, TOTAL_BYTES)
        val decryptedBody = cryptBody(body)
        System.arraycopy(decryptedBody, 0, decrypted, OFFSET_META, decryptedBody.size)
        return decrypted
    }

    private fun cryptBody(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val key = SecretKeySpec(deriveBytes(KEY_DERIVE_LABEL, AES_KEY_SIZE_BYTES), "AES")
        val iv = IvParameterSpec(deriveBytes(IV_DERIVE_LABEL, AES_BLOCK_SIZE_BYTES))
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        return cipher.doFinal(data)
    }

    private fun deriveBytes(label: String, length: Int): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(label.toByteArray(Charsets.US_ASCII))
        digest.update(0x00)
        digest.update(psk.toByteArray(Charsets.UTF_8))
        digest.update(0x00)
        digest.update(uid)
        return digest.digest().copyOf(length)
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
            val response = transceive(cmd)
            validateWriteAck(response, page)
            page++
        }
    }

    private fun writeChangedPages(startPage: Int, previous: ByteArray, next: ByteArray) {
        require(previous.size == next.size)
        require(next.size % BYTES_PER_PAGE == 0)
        var page = startPage

        for (offset in next.indices step BYTES_PER_PAGE) {
            val isDifferent =
                previous[offset] != next[offset] ||
                previous[offset + 1] != next[offset + 1] ||
                previous[offset + 2] != next[offset + 2] ||
                previous[offset + 3] != next[offset + 3]
            if (isDifferent) {
                val cmd = byteArrayOf(
                    CMD_WRITE,
                    page.toByte(),
                    next[offset],
                    next[offset + 1],
                    next[offset + 2],
                    next[offset + 3]
                )
                val response = transceive(cmd)
                validateWriteAck(response, page)
            }
            page++
        }
    }

    /**
     * Validate that an NTAG/Type 2 WRITE command returned a proper ACK frame.
     *
     * According to the NTAG/Type 2 protocol, successful writes return a single-byte
     * ACK frame (0x0A). Some NFC stacks abstract this low-level ACK and return an
     * empty byte array on success, so we accept that as success too.
     */
    private fun validateWriteAck(response: ByteArray, page: Int) {
        val isAck = response.isEmpty() || (response.size == 1 && response[0] == 0x0A.toByte())
        require(isAck) {
            val hex = response.joinToString(separator = " ") { "0x%02X".format(it) }
            "NTAG write to page $page failed: NAK or unexpected response ($hex)"
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

    private fun isPaddingValid(payload: ByteArray, version: Int): Boolean {
        val p0 = payload[OFFSET_PADDING].toInt() and 0xFF
        val p1 = payload[OFFSET_PADDING + 1].toInt() and 0xFF
        val expectedMagic = (PADDING_VERSION_PREFIX or version) and 0xFF
        return if (version == 1) {
            // Retro-compatible: accept legacy 0x00 0x00 padding or new version magic.
            (p0 == 0x00 && p1 == 0x00) || (p0 == PADDING_MAGIC && p1 == expectedMagic)
        } else {
            p0 == PADDING_MAGIC && p1 == expectedMagic
        }
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

        private const val CURRENT_VERSION = 0x2
        private const val FLAG_SINGLE_RECHARGE = 0x1
        private const val MIN_VALID_TIMESTAMP_YEAR = 2026
        internal const val DECRYPT_ERROR_PREFIX = "Decryption error:"
        private const val PADDING_MAGIC = 0xC0
        private const val PADDING_VERSION_PREFIX = 0x10

        private const val CIPHER_TRANSFORMATION = "AES/CTR/NoPadding"
        private const val AES_KEY_SIZE_BYTES = 16
        private const val AES_BLOCK_SIZE_BYTES = 16
        private const val KEY_DERIVE_LABEL = "NTAG-DATA-KEY"
        private const val IV_DERIVE_LABEL = "NTAG-DATA-IV"

        private const val CMD_READ: Byte = 0x30
        private const val CMD_FAST_READ: Byte = 0x3A
        private const val CMD_WRITE: Byte = 0xA2.toByte()
        private const val CMD_GET_VERSION: Byte = 0x60
        private const val VERSION_RESPONSE_LEN = 8
        private const val MAX_PAGE_PROBE = 255
        private const val SMALL_TAG_THRESHOLD_BYTES = 250

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
