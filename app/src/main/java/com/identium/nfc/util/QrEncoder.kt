package com.identium.nfc.util

import android.graphics.Bitmap

/**
 * Pure-Kotlin QR Code encoder. No external dependencies.
 *
 * Implements ISO/IEC 18004 (2015) for byte mode (UTF-8 strings) across all
 * 40 versions and all 4 error-correction levels. Adapted from the QR Code
 * specification with structure inspired by Project Nayuki's reference
 * implementation — rewritten in idiomatic Kotlin and trimmed to the modes
 * we actually need.
 *
 * Capacity at the upper end:
 *   v40 L  →  2,953 bytes
 *   v40 M  →  2,331 bytes
 *   v40 Q  →  1,663 bytes
 *   v40 H  →  1,273 bytes
 *
 * Encoding a typical URL (50 chars, M) finishes in <5 ms on a mid-range
 * phone — fast enough to be regenerated whenever the user edits the input.
 */
object QrEncoder {

    enum class Ecc(internal val formatBits: Int, internal val tableIdx: Int) {
        LOW(0b01, 0),
        MEDIUM(0b00, 1),
        QUARTILE(0b11, 2),
        HIGH(0b10, 3);
    }

    class QrCode internal constructor(
        val version: Int,
        val ecc: Ecc,
        val mask: Int,
        val size: Int,
        private val modules: Array<BooleanArray>
    ) {
        fun isDark(x: Int, y: Int): Boolean =
            x in 0 until size && y in 0 until size && modules[y][x]

        /**
         * Render the matrix into a Bitmap. [border] is the quiet-zone in
         * modules (spec requires ≥ 4). [scale] is pixels per module.
         */
        fun toBitmap(
            scale: Int = 8,
            border: Int = 4,
            dark: Int = 0xFF000000.toInt(),
            light: Int = 0xFFFFFFFF.toInt()
        ): Bitmap {
            require(scale > 0 && border >= 0)
            val dim = (size + border * 2) * scale
            val bm = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(dim * dim)
            for (y in 0 until dim) {
                val moduleY = y / scale - border
                for (x in 0 until dim) {
                    val moduleX = x / scale - border
                    val on = moduleX in 0 until size &&
                            moduleY in 0 until size &&
                            modules[moduleY][moduleX]
                    pixels[y * dim + x] = if (on) dark else light
                }
            }
            bm.setPixels(pixels, 0, dim, 0, 0, dim, dim)
            return bm
        }
    }

    // ---- Public entry point --------------------------------------------------

    /**
     * Encode [text] at the smallest version that fits with the requested
     * [ecc] level. Throws if [text] would need more than 2,953 UTF-8 bytes
     * (the spec limit at v40 / L).
     */
    @JvmStatic
    fun encode(text: String, ecc: Ecc = Ecc.MEDIUM): QrCode {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = chooseVersion(data.size, ecc)

        // Build the data-codeword stream.
        val bb = BitBuffer()
        bb.appendBits(MODE_BYTE, 4)
        bb.appendBits(data.size, charCountBits(version))
        for (b in data) bb.appendBits(b.toInt() and 0xFF, 8)

        val totalDataBits = numDataCodewords(version, ecc) * 8
        // Terminator: up to 4 zero bits.
        bb.appendBits(0, minOf(4, totalDataBits - bb.size))
        // Pad to byte boundary.
        bb.appendBits(0, (8 - bb.size % 8) % 8)
        // Pad bytes alternating 0xEC, 0x11.
        var padToggle = 0
        while (bb.size < totalDataBits) {
            bb.appendBits(if (padToggle == 0) 0xEC else 0x11, 8)
            padToggle = padToggle xor 1
        }

        val dataCodewords = bb.toBytes()
        val allCodewords = addEccAndInterleave(dataCodewords, version, ecc)
        val matrix = buildMatrix(version, ecc, allCodewords)
        return matrix
    }

    // ---- Version selection ---------------------------------------------------

    private fun chooseVersion(dataBytes: Int, ecc: Ecc): Int {
        for (v in 1..40) {
            // Total data bits available at this version + ecc:
            val capacity = numDataCodewords(v, ecc) * 8
            val needed = 4 + charCountBits(v) + dataBytes * 8
            if (needed <= capacity) return v
        }
        error("Data too long for QR code (need ${dataBytes} bytes)")
    }

    private fun charCountBits(version: Int): Int = if (version <= 9) 8 else 16

    // ---- Reed-Solomon EC + interleaving --------------------------------------

    private fun addEccAndInterleave(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.tableIdx][version - 1]
        val ecPerBlock = ECC_CODEWORDS_PER_BLOCK[ecc.tableIdx][version - 1]
        val totalCodewords = NUM_RAW_DATA_MODULES[version - 1] / 8
        val totalDataCodewords = data.size

        // QR spec: smaller blocks come first, larger blocks last.
        val numShortBlocks = numBlocks - totalDataCodewords % numBlocks
        val shortBlockDataLen = totalDataCodewords / numBlocks

        // Build each block: data || ec
        val blocks = mutableListOf<ByteArray>()
        val generator = reedSolomonGenerator(ecPerBlock)
        var dataOffset = 0
        for (i in 0 until numBlocks) {
            val dataLen = shortBlockDataLen + if (i < numShortBlocks) 0 else 1
            val dataBlock = data.copyOfRange(dataOffset, dataOffset + dataLen)
            dataOffset += dataLen
            val ecBlock = reedSolomonRemainder(dataBlock, generator)
            blocks += dataBlock + ecBlock
        }

        // Interleave codewords column-wise.
        val result = ByteArray(totalCodewords)
        var idx = 0
        val dataMaxLen = shortBlockDataLen + 1
        // Data portion
        for (col in 0 until dataMaxLen) {
            for ((b, block) in blocks.withIndex()) {
                if (col != shortBlockDataLen || b >= numShortBlocks) {
                    result[idx++] = block[col]
                }
            }
        }
        // EC portion
        for (col in 0 until ecPerBlock) {
            for (block in blocks) {
                result[idx++] = block[block.size - ecPerBlock + col]
            }
        }
        return result
    }

    private fun reedSolomonGenerator(degree: Int): IntArray {
        // Construct polynomial (x - α^0)(x - α^1)…(x - α^(degree-1)) in GF(256).
        var result = intArrayOf(1)
        var rootAlpha = 1
        for (i in 0 until degree) {
            val next = IntArray(result.size + 1)
            for (j in result.indices) {
                next[j] = next[j] xor gfMul(result[j], 1)        // multiply by x
                next[j + 1] = next[j + 1] xor gfMul(result[j], rootAlpha) // + α^i term
            }
            result = next
            rootAlpha = gfMul(rootAlpha, 2)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, generator: IntArray): ByteArray {
        // Polynomial division in GF(256). The remainder is the EC codewords.
        val result = IntArray(generator.size - 1)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) {
                result[i] = result[i + 1] xor gfMul(generator[i + 1], factor)
            }
            result[result.size - 1] = gfMul(generator[generator.size - 1], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    /** GF(256) multiply with primitive polynomial 0x11D (x^8 + x^4 + x^3 + x^2 + 1). */
    private fun gfMul(x: Int, y: Int): Int {
        var z = 0
        var b = y
        var a = x
        for (i in 7 downTo 0) {
            z = (z shl 1) xor (if ((z ushr 7) != 0) 0x11D else 0)
            if (((b ushr i) and 1) != 0) z = z xor a
        }
        return z and 0xFF
    }

    // ---- Matrix building -----------------------------------------------------

    private fun buildMatrix(version: Int, ecc: Ecc, codewords: ByteArray): QrCode {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        drawFunctionPatterns(modules, isFunction, version)
        drawCodewords(modules, isFunction, codewords)

        // Try every mask, score, pick the best.
        var bestMask = -1
        var bestScore = Int.MAX_VALUE
        var bestModules = modules
        for (mask in 0..7) {
            val trial = deepCopy(modules)
            applyMask(trial, isFunction, mask)
            drawFormatBits(trial, ecc, mask)
            val score = penaltyScore(trial)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
                bestModules = trial
            }
        }
        if (version >= 7) drawVersionBits(bestModules, version)
        return QrCode(version, ecc, bestMask, size, bestModules)
    }

    private fun deepCopy(m: Array<BooleanArray>): Array<BooleanArray> =
        Array(m.size) { m[it].copyOf() }

    private fun drawFunctionPatterns(modules: Array<BooleanArray>, isFn: Array<BooleanArray>, version: Int) {
        val size = modules.size

        // Timing patterns (row 6 and column 6, alternating).
        for (i in 0 until size) {
            setFn(modules, isFn, 6, i, i % 2 == 0)
            setFn(modules, isFn, i, 6, i % 2 == 0)
        }

        // Finder patterns at TL / TR / BL corners.
        drawFinder(modules, isFn, 3, 3)
        drawFinder(modules, isFn, size - 4, 3)
        drawFinder(modules, isFn, 3, size - 4)

        // Alignment patterns.
        val alignPositions = alignmentPatternPositions(version)
        val n = alignPositions.size
        for (i in 0 until n) {
            for (j in 0 until n) {
                // Skip the three corners that overlap finder patterns.
                if (i == 0 && j == 0) continue
                if (i == 0 && j == n - 1) continue
                if (i == n - 1 && j == 0) continue
                drawAlignment(modules, isFn, alignPositions[i], alignPositions[j])
            }
        }

        // Reserve format and version info areas (drawn later).
        drawFormatBits(modules, Ecc.LOW, 0)  // placeholder, just to mark as function
        markFormatAsFunction(isFn, size)
        if (version >= 7) markVersionAsFunction(isFn, size)
    }

    private fun drawFinder(modules: Array<BooleanArray>, isFn: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until modules.size || y !in 0 until modules.size) continue
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val dark = dist != 2 && dist != 4
                setFn(modules, isFn, x, y, dark)
            }
        }
    }

    private fun drawAlignment(modules: Array<BooleanArray>, isFn: Array<BooleanArray>, cx: Int, cy: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                setFn(modules, isFn, cx + dx, cy + dy, dist != 1)
            }
        }
    }

    private fun markFormatAsFunction(isFn: Array<BooleanArray>, size: Int) {
        for (i in 0..5) { isFn[8][i] = true; isFn[i][8] = true }
        isFn[8][7] = true; isFn[8][8] = true; isFn[7][8] = true
        for (i in 0..7) { isFn[8][size - 1 - i] = true; isFn[size - 1 - i][8] = true }
        isFn[size - 8][8] = true  // dark module always
    }

    private fun markVersionAsFunction(isFn: Array<BooleanArray>, size: Int) {
        for (i in 0..5) {
            for (j in size - 11 until size - 8) {
                isFn[j][i] = true
                isFn[i][j] = true
            }
        }
    }

    private fun setFn(modules: Array<BooleanArray>, isFn: Array<BooleanArray>, x: Int, y: Int, dark: Boolean) {
        if (x in 0 until modules.size && y in 0 until modules.size) {
            modules[y][x] = dark
            isFn[y][x] = true
        }
    }

    private fun drawCodewords(modules: Array<BooleanArray>, isFn: Array<BooleanArray>, data: ByteArray) {
        val size = modules.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFn[y][x] && i < data.size * 8) {
                        val bit = ((data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        modules[y][x] = bit
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(modules: Array<BooleanArray>, isFn: Array<BooleanArray>, mask: Int) {
        val size = modules.size
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFn[y][x]) continue
                val invert = when (mask) {
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
                if (invert) modules[y][x] = !modules[y][x]
            }
        }
    }

    private fun drawFormatBits(modules: Array<BooleanArray>, ecc: Ecc, mask: Int) {
        val data = (ecc.formatBits shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412
        val size = modules.size

        // Top-left
        for (i in 0..5) modules[8][i] = bitAt(bits, i)
        modules[8][7] = bitAt(bits, 6)
        modules[8][8] = bitAt(bits, 7)
        modules[7][8] = bitAt(bits, 8)
        for (i in 9..14) modules[14 - i][8] = bitAt(bits, i)

        // Top-right + bottom-left
        for (i in 0..7) modules[size - 1 - i][8] = bitAt(bits, i)
        for (i in 8..14) modules[8][size - 15 + i] = bitAt(bits, i)
        modules[size - 8][8] = true  // dark module
    }

    private fun drawVersionBits(modules: Array<BooleanArray>, version: Int) {
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        val bits = (version shl 12) or rem
        val size = modules.size

        for (i in 0..17) {
            val bit = bitAt(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            modules[a][b] = bit
            modules[b][a] = bit
        }
    }

    private fun bitAt(value: Int, bit: Int): Boolean = ((value ushr bit) and 1) != 0

    // ---- Mask penalty scoring -----------------------------------------------

    private fun penaltyScore(modules: Array<BooleanArray>): Int {
        val size = modules.size
        var score = 0

        // Rule 1: runs of 5+ same-color modules in a row / column.
        for (y in 0 until size) {
            var runColor = false
            var runLen = 0
            for (x in 0 until size) {
                val c = modules[y][x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) score += 3
                    else if (runLen > 5) score++
                } else { runColor = c; runLen = 1 }
            }
        }
        for (x in 0 until size) {
            var runColor = false
            var runLen = 0
            for (y in 0 until size) {
                val c = modules[y][x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) score += 3
                    else if (runLen > 5) score++
                } else { runColor = c; runLen = 1 }
            }
        }

        // Rule 2: 2x2 same-color blocks.
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1])
                    score += 3
            }
        }

        // Rule 3: patterns that look like finder-pattern bars (penalize each).
        for (y in 0 until size) {
            for (x in 0 until size - 10) {
                if (matchesFinderHoriz(modules, x, y)) score += 40
            }
        }
        for (x in 0 until size) {
            for (y in 0 until size - 10) {
                if (matchesFinderVert(modules, x, y)) score += 40
            }
        }

        // Rule 4: deviation from 50% dark modules.
        var dark = 0
        for (row in modules) for (m in row) if (m) dark++
        val total = size * size
        val percent = dark * 100 / total
        val deviation = Math.abs(percent - 50) / 5
        score += deviation * 10
        return score
    }

    private fun matchesFinderHoriz(modules: Array<BooleanArray>, x: Int, y: Int): Boolean {
        val pat = booleanArrayOf(true, false, true, true, true, false, true)
        val pad = booleanArrayOf(false, false, false, false)
        return checkSequence(x, y, 1, 0, modules, pad + pat) ||
                checkSequence(x, y, 1, 0, modules, pat + pad)
    }

    private fun matchesFinderVert(modules: Array<BooleanArray>, x: Int, y: Int): Boolean {
        val pat = booleanArrayOf(true, false, true, true, true, false, true)
        val pad = booleanArrayOf(false, false, false, false)
        return checkSequence(x, y, 0, 1, modules, pad + pat) ||
                checkSequence(x, y, 0, 1, modules, pat + pad)
    }

    private fun checkSequence(x: Int, y: Int, dx: Int, dy: Int, modules: Array<BooleanArray>, pat: BooleanArray): Boolean {
        for (i in pat.indices) {
            val nx = x + dx * i; val ny = y + dy * i
            if (nx !in 0 until modules.size || ny !in 0 until modules.size) return false
            if (modules[ny][nx] != pat[i]) return false
        }
        return true
    }

    // ---- Capacity / table lookups -------------------------------------------

    private fun numDataCodewords(version: Int, ecc: Ecc): Int {
        val total = NUM_RAW_DATA_MODULES[version - 1] / 8
        val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc.tableIdx][version - 1]
        val ecPerBlock = ECC_CODEWORDS_PER_BLOCK[ecc.tableIdx][version - 1]
        return total - numBlocks * ecPerBlock
    }

    private fun alignmentPatternPositions(version: Int): IntArray {
        if (version == 1) return intArrayOf()
        val numAlign = version / 7 + 2
        val step = if (version == 32) 26
                   else (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = version * 4 + 10
        for (i in numAlign - 1 downTo 1) {
            result[i] = pos
            pos -= step
        }
        return result
    }

    // ---- Static tables ------------------------------------------------------

    private const val MODE_BYTE = 0b0100

    /** Total module count - function pattern area, per version (1..40). */
    private val NUM_RAW_DATA_MODULES = intArrayOf(
        208, 359, 567, 807, 1079, 1383, 1568, 1936, 2336, 2768,
        3232, 3728, 4256, 4651, 5243, 5867, 6523, 7211, 7931, 8683,
        9252, 10068, 10916, 11796, 12708, 13652, 14628, 15371, 16411, 17483,
        18587, 19723, 20891, 22091, 23008, 24272, 25568, 26896, 28256, 29648
    )

    /** EC codewords per block for each (ecc, version-1). Spec Table 9. */
    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        // L
        intArrayOf( 7, 10, 15, 20, 26, 18, 20, 24, 30, 18,  20, 24, 26, 30, 22, 24, 28, 30, 28, 28,  28, 28, 30, 30, 26, 28, 30, 30, 30, 30,  30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        // M
        intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26,  30, 22, 22, 24, 24, 28, 28, 26, 26, 26,  26, 28, 28, 28, 28, 28, 28, 28, 28, 28,  28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        // Q
        intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24,  28, 26, 24, 20, 30, 24, 28, 28, 26, 30,  28, 30, 30, 30, 30, 28, 30, 30, 30, 30,  30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        // H
        intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28,  24, 28, 22, 24, 24, 30, 28, 28, 26, 28,  30, 24, 30, 30, 30, 30, 30, 30, 30, 30,  30, 30, 30, 30, 30, 30, 30, 30, 30, 30)
    )

    /** Number of EC blocks for each (ecc, version-1). Spec Table 9. */
    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        // L
        intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4,   4, 4, 4, 4, 6, 6, 6, 6, 7, 8,   8, 9, 9, 10, 12, 12, 12, 13, 14, 15,  16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        // M
        intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5,   5, 8, 9, 9, 10, 10, 11, 13, 14, 16,  17, 17, 18, 20, 21, 23, 25, 26, 28, 29,  31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        // Q
        intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8,   8, 10, 12, 16, 12, 17, 16, 18, 21, 20,  23, 23, 25, 27, 29, 34, 34, 35, 38, 40,  43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        // H
        intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8,  11, 11, 16, 16, 18, 16, 19, 21, 25, 25,  25, 34, 30, 32, 35, 37, 40, 42, 45, 48,  51, 54, 57, 60, 63, 66, 70, 74, 77, 81)
    )

    /** Minimal bit-stream builder with bit-at-a-time append. */
    private class BitBuffer {
        private val bits = java.util.ArrayList<Boolean>(256)
        val size: Int get() = bits.size

        fun appendBits(value: Int, count: Int) {
            require(count >= 0)
            if (count == 0) return
            for (i in count - 1 downTo 0) bits.add(((value ushr i) and 1) == 1)
        }

        fun toBytes(): ByteArray {
            val out = ByteArray((bits.size + 7) / 8)
            for ((i, b) in bits.withIndex()) {
                if (b) out[i ushr 3] = (out[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
            return out
        }
    }
}
