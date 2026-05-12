package com.identium.nfc.nfc

object HexUtil {
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun toHex(bytes: ByteArray, separator: String = ""): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        for ((i, b) in bytes.withIndex()) {
            if (i > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    fun fromHex(s: String): ByteArray {
        val cleaned = s.replace(Regex("[^0-9A-Fa-f]"), "")
        require(cleaned.length % 2 == 0) { "Hex length must be even" }
        return ByteArray(cleaned.length / 2) { i ->
            ((Character.digit(cleaned[i * 2], 16) shl 4) +
                    Character.digit(cleaned[i * 2 + 1], 16)).toByte()
        }
    }

    fun hexDump(bytes: ByteArray, columns: Int = 16): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder()
        var offset = 0
        while (offset < bytes.size) {
            val len = minOf(columns, bytes.size - offset)
            sb.append(String.format("%04X  ", offset))
            for (i in 0 until columns) {
                if (i < len) {
                    val v = bytes[offset + i].toInt() and 0xFF
                    sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F]).append(' ')
                } else sb.append("   ")
                if (i == 7) sb.append(' ')
            }
            sb.append(' ')
            for (i in 0 until len) {
                val c = bytes[offset + i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append('\n')
            offset += columns
        }
        return sb.toString()
    }
}
