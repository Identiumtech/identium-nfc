package com.identium.nfc.nfc

import android.nfc.FormatException
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import java.io.IOException

/**
 * High-level NFC operations the UI calls into.
 *
 * Every method accepts a raw [Tag] and is responsible for connecting,
 * performing the requested work, and closing. UI code never holds a Tag.
 */
object TagOperations {

    fun read(tag: Tag): TagInfo {
        val techList = tag.techList.toList().map { it.removePrefix("android.nfc.tech.") }
        val uidHex = HexUtil.toHex(tag.id, separator = ":")
        var atqaHex: String? = null
        var sakHex: String? = null
        var historicalHex: String? = null
        var maxXcv: Int? = null
        var totalMem: Int? = null
        var usedMem: Int? = null
        var writable = false
        var canRO = false
        var ndef: NdefMessage? = null
        var dump: ByteArray? = null
        var pages: Int? = null
        var product: String? = null
        var detectedType = TagType.GENERIC

        // 1) NDEF first — gives memory-size, writability, message
        val ndefT = Ndef.get(tag)
        if (ndefT != null) {
            try {
                ndefT.connect()
                writable = ndefT.isWritable
                canRO = ndefT.canMakeReadOnly()
                totalMem = ndefT.maxSize
                ndef = ndefT.cachedNdefMessage ?: try { ndefT.ndefMessage } catch (_: Exception) { null }
                usedMem = ndef?.byteArrayLength
                detectedType = when (ndefT.type) {
                    Ndef.NFC_FORUM_TYPE_1 -> TagType.GENERIC
                    Ndef.NFC_FORUM_TYPE_2 -> TagType.NFC_FORUM_TYPE_2
                    Ndef.NFC_FORUM_TYPE_3 -> TagType.GENERIC
                    Ndef.NFC_FORUM_TYPE_4 -> TagType.NFC_FORUM_TYPE_4
                    "android.nfc.tech.IsoDep" -> TagType.NFC_FORUM_TYPE_4
                    else -> TagType.GENERIC
                }
            } catch (_: Exception) {
            } finally {
                runCatching { ndefT.close() }
            }
        }

        // 2) NfcA — ATQA / SAK + raw memory dump for NTAG / Ultralight
        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            try {
                nfcA.connect()
                atqaHex = HexUtil.toHex(nfcA.atqa)
                sakHex = "%02X".format(nfcA.sak.toInt() and 0xFF)
                maxXcv = nfcA.maxTransceiveLength
                val variant = Ntag21x.detect(nfcA)
                if (variant != null) {
                    pages = variant.pages
                    product = when (variant) {
                        Ntag21x.Variant.NTAG_213 -> "NTAG213 (180 bytes)"
                        Ntag21x.Variant.NTAG_215 -> "NTAG215 (540 bytes)"
                        Ntag21x.Variant.NTAG_216 -> "NTAG216 (924 bytes)"
                        else -> null
                    }
                    detectedType = when (variant) {
                        Ntag21x.Variant.NTAG_213 -> TagType.NTAG_213
                        Ntag21x.Variant.NTAG_215 -> TagType.NTAG_215
                        Ntag21x.Variant.NTAG_216 -> TagType.NTAG_216
                        Ntag21x.Variant.ULTRALIGHT -> TagType.MIFARE_ULTRALIGHT
                        Ntag21x.Variant.ULTRALIGHT_C -> TagType.MIFARE_ULTRALIGHT_C
                    }
                    dump = readEntireMemoryWithFastRead(nfcA, variant.pages)
                } else {
                    dump = tryGenericPageRead(nfcA)
                    if (dump != null) pages = dump.size / 4
                }
            } catch (_: Exception) {
            } finally {
                runCatching { nfcA.close() }
            }
        }

        // 3) Mifare Classic-specific overrides
        val classic = MifareClassic.get(tag)
        if (classic != null) {
            try {
                classic.connect()
                totalMem = classic.size
                detectedType = if (classic.size == MifareClassic.SIZE_4K) TagType.MIFARE_CLASSIC_4K else TagType.MIFARE_CLASSIC_1K
                pages = classic.blockCount
                product = "MIFARE Classic — ${classic.size / 1024}K"
            } catch (_: Exception) {
            } finally {
                runCatching { classic.close() }
            }
        }

        return TagInfo(
            uidHex = uidHex,
            uidLength = tag.id.size,
            techList = techList,
            type = detectedType,
            atqaHex = atqaHex,
            sakHex = sakHex,
            historicalBytesHex = historicalHex,
            maxTransceiveLength = maxXcv,
            totalMemoryBytes = totalMem,
            usedMemoryBytes = usedMem,
            writable = writable,
            canMakeReadOnly = canRO,
            ndefMessage = ndef,
            rawDump = dump,
            pageCount = pages,
            productName = product
        )
    }

    private fun readEntireMemoryWithFastRead(nfcA: NfcA, pages: Int): ByteArray? {
        return try {
            val out = ByteArray(pages * 4)
            // FAST_READ supports up to 64 byte responses safely.
            var off = 0
            while (off < pages) {
                val end = minOf(pages - 1, off + 14)
                val resp = nfcA.transceive(byteArrayOf(Ntag21x.CMD_FAST_READ, off.toByte(), end.toByte()))
                System.arraycopy(resp, 0, out, off * 4, resp.size)
                off = end + 1
            }
            out
        } catch (_: IOException) {
            // Fall back to plain READ (4 pages at a time)
            try {
                val out = ByteArray(pages * 4)
                var off = 0
                while (off < pages) {
                    val resp = nfcA.transceive(byteArrayOf(Ntag21x.CMD_READ, off.toByte()))
                    val toCopy = minOf(resp.size, out.size - off * 4)
                    if (toCopy > 0) System.arraycopy(resp, 0, out, off * 4, toCopy)
                    off += 4
                }
                out
            } catch (_: IOException) { null }
        }
    }

    private fun tryGenericPageRead(nfcA: NfcA): ByteArray? {
        return try {
            val out = mutableListOf<Byte>()
            var page = 0
            while (page < 64) {
                val resp = try { nfcA.transceive(byteArrayOf(Ntag21x.CMD_READ, page.toByte())) }
                catch (_: IOException) { return if (out.isEmpty()) null else out.toByteArray() }
                if (resp.isEmpty()) break
                resp.forEach { out += it }
                page += 4
            }
            if (out.isEmpty()) null else out.toByteArray()
        } catch (_: Exception) { null }
    }

    /**
     * Write an NDEF message to the tag. If [makeReadOnly] is set, after the
     * write completes we try to lock — first via the NFC Forum Ndef API, and
     * if that fails (e.g. NTAG21x where Android refuses to drive lock-bytes),
     * via raw NTAG static + dynamic lock pages.
     */
    fun writeNdef(tag: Tag, message: NdefMessage, makeReadOnly: Boolean): WriteResult {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                if (!ndef.isWritable) return WriteResult.error("Tag is read-only")
                if (ndef.maxSize < message.byteArrayLength)
                    return WriteResult.error("Message is ${message.byteArrayLength} bytes but tag only fits ${ndef.maxSize}")

                ndef.writeNdefMessage(message)
                if (makeReadOnly) tryMakeReadOnly(ndef, tag)
                return WriteResult.ok(message.byteArrayLength)
            } catch (e: TagLostException) {
                return WriteResult.error("Tag lost — keep the tag still and try again")
            } catch (e: FormatException) {
                return WriteResult.error("NDEF format error: ${e.message}")
            } catch (e: IOException) {
                return WriteResult.error("I/O error: ${e.message}")
            } finally {
                runCatching { ndef.close() }
            }
        }

        // No Ndef tech — try to format and then write.
        val formatable = NdefFormatable.get(tag) ?: return WriteResult.error("Tag is not NDEF capable")
        return try {
            formatable.connect()
            if (makeReadOnly) formatable.formatReadOnly(message) else formatable.format(message)
            WriteResult.ok(message.byteArrayLength)
        } catch (e: Exception) {
            WriteResult.error("Format failed: ${e.message}")
        } finally {
            runCatching { formatable.close() }
        }
    }

    private fun tryMakeReadOnly(ndef: Ndef, tag: Tag) {
        val ok = try { ndef.makeReadOnly() } catch (_: Exception) { false }
        if (ok) return

        // Some Android versions / NTAGs refuse the standard call; fall back
        // to driving lock-bytes directly via NfcA.
        val nfcA = NfcA.get(tag) ?: return
        runCatching {
            nfcA.connect()
            val variant = Ntag21x.detect(nfcA) ?: Ntag21x.Variant.ULTRALIGHT
            Ntag21x.lockEverything(nfcA, variant)
        }
        runCatching { nfcA.close() }
    }

    /** Erase by writing a single empty NDEF record. */
    fun erase(tag: Tag): WriteResult {
        val emptyMsg = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0))))
        return writeNdef(tag, emptyMsg, makeReadOnly = false)
    }

    /** Force a Format on a not-yet-formatted tag. */
    fun format(tag: Tag): WriteResult {
        val formatable = NdefFormatable.get(tag) ?: return WriteResult.error("Tag is already formatted or not formattable")
        return try {
            formatable.connect()
            val emptyMsg = NdefMessage(arrayOf(NdefRecord(NdefRecord.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0))))
            formatable.format(emptyMsg)
            WriteResult.ok(0)
        } catch (e: Exception) {
            WriteResult.error("Format failed: ${e.message}")
        } finally {
            runCatching { formatable.close() }
        }
    }

    /** Make the tag read-only (irreversible on most chips). */
    fun makeReadOnly(tag: Tag): WriteResult {
        val ndef = Ndef.get(tag) ?: return WriteResult.error("Not an NDEF tag")
        return try {
            ndef.connect()
            if (!ndef.canMakeReadOnly()) return WriteResult.error("Tag refuses to be made read-only")
            val ok = ndef.makeReadOnly()
            if (ok) WriteResult.ok(0) else {
                tryMakeReadOnly(ndef, tag)
                WriteResult.ok(0)
            }
        } catch (e: Exception) {
            WriteResult.error("Lock failed: ${e.message}")
        } finally {
            runCatching { ndef.close() }
        }
    }

    fun setPassword(tag: Tag, passwordAscii: String, protectFromPage: Int): WriteResult {
        val nfcA = NfcA.get(tag) ?: return WriteResult.error("Password requires an NTAG21x / NfcA tag")
        return try {
            nfcA.connect()
            val variant = Ntag21x.detect(nfcA)
                ?: return WriteResult.error("This chip does not support PWD_AUTH")
            Ntag21x.setPassword(nfcA, variant, passwordAscii, protectFromPage)
            WriteResult.ok(0, "Password set on ${variant.name}")
        } catch (e: Exception) {
            WriteResult.error("setPassword failed: ${e.message}")
        } finally {
            runCatching { nfcA.close() }
        }
    }

    fun removePassword(tag: Tag, currentPasswordAscii: String): WriteResult {
        val nfcA = NfcA.get(tag) ?: return WriteResult.error("Requires NfcA")
        return try {
            nfcA.connect()
            val variant = Ntag21x.detect(nfcA)
                ?: return WriteResult.error("Not an NTAG21x")
            // Authenticate first
            Ntag21x.pwdAuth(nfcA, Ntag21x.derivePassword(currentPasswordAscii))
            Ntag21x.removePassword(nfcA, variant)
            WriteResult.ok(0, "Password cleared")
        } catch (e: Exception) {
            WriteResult.error("removePassword failed: ${e.message}")
        } finally {
            runCatching { nfcA.close() }
        }
    }

    data class WriteResult(val success: Boolean, val bytesWritten: Int, val message: String) {
        companion object {
            fun ok(bytes: Int, msg: String = "Done") = WriteResult(true, bytes, msg)
            fun error(msg: String) = WriteResult(false, 0, msg)
        }
    }
}
