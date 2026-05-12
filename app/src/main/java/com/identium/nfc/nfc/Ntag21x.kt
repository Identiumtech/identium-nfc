package com.identium.nfc.nfc

import android.nfc.tech.MifareUltralight
import android.nfc.tech.NfcA
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Low-level helpers for NTAG21x / Mifare Ultralight family tags.
 *
 * NTAG21x supports password protection (PWD/PACK), READ_CNT counters and
 * native page-locking. Most operations work over [NfcA] using raw command
 * bytes; the [MifareUltralight] technology only exposes a subset and breaks
 * on NTAG216 because of its larger memory map. Stick with NfcA.
 */
object Ntag21x {

    // Command bytes from the NTAG21x datasheet
    const val CMD_READ = 0x30.toByte()
    const val CMD_FAST_READ = 0x3A.toByte()
    const val CMD_WRITE = 0xA2.toByte()
    const val CMD_GET_VERSION = 0x60.toByte()
    const val CMD_PWD_AUTH = 0x1B.toByte()
    const val CMD_READ_SIG = 0x3C.toByte()
    const val CMD_READ_CNT = 0x39.toByte()

    enum class Variant(val pages: Int, val userBytes: Int, val lastUserPage: Int, val cfgPage: Int, val pwdPage: Int) {
        NTAG_213(45, 144, 0x27, 0x29, 0x2B),
        NTAG_215(135, 504, 0x81, 0x83, 0x85),
        NTAG_216(231, 888, 0xE1, 0xE3, 0xE5),
        ULTRALIGHT(16, 48, 0x0F, 0, 0),       // Original UL — no auth
        ULTRALIGHT_C(48, 144, 0x27, 0, 0)     // UL-C — uses 3DES, not handled here
    }

    /**
     * Reads the GET_VERSION block to identify NTAG variant.
     * Returns null if the tag is not an NTAG21x.
     */
    fun detect(nfcA: NfcA): Variant? {
        return try {
            val v = nfcA.transceive(byteArrayOf(CMD_GET_VERSION))
            if (v.size < 8) return null
            // byte[2] = product type, byte[6] = storage size
            val product = v[2].toInt() and 0xFF
            val storage = v[6].toInt() and 0xFF
            if (product != 0x04) return null // 0x04 = NTAG
            when (storage) {
                0x0F -> Variant.NTAG_213
                0x11 -> Variant.NTAG_215
                0x13 -> Variant.NTAG_216
                else -> null
            }
        } catch (_: IOException) { null }
    }

    /** Read 4 pages (16 bytes) starting from page. */
    fun readBlock(nfcA: NfcA, page: Int): ByteArray =
        nfcA.transceive(byteArrayOf(CMD_READ, page.toByte()))

    /** Write 4 bytes to a single page. */
    fun writePage(nfcA: NfcA, page: Int, data: ByteArray) {
        require(data.size == 4) { "page write requires 4 bytes" }
        val cmd = ByteArray(6)
        cmd[0] = CMD_WRITE
        cmd[1] = page.toByte()
        System.arraycopy(data, 0, cmd, 2, 4)
        val resp = nfcA.transceive(cmd)
        // Datasheet: ACK = 0x0A; NAK is anything else (often 0x00 / 0x01).
        if (resp.isNotEmpty() && resp[0] != 0x0A.toByte()) {
            throw IOException("WRITE returned NAK 0x${"%02X".format(resp[0])}")
        }
    }

    /** Issue PWD_AUTH; returns the 2-byte PACK response on success. */
    fun pwdAuth(nfcA: NfcA, password: ByteArray): ByteArray {
        require(password.size == 4) { "PWD must be 4 bytes" }
        val cmd = ByteArray(5)
        cmd[0] = CMD_PWD_AUTH
        System.arraycopy(password, 0, cmd, 1, 4)
        val resp = nfcA.transceive(cmd)
        if (resp.size != 2) throw IOException("PWD_AUTH failed (resp=${resp.size}b)")
        return resp
    }

    /** Read the 16-byte ECC signature block. */
    fun readSignature(nfcA: NfcA): ByteArray =
        nfcA.transceive(byteArrayOf(CMD_READ_SIG, 0x00))

    /** Read the 24-bit one-way counter. */
    fun readCounter(nfcA: NfcA, counter: Int = 2): ByteArray =
        nfcA.transceive(byteArrayOf(CMD_READ_CNT, counter.toByte()))

    /**
     * Set NTAG password (4 bytes ASCII converted) + PACK (2 bytes).
     * Also writes ACCESS / AUTH0 cfg so memory from [protectFromPage] is locked.
     */
    fun setPassword(nfcA: NfcA, variant: Variant, passwordAscii: String, protectFromPage: Int) {
        val pwd = derivePassword(passwordAscii)
        val pack = derivePack(passwordAscii)
        // PACK page sits at pwdPage + 1, lower two bytes used.
        val pwdPage = variant.pwdPage
        writePage(nfcA, pwdPage, pwd)
        writePage(nfcA, pwdPage + 1, byteArrayOf(pack[0], pack[1], 0, 0))

        // CFG0 (cfgPage): byte 3 = AUTH0 = first protected page.
        val cfg0 = readBlock(nfcA, variant.cfgPage)
        val newCfg0 = cfg0.copyOf(4)
        newCfg0[3] = protectFromPage.toByte()
        writePage(nfcA, variant.cfgPage, newCfg0)

        // CFG1 (cfgPage + 1): byte 0 = ACCESS. PROT bit (bit 7) protects READ
        // & WRITE. We set it so a reader has to authenticate to read or write
        // anything beyond AUTH0.
        val cfg1 = readBlock(nfcA, variant.cfgPage + 1)
        val newCfg1 = cfg1.copyOf(4)
        newCfg1[0] = (newCfg1[0].toInt() or 0x80).toByte()
        // AUTHLIM (bits 0..2) = 0 = no limit on attempts.
        newCfg1[0] = (newCfg1[0].toInt() and 0xF8.toInt() or 0x00).toByte()
        writePage(nfcA, variant.cfgPage + 1, newCfg1)
    }

    /** Remove password protection (set AUTH0 = 0xFF, clear PROT). */
    fun removePassword(nfcA: NfcA, variant: Variant) {
        val cfg0 = readBlock(nfcA, variant.cfgPage).copyOf(4)
        cfg0[3] = 0xFF.toByte()
        writePage(nfcA, variant.cfgPage, cfg0)

        val cfg1 = readBlock(nfcA, variant.cfgPage + 1).copyOf(4)
        cfg1[0] = (cfg1[0].toInt() and 0x7F).toByte() // clear PROT
        writePage(nfcA, variant.cfgPage + 1, cfg1)

        // Reset password to factory default 0xFFFFFFFF
        writePage(nfcA, variant.pwdPage, byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        writePage(nfcA, variant.pwdPage + 1, byteArrayOf(0, 0, 0, 0))
    }

    /**
     * Apply static lock bytes (page 02) and the dynamic-lock pages so the
     * tag is permanently read-only.
     *
     * Static lock bytes for NTAG21x live at page 2 bytes [2..3] and lock the
     * lower 16 pages. Dynamic lock pages live just past the user memory and
     * cover the rest. Because lock bits are OTP, this is irreversible.
     */
    fun lockEverything(nfcA: NfcA, variant: Variant) {
        // Static lock — preserve UID0..UID2 on page 2
        val page2 = readBlock(nfcA, 2).copyOf(4)
        page2[2] = 0xFF.toByte()
        page2[3] = 0xFF.toByte()
        writePage(nfcA, 2, page2)

        // Dynamic lock page — the lock page sits 1 page after the last user page.
        val dynLockPage = variant.lastUserPage + 1
        if (dynLockPage > 0) {
            val locks = readBlock(nfcA, dynLockPage).copyOf(4)
            locks[0] = 0xFF.toByte()
            locks[1] = 0xFF.toByte()
            locks[2] = 0xFF.toByte()
            // byte 3 reserved
            writePage(nfcA, dynLockPage, locks)
        }
    }

    /**
     * Convenience for users who type passwords. NTAG PWD is a 4-byte value;
     * we accept any string and pad/truncate to 4 bytes (UTF-8). When the user
     * passes hex, accept that too.
     */
    fun derivePassword(input: String): ByteArray {
        val cleanedHex = input.removePrefix("0x").replace(Regex("[^0-9A-Fa-f]"), "")
        return if (input.startsWith("0x") && cleanedHex.length == 8) {
            HexUtil.fromHex(cleanedHex)
        } else {
            val bytes = input.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(4)
            for (i in 0 until 4) out[i] = if (i < bytes.size) bytes[i] else 0x00
            out
        }
    }

    /** PACK is two bytes that the tag returns after auth. We derive a stable
     * value from the password so the user can recall it later. */
    fun derivePack(input: String): ByteArray {
        val pwd = derivePassword(input)
        return byteArrayOf((pwd[0].toInt() xor 0x55).toByte(), (pwd[1].toInt() xor 0xAA.toInt()).toByte())
    }
}
