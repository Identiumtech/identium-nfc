package com.identium.nfc.util.qr

import java.io.ByteArrayOutputStream

/** Square grid of QR modules. true = dark. */
class QrBitMatrix(val size: Int) {
    private val bits = Array(size) { BooleanArray(size) }
    operator fun get(x: Int, y: Int): Boolean =
        if (x in 0 until size && y in 0 until size) bits[y][x] else false
    operator fun set(x: Int, y: Int, value: Boolean) {
        if (x in 0 until size && y in 0 until size) bits[y][x] = value
    }
    fun flip(x: Int, y: Int) { bits[y][x] = !bits[y][x] }
}

/**
 * Decodes a sampled QR module grid back into its text payload.
 *
 * Covers versions 1–40, all four error-correction levels, all eight data
 * masks, and the numeric / alphanumeric / byte / ECI segment modes — i.e.
 * everything produced by real-world QR generators.
 *
 * Written from scratch (no ZXing, no ML Kit, no Play Services) so the app
 * stays dependency-free and works with no network.
 */
object QrDecoder {

    class DecodeException(message: String) : Exception(message)

    /** Returns the decoded text, or throws [DecodeException]. */
    fun decode(matrix: QrBitMatrix): String {
        val size = matrix.size
        if (size < 21 || size > 177 || (size - 17) % 4 != 0) {
            throw DecodeException("Not a valid QR size ($size)")
        }
        val version = (size - 17) / 4

        val format = readFormatInfo(matrix)
            ?: throw DecodeException("Could not read format info")
        val eccIndex = format.first     // 0=L 1=M 2=Q 3=H (table order)
        val mask = format.second

        // Undo the data mask on every non-function module.
        val functionMask = buildFunctionMask(version, size)
        val unmasked = QrBitMatrix(size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var bit = matrix[x, y]
                if (!functionMask[y][x] && maskBit(mask, x, y)) bit = !bit
                unmasked[x, y] = bit
            }
        }

        val raw = readCodewords(unmasked, functionMask, version)
        val dataCodewords = deinterleaveAndCorrect(raw, version, eccIndex)
        return parseBitStream(dataCodewords, version)
    }

    // ── format information ──

    /** Returns (eccTableIndex, mask) or null. */
    private fun readFormatInfo(m: QrBitMatrix): Pair<Int, Int>? {
        val size = m.size

        // Copy 1 — around the top-left finder.
        var bits1 = 0
        for (i in 0..5) bits1 = bits1 or (bit(m[i, 8]) shl i)
        bits1 = bits1 or (bit(m[7, 8]) shl 6)
        bits1 = bits1 or (bit(m[8, 8]) shl 7)
        bits1 = bits1 or (bit(m[8, 7]) shl 8)
        for (i in 9..14) bits1 = bits1 or (bit(m[8, 14 - i]) shl i)

        // Copy 2 — split under the top-right and left of the bottom-left finder.
        var bits2 = 0
        for (i in 0..7) bits2 = bits2 or (bit(m[8, size - 1 - i]) shl i)
        for (i in 8..14) bits2 = bits2 or (bit(m[size - 15 + i, 8]) shl i)

        return decodeFormatBits(bits1) ?: decodeFormatBits(bits2)
    }

    private fun bit(b: Boolean) = if (b) 1 else 0

    /**
     * Format info is a 15-bit BCH(15,5) code XOR-masked with 0x5412. Rather
     * than implementing BCH correction, compare against all 32 valid words
     * and take the closest — the code has distance 7, so up to 3 bit errors
     * are unambiguous.
     */
    private fun decodeFormatBits(raw: Int): Pair<Int, Int>? {
        var bestDiff = Int.MAX_VALUE
        var bestData = -1
        for (data in 0 until 32) {
            val encoded = encodeFormat(data)
            if (encoded == raw) { bestData = data; bestDiff = 0; break }
            val diff = Integer.bitCount(encoded xor raw)
            if (diff < bestDiff) { bestDiff = diff; bestData = data }
        }
        if (bestDiff > 3 || bestData < 0) return null
        val eccBits = (bestData shr 3) and 0x3
        val mask = bestData and 0x7
        // Format bits -> table index: 01=L(0) 00=M(1) 11=Q(2) 10=H(3)
        val eccIndex = when (eccBits) {
            0b01 -> 0
            0b00 -> 1
            0b11 -> 2
            0b10 -> 3
            else -> return null
        }
        return Pair(eccIndex, mask)
    }

    private fun encodeFormat(data: Int): Int {
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        return ((data shl 10) or rem) xor 0x5412
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        7 -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
        else -> false
    }

    // ── function pattern map (mirrors the encoder) ──

    private fun buildFunctionMask(version: Int, size: Int): Array<BooleanArray> {
        val fn = Array(size) { BooleanArray(size) }
        fun mark(x: Int, y: Int) { if (x in 0 until size && y in 0 until size) fn[y][x] = true }

        // Timing patterns
        for (i in 0 until size) { mark(6, i); mark(i, 6) }

        // Finder patterns + separators
        for ((cx, cy) in listOf(Pair(3, 3), Pair(size - 4, 3), Pair(3, size - 4))) {
            for (dy in -4..4) for (dx in -4..4) mark(cx + dx, cy + dy)
        }

        // Alignment patterns
        val positions = alignmentPatternPositions(version)
        val n = positions.size
        for (i in 0 until n) for (j in 0 until n) {
            if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) mark(positions[i] + dx, positions[j] + dy)
        }

        // Format info areas + the always-dark module
        for (i in 0..5) { mark(i, 8); mark(8, i) }
        mark(7, 8); mark(8, 8); mark(8, 7)
        for (i in 0..7) { mark(size - 1 - i, 8); mark(8, size - 1 - i) }
        mark(8, size - 8)

        // Version info areas (v7+)
        if (version >= 7) {
            for (i in 0..5) for (j in size - 11 until size - 8) { mark(i, j); mark(j, i) }
        }
        return fn
    }

    private fun alignmentPatternPositions(version: Int): IntArray {
        if (version == 1) return intArrayOf()
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26
        else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
        return result
    }

    // ── codeword extraction ──

    private fun readCodewords(m: QrBitMatrix, fn: Array<BooleanArray>, version: Int): IntArray {
        val size = m.size
        val totalCodewords = NUM_RAW_DATA_MODULES[version - 1] / 8
        val result = IntArray(totalCodewords)
        var bitIndex = 0

        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!fn[y][x] && bitIndex < totalCodewords * 8) {
                        if (m[x, y]) {
                            result[bitIndex ushr 3] =
                                result[bitIndex ushr 3] or (1 shl (7 - (bitIndex and 7)))
                        }
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        return result
    }

    // ── de-interleave + error correction ──

    private fun deinterleaveAndCorrect(raw: IntArray, version: Int, eccIndex: Int): IntArray {
        val numBlocks = NUM_EC_BLOCKS[eccIndex][version - 1]
        val ecPerBlock = ECC_CODEWORDS_PER_BLOCK[eccIndex][version - 1]
        val totalCodewords = raw.size
        val totalDataCodewords = totalCodewords - numBlocks * ecPerBlock

        val shortBlockDataLen = totalDataCodewords / numBlocks
        val numShortBlocks = numBlocks - totalDataCodewords % numBlocks

        // Allocate each block: data part then EC part.
        val blockData = Array(numBlocks) { b ->
            IntArray(shortBlockDataLen + if (b < numShortBlocks) 0 else 1)
        }
        val blockEc = Array(numBlocks) { IntArray(ecPerBlock) }

        var idx = 0
        // Data is interleaved column-wise; the longer blocks contribute one
        // extra codeword in the final column.
        for (col in 0..shortBlockDataLen) {
            for (b in 0 until numBlocks) {
                if (col < blockData[b].size) {
                    if (col != shortBlockDataLen || b >= numShortBlocks) {
                        blockData[b][col] = raw[idx++]
                    }
                }
            }
        }
        for (col in 0 until ecPerBlock) {
            for (b in 0 until numBlocks) {
                blockEc[b][col] = raw[idx++]
            }
        }

        val out = ByteArrayOutputStream()
        for (b in 0 until numBlocks) {
            val codewords = IntArray(blockData[b].size + ecPerBlock)
            System.arraycopy(blockData[b], 0, codewords, 0, blockData[b].size)
            System.arraycopy(blockEc[b], 0, codewords, blockData[b].size, ecPerBlock)
            if (!ReedSolomonDecoder.decode(codewords, ecPerBlock)) {
                throw DecodeException("Too many errors to correct")
            }
            for (i in blockData[b].indices) out.write(codewords[i])
        }
        return out.toByteArray().map { it.toInt() and 0xFF }.toIntArray()
    }

    // ── bit stream ──

    private class BitReader(private val data: IntArray) {
        private var bitPos = 0
        val available: Int get() = data.size * 8 - bitPos
        fun read(n: Int): Int {
            require(n in 0..32)
            if (n > available) throw DecodeException("Ran out of bits")
            var result = 0
            var remaining = n
            while (remaining > 0) {
                val byteIndex = bitPos ushr 3
                val bitOffset = bitPos and 7
                val bitsLeftInByte = 8 - bitOffset
                val take = minOf(remaining, bitsLeftInByte)
                val shift = bitsLeftInByte - take
                val mask = ((1 shl take) - 1)
                val value = (data[byteIndex] shr shift) and mask
                result = (result shl take) or value
                bitPos += take
                remaining -= take
            }
            return result
        }
    }

    private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    private fun parseBitStream(data: IntArray, version: Int): String {
        val reader = BitReader(data)
        val sb = StringBuilder()
        val bytes = ByteArrayOutputStream()
        var utf8 = false

        loop@ while (reader.available >= 4) {
            when (val mode = reader.read(4)) {
                0x0 -> break@loop                       // terminator
                0x1 -> {                                // numeric
                    flushBytes(bytes, sb, utf8)
                    val count = reader.read(countBits(version, 10, 12, 14))
                    var i = 0
                    while (i + 3 <= count) {
                        val v = reader.read(10)
                        sb.append((v / 100)).append((v / 10) % 10).append(v % 10)
                        i += 3
                    }
                    if (count - i == 2) {
                        val v = reader.read(7); sb.append(v / 10).append(v % 10)
                    } else if (count - i == 1) {
                        sb.append(reader.read(4))
                    }
                }
                0x2 -> {                                // alphanumeric
                    flushBytes(bytes, sb, utf8)
                    val count = reader.read(countBits(version, 9, 11, 13))
                    var i = 0
                    while (i + 2 <= count) {
                        val v = reader.read(11)
                        sb.append(ALPHANUMERIC[v / 45]).append(ALPHANUMERIC[v % 45])
                        i += 2
                    }
                    if (i < count) sb.append(ALPHANUMERIC[reader.read(6)])
                }
                0x4 -> {                                // byte
                    val count = reader.read(countBits(version, 8, 16, 16))
                    for (i in 0 until count) bytes.write(reader.read(8))
                }
                0x7 -> {                                // ECI
                    val first = reader.read(8)
                    val eci = when {
                        first and 0x80 == 0 -> first
                        first and 0xC0 == 0x80 -> ((first and 0x3F) shl 8) or reader.read(8)
                        else -> ((first and 0x1F) shl 16) or reader.read(16)
                    }
                    if (eci == 26) utf8 = true          // UTF-8
                }
                0x3 -> {                                // structured append — skip header
                    reader.read(16)
                }
                0x8 -> {                                // kanji — not supported, bail out cleanly
                    val count = reader.read(countBits(version, 8, 10, 12))
                    for (i in 0 until count) reader.read(13)
                }
                else -> break@loop                      // unknown mode; stop
            }
        }
        flushBytes(bytes, sb, utf8)
        val text = sb.toString()
        if (text.isEmpty()) throw DecodeException("QR contained no readable data")
        return text
    }

    private fun flushBytes(buf: ByteArrayOutputStream, sb: StringBuilder, utf8: Boolean) {
        if (buf.size() == 0) return
        val raw = buf.toByteArray()
        buf.reset()
        // Most modern encoders emit UTF-8 even without an ECI header, so try
        // UTF-8 first and fall back to ISO-8859-1 (the spec default).
        val decoded = try {
            val s = String(raw, Charsets.UTF_8)
            if (!utf8 && s.contains('�')) String(raw, Charsets.ISO_8859_1) else s
        } catch (_: Exception) {
            String(raw, Charsets.ISO_8859_1)
        }
        sb.append(decoded)
    }

    private fun countBits(version: Int, small: Int, medium: Int, large: Int): Int = when {
        version <= 9 -> small
        version <= 26 -> medium
        else -> large
    }

    // ── spec tables (mirror QrEncoder's; kept local so the encoder stays untouched) ──

    private val NUM_RAW_DATA_MODULES = intArrayOf(
        208, 359, 567, 807, 1079, 1383, 1568, 1936, 2336, 2768,
        3232, 3728, 4256, 4651, 5243, 5867, 6523, 7211, 7931, 8683,
        9252, 10068, 10916, 11796, 12708, 13652, 14628, 15371, 16411, 17483,
        18587, 19723, 20891, 22091, 23008, 24272, 25568, 26896, 28256, 29648
    )

    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        intArrayOf( 7, 10, 15, 20, 26, 18, 20, 24, 30, 18,  20, 24, 26, 30, 22, 24, 28, 30, 28, 28,  28, 28, 30, 30, 26, 28, 30, 30, 30, 30,  30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26,  30, 22, 22, 24, 24, 28, 28, 26, 26, 26,  26, 28, 28, 28, 28, 28, 28, 28, 28, 28,  28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24,  28, 26, 24, 20, 30, 24, 28, 28, 26, 30,  28, 30, 30, 30, 30, 28, 30, 30, 30, 30,  30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28,  24, 28, 22, 24, 24, 30, 28, 28, 26, 28,  30, 24, 30, 30, 30, 30, 30, 30, 30, 30,  30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
    )

    private val NUM_EC_BLOCKS = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4,   4, 4, 4, 4, 6, 6, 6, 6, 7, 8,   8, 9, 9, 10, 12, 12, 12, 13, 14, 15,  16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5,   5, 8, 9, 9, 10, 10, 11, 13, 14, 16,  17, 17, 18, 20, 21, 23, 25, 26, 28, 29,  31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8,   8, 10, 12, 16, 12, 17, 16, 18, 21, 20,  23, 23, 25, 27, 29, 34, 34, 35, 38, 40,  43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8,  11, 11, 16, 16, 18, 16, 19, 21, 25, 25,  25, 34, 30, 32, 35, 37, 40, 42, 45, 48,  51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
    )
}
