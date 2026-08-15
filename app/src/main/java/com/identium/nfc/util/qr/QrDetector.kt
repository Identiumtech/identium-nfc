package com.identium.nfc.util.qr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Locates a QR code inside a grayscale camera frame and samples it into a
 * [QrBitMatrix] that [QrDecoder] can read.
 *
 * Pipeline: adaptive binarisation → finder-pattern search (the 1:1:3:1:1
 * runs in the three corner squares) → corner ordering → optional alignment
 * pattern → perspective transform → module sampling.
 *
 * Written from scratch, no third-party vision library.
 */
object QrDetector {

    class DetectException(message: String) : Exception(message)

    /**
     * @param luma  grayscale bytes, one per pixel, row-major
     * @param width  frame width in pixels
     * @param height frame height in pixels
     */
    fun detectAndSample(luma: ByteArray, width: Int, height: Int): QrBitMatrix {
        val binary = binarize(luma, width, height)
        val finders = findFinderPatterns(binary, width, height)
        if (finders.size < 3) throw DetectException("Fewer than 3 finder patterns")

        // Pick the triple that actually looks like a QR's three corners
        // rather than blindly taking the three most-seen candidates — noisy
        // frames throw up plenty of small false positives.
        val (tl, tr, bl) = bestTriple(finders)
            ?: throw DetectException("No consistent finder triple")

        val moduleSize = (tl.moduleSize + tr.moduleSize + bl.moduleSize) / 3f
        if (moduleSize < 1f) throw DetectException("Module size too small")

        var dimension = computeDimension(tl, tr, bl, moduleSize)
        if (dimension < 21) dimension = 21
        if (dimension > 177) throw DetectException("Dimension out of range ($dimension)")
        val version = (dimension - 17) / 4
        if (version < 1 || version > 40) throw DetectException("Bad version from dimension")

        // Estimate the bottom-right corner, then try to refine it with the
        // alignment pattern (present from version 2 upward).
        val estBrX = tr.x - tl.x + bl.x
        val estBrY = tr.y - tl.y + bl.y

        var bottomRight: FinderPoint? = null
        var bottomRightGrid = dimension - 3.5f
        if (version >= 2) {
            val modulesBetween = dimension - 7f
            val correction = 1f - 3f / modulesBetween
            val alignX = tl.x + correction * (estBrX - tl.x)
            val alignY = tl.y + correction * (estBrY - tl.y)
            bottomRight = findAlignmentPattern(
                binary, width, height, alignX, alignY, moduleSize
            )
            if (bottomRight != null) bottomRightGrid = dimension - 6.5f
        }

        val brX = bottomRight?.x ?: estBrX
        val brY = bottomRight?.y ?: estBrY

        val transform = PerspectiveTransform.quadrilateralToQuadrilateral(
            3.5f, 3.5f,
            dimension - 3.5f, 3.5f,
            bottomRightGrid, bottomRightGrid,
            3.5f, dimension - 3.5f,
            tl.x, tl.y,
            tr.x, tr.y,
            brX, brY,
            bl.x, bl.y
        )

        val matrix = QrBitMatrix(dimension)
        val point = FloatArray(2)
        for (y in 0 until dimension) {
            for (x in 0 until dimension) {
                point[0] = x + 0.5f
                point[1] = y + 0.5f
                transform.transform(point)
                var px = point[0].roundToInt()
                var py = point[1].roundToInt()
                // Clamp rather than abort. Under perspective the estimated far
                // corner can push edge modules just outside the frame; a few
                // wrong edge modules are exactly what the Reed-Solomon parity
                // repairs, whereas bailing out throws away a good frame.
                val slack = (moduleSize * 2).toInt() + 2
                if (px < -slack || py < -slack || px > width + slack || py > height + slack) {
                    throw DetectException("Sample point far outside the frame")
                }
                px = px.coerceIn(0, width - 1)
                py = py.coerceIn(0, height - 1)
                matrix[x, y] = binary[py * width + px]
            }
        }
        return matrix
    }

    // ── binarisation ──

    private const val BLOCK_POWER = 3          // 8x8 blocks
    private const val BLOCK_SIZE = 1 shl BLOCK_POWER
    private const val MIN_DYNAMIC_RANGE = 24

    /**
     * Adaptive threshold: each 8x8 block is thresholded using the average of
     * its 5x5 block neighbourhood. Handles uneven lighting far better than a
     * single global threshold, which matters when a phone shadows the tag.
     */
    /**
     * Separable 3x3 box blur. Cheap, and it stops sensor noise from breaking
     * the run-length ratios that finder detection depends on — without it a
     * grainy frame loses whole finder patterns.
     */
    private fun smooth3(luma: ByteArray, width: Int, height: Int): IntArray {
        val tmp = IntArray(width * height)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val ro = y * width
            for (x in 0 until width) {
                val a = luma[ro + max(0, x - 1)].toInt() and 0xFF
                val b = luma[ro + x].toInt() and 0xFF
                val c = luma[ro + min(width - 1, x + 1)].toInt() and 0xFF
                tmp[ro + x] = (a + b + c) / 3
            }
        }
        for (y in 0 until height) {
            val ro = y * width
            val up = max(0, y - 1) * width
            val dn = min(height - 1, y + 1) * width
            for (x in 0 until width) out[ro + x] = (tmp[up + x] + tmp[ro + x] + tmp[dn + x]) / 3
        }
        return out
    }

    private fun binarize(lumaRaw: ByteArray, width: Int, height: Int): BooleanArray {
        val luma = smooth3(lumaRaw, width, height)
        val out = BooleanArray(width * height)
        val blocksX = (width + BLOCK_SIZE - 1) / BLOCK_SIZE
        val blocksY = (height + BLOCK_SIZE - 1) / BLOCK_SIZE
        val averages = IntArray(blocksX * blocksY)

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                var sum = 0
                var minV = 255
                var maxV = 0
                var count = 0
                val yStart = by * BLOCK_SIZE
                val xStart = bx * BLOCK_SIZE
                for (y in yStart until min(yStart + BLOCK_SIZE, height)) {
                    val rowOffset = y * width
                    for (x in xStart until min(xStart + BLOCK_SIZE, width)) {
                        val v = luma[rowOffset + x]
                        sum += v; count++
                        if (v < minV) minV = v
                        if (v > maxV) maxV = v
                    }
                }
                var average = if (count > 0) sum / count else 128
                if (maxV - minV <= MIN_DYNAMIC_RANGE) {
                    // Nearly flat block — likely all background. Bias toward
                    // white so smooth areas don't turn into false blacks.
                    average = minV / 2
                    if (by > 0 && bx > 0) {
                        val neighbourAvg = (averages[(by - 1) * blocksX + bx] +
                                2 * averages[by * blocksX + bx - 1] +
                                averages[(by - 1) * blocksX + bx - 1]) / 4
                        if (minV < neighbourAvg) average = neighbourAvg
                    }
                }
                averages[by * blocksX + bx] = average
            }
        }

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val left = (bx - 2).coerceIn(0, blocksX - 1)
                val right = (bx + 2).coerceIn(0, blocksX - 1)
                val top = (by - 2).coerceIn(0, blocksY - 1)
                val bottom = (by + 2).coerceIn(0, blocksY - 1)
                var sum = 0
                var n = 0
                for (yy in top..bottom) for (xx in left..right) {
                    sum += averages[yy * blocksX + xx]; n++
                }
                val threshold = sum / n
                val yStart = by * BLOCK_SIZE
                val xStart = bx * BLOCK_SIZE
                for (y in yStart until min(yStart + BLOCK_SIZE, height)) {
                    val rowOffset = y * width
                    for (x in xStart until min(xStart + BLOCK_SIZE, width)) {
                        val v = luma[rowOffset + x]
                        out[rowOffset + x] = v < threshold      // true = dark
                    }
                }
            }
        }
        return out
    }

    // ── finder patterns ──

    data class FinderPoint(val x: Float, val y: Float, val moduleSize: Float, var count: Int = 1)

    /**
     * Scans rows for the 1:1:3:1:1 dark/light run signature of a finder
     * pattern, then cross-checks vertically before accepting a candidate.
     */
    private fun findFinderPatterns(binary: BooleanArray, width: Int, height: Int): List<FinderPoint> {
        val found = mutableListOf<FinderPoint>()
        val stateCount = IntArray(5)
        // Skip rows for speed; QR finders are several pixels tall.
        val rowStep = max(1, height / 240)

        var y = rowStep
        while (y < height) {
            stateCount.fill(0)
            var currentState = 0
            val rowOffset = y * width
            for (x in 0 until width) {
                val dark = binary[rowOffset + x]
                if (dark) {
                    if (currentState % 2 == 1) currentState++      // white -> black
                    stateCount[currentState]++
                } else {
                    if (currentState % 2 == 0) {                    // black -> white
                        if (currentState == 4) {
                            if (isFinderRatio(stateCount)) {
                                val centerX = centerFromEnd(stateCount, x)
                                val module = (stateCount.sum()) / 7f
                                val centerY = crossCheckVertical(
                                    binary, width, height, centerX.toInt(), y, stateCount[2], stateCount.sum()
                                )
                                if (centerY != null) {
                                    addOrMerge(found, FinderPoint(centerX, centerY, module))
                                }
                            }
                            // Shift the window and keep scanning this row.
                            stateCount[0] = stateCount[2]
                            stateCount[1] = stateCount[3]
                            stateCount[2] = stateCount[4]
                            stateCount[3] = 1
                            stateCount[4] = 0
                            currentState = 3
                        } else {
                            currentState++
                            stateCount[currentState]++
                        }
                    } else {
                        stateCount[currentState]++
                    }
                }
            }
            // End-of-row check
            if (currentState == 4 && isFinderRatio(stateCount)) {
                val centerX = centerFromEnd(stateCount, width)
                val module = stateCount.sum() / 7f
                val centerY = crossCheckVertical(
                    binary, width, height, centerX.toInt(), y, stateCount[2], stateCount.sum()
                )
                if (centerY != null) addOrMerge(found, FinderPoint(centerX, centerY, module))
            }
            y += rowStep
        }
        // Prefer candidates seen on multiple rows — those are real patterns.
        return found.sortedByDescending { it.count }
    }

    private fun isFinderRatio(stateCount: IntArray): Boolean {
        var total = 0
        for (i in 0 until 5) {
            if (stateCount[i] == 0) return false
            total += stateCount[i]
        }
        if (total < 7) return false
        val moduleSize = total / 7f
        val maxVariance = moduleSize / 1.6f
        return abs(moduleSize - stateCount[0]) < maxVariance &&
                abs(moduleSize - stateCount[1]) < maxVariance &&
                abs(3f * moduleSize - stateCount[2]) < 3f * maxVariance &&
                abs(moduleSize - stateCount[3]) < maxVariance &&
                abs(moduleSize - stateCount[4]) < maxVariance
    }

    private fun centerFromEnd(stateCount: IntArray, end: Int): Float =
        (end - stateCount[4] - stateCount[3]) - stateCount[2] / 2f

    private fun crossCheckVertical(
        binary: BooleanArray, width: Int, height: Int,
        centerX: Int, centerY: Int, maxCount: Int, originalTotal: Int
    ): Float? {
        if (centerX < 0 || centerX >= width) return null
        val stateCount = IntArray(5)
        var y = centerY

        // Walk up through the centre black run, then the white and black rings.
        while (y >= 0 && binary[y * width + centerX]) { stateCount[2]++; y-- }
        if (y < 0) return null
        while (y >= 0 && !binary[y * width + centerX] && stateCount[1] <= maxCount) { stateCount[1]++; y-- }
        if (y < 0 || stateCount[1] > maxCount) return null
        while (y >= 0 && binary[y * width + centerX] && stateCount[0] <= maxCount) { stateCount[0]++; y-- }
        if (stateCount[0] > maxCount) return null

        y = centerY + 1
        while (y < height && binary[y * width + centerX]) { stateCount[2]++; y++ }
        if (y == height) return null
        while (y < height && !binary[y * width + centerX] && stateCount[3] < maxCount) { stateCount[3]++; y++ }
        if (y == height || stateCount[3] >= maxCount) return null
        while (y < height && binary[y * width + centerX] && stateCount[4] < maxCount) { stateCount[4]++; y++ }
        if (stateCount[4] >= maxCount) return null

        val total = stateCount.sum()
        if (5 * abs(total - originalTotal) >= 2 * originalTotal) return null
        return if (isFinderRatio(stateCount)) centerFromEnd(stateCount, y) else null
    }

    private fun addOrMerge(found: MutableList<FinderPoint>, candidate: FinderPoint) {
        for (i in found.indices) {
            val p = found[i]
            if (abs(p.x - candidate.x) <= p.moduleSize * 2 &&
                abs(p.y - candidate.y) <= p.moduleSize * 2
            ) {
                found[i] = FinderPoint(
                    (p.x * p.count + candidate.x) / (p.count + 1),
                    (p.y * p.count + candidate.y) / (p.count + 1),
                    (p.moduleSize * p.count + candidate.moduleSize) / (p.count + 1),
                    p.count + 1
                )
                return
            }
        }
        found.add(candidate)
    }

    /**
     * Score every combination of three candidates and keep the one that best
     * matches a QR's geometry: similar module sizes, two roughly equal legs
     * from the top-left corner, and a hypotenuse of about sqrt(2) legs.
     */
    private fun bestTriple(candidates: List<FinderPoint>): Triple<FinderPoint, FinderPoint, FinderPoint>? {
        val pool = candidates.take(8)
        if (pool.size < 3) return null
        var best: Triple<FinderPoint, FinderPoint, FinderPoint>? = null
        var bestScore = Float.MAX_VALUE
        for (i in pool.indices) {
            for (j in i + 1 until pool.size) {
                for (k in j + 1 until pool.size) {
                    val combo = listOf(pool[i], pool[j], pool[k])
                    val sizes = combo.map { it.moduleSize }
                    val avg = sizes.sum() / 3f
                    if (avg <= 0f) continue
                    val sizeDev = (sizes.max() - sizes.min()) / avg
                    val ordered = orderFinders(combo)
                    val d1 = distance(ordered.first, ordered.second)
                    val d2 = distance(ordered.first, ordered.third)
                    val d3 = distance(ordered.second, ordered.third)
                    // Corners must be at least a few modules apart.
                    if (d1 < avg * 5 || d2 < avg * 5) continue
                    val legDev = abs(d1 - d2) / max(d1, d2)
                    val hyp = sqrt(d1 * d1 + d2 * d2)
                    val hypDev = abs(d3 - hyp) / max(hyp, 1f)
                    val score = sizeDev * 2f + legDev + hypDev * 2f
                    if (score < bestScore) { bestScore = score; best = ordered }
                }
            }
        }
        return best
    }

    /** Returns (topLeft, topRight, bottomLeft). */
    private fun orderFinders(all: List<FinderPoint>): Triple<FinderPoint, FinderPoint, FinderPoint> {
        val p = all.take(3)
        val a = p[0]; val b = p[1]; val c = p[2]
        val ab = distance(a, b); val bc = distance(b, c); val ac = distance(a, c)

        // The corner opposite the longest side is the top-left.
        val (topLeft, other1, other2) = when {
            bc >= ab && bc >= ac -> Triple(a, b, c)
            ac >= ab && ac >= bc -> Triple(b, a, c)
            else -> Triple(c, a, b)
        }

        // Cross product decides which of the remaining two is top-right.
        val cross = (other1.x - topLeft.x) * (other2.y - topLeft.y) -
                (other1.y - topLeft.y) * (other2.x - topLeft.x)
        return if (cross < 0) Triple(topLeft, other2, other1)
        else Triple(topLeft, other1, other2)
    }

    private fun distance(a: FinderPoint, b: FinderPoint): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun computeDimension(
        tl: FinderPoint, tr: FinderPoint, bl: FinderPoint, moduleSize: Float
    ): Int {
        val tltr = (distance(tl, tr) / moduleSize).roundToInt()
        val tlbl = (distance(tl, bl) / moduleSize).roundToInt()
        var dimension = ((tltr + tlbl) / 2) + 7
        // Valid dimensions are 4*version+17, i.e. always ≡ 1 (mod 4).
        when (dimension % 4) {
            0 -> dimension++
            2 -> dimension--
            3 -> dimension += 2
        }
        return dimension
    }

    // ── alignment pattern (1:1:1 dark/light/dark) ──

    /**
     * Locate the bottom-right alignment pattern.
     *
     * The signature searched for is the inner light-dark-light of the
     * pattern, NOT the outer dark ring: the outer ring frequently merges with
     * neighbouring dark data modules, which breaks any ratio test based on
     * it, whereas the 1-module light ring around the dark centre is always
     * intact by construction. Candidates are then verified against the full
     * 5x5 template and gated on proximity to the predicted position, so
     * ordinary isolated dark modules in the data can't masquerade as it.
     */
    private fun findAlignmentPattern(
        binary: BooleanArray, width: Int, height: Int,
        estX: Float, estY: Float, moduleSize: Float
    ): FinderPoint? {
        val allowance = max(10, (moduleSize * 9).toInt())
        val left = max(0, (estX - allowance).toInt())
        val right = min(width - 1, (estX + allowance).toInt())
        val top = max(0, (estY - allowance).toInt())
        val bottom = min(height - 1, (estY + allowance).toInt())
        if (right - left < moduleSize * 4 || bottom - top < moduleSize * 4) return null

        var best: FinderPoint? = null
        var bestDist = Float.MAX_VALUE
        val tolerance = moduleSize / 1.7f

        for (y in top..bottom) {
            val rowOffset = y * width
            var x = left
            while (x <= right) {
                // Walk one run at a time, looking for light | dark | light.
                val runStart = x
                val value = binary[rowOffset + x]
                while (x <= right && binary[rowOffset + x] == value) x++
                val runLen = x - runStart
                if (value) {
                    // A dark run: check the light runs either side.
                    val leftLight = runStart > left && !binary[rowOffset + runStart - 1]
                    val rightLight = x <= right && !binary[rowOffset + x]
                    if (leftLight && rightLight && abs(runLen - moduleSize) < tolerance) {
                        val cx = runStart + runLen / 2f
                        val cy = verticalCenterOfDarkRun(binary, width, height, cx.roundToInt(), y, moduleSize)
                        if (cy != null && alignmentTemplateMatches(binary, width, height, cx, cy, moduleSize)) {
                            val dx = cx - estX; val dy = cy - estY
                            val d = dx * dx + dy * dy
                            if (d < bestDist) { bestDist = d; best = FinderPoint(cx, cy, moduleSize) }
                        }
                    }
                }
            }
        }
        val gate = moduleSize * 8
        return if (best != null && bestDist <= gate * gate) best else null
    }

    /** Centre of the dark run through (x, y), or null if it isn't ~1 module. */
    private fun verticalCenterOfDarkRun(
        binary: BooleanArray, width: Int, height: Int, x: Int, y: Int, moduleSize: Float
    ): Float? {
        if (x < 0 || x >= width) return null
        if (!binary[y * width + x]) return null
        var up = y
        while (up >= 0 && binary[up * width + x]) up--
        var down = y
        while (down < height && binary[down * width + x]) down++
        val runLen = (down - up - 1).toFloat()
        if (abs(runLen - moduleSize) >= moduleSize / 1.7f) return null
        return (up + down) / 2f
    }

    /**
     * Verify the 5x5 alignment template around a candidate centre:
     * dark outer ring, light inner ring, dark centre.
     */
    private fun alignmentTemplateMatches(
        binary: BooleanArray, width: Int, height: Int,
        cx: Float, cy: Float, moduleSize: Float
    ): Boolean {
        var hits = 0
        for (dy in -2..2) {
            for (dx in -2..2) {
                val x = (cx + dx * moduleSize).roundToInt()
                val y = (cy + dy * moduleSize).roundToInt()
                if (x < 0 || y < 0 || x >= width || y >= height) return false
                val expectDark = (abs(dx) == 2 || abs(dy) == 2) || (dx == 0 && dy == 0)
                if (binary[y * width + x] == expectDark) hits++
            }
        }
        return hits >= 20      // tolerate a few sampling errors at the edges
    }

    // ── perspective transform ──

    class PerspectiveTransform(
        private val a11: Float, private val a21: Float, private val a31: Float,
        private val a12: Float, private val a22: Float, private val a32: Float,
        private val a13: Float, private val a23: Float, private val a33: Float
    ) {
        fun transform(point: FloatArray) {
            val x = point[0]; val y = point[1]
            val denominator = a13 * x + a23 * y + a33
            point[0] = (a11 * x + a21 * y + a31) / denominator
            point[1] = (a12 * x + a22 * y + a32) / denominator
        }

        fun times(other: PerspectiveTransform) = PerspectiveTransform(
            a11 * other.a11 + a21 * other.a12 + a31 * other.a13,
            a11 * other.a21 + a21 * other.a22 + a31 * other.a23,
            a11 * other.a31 + a21 * other.a32 + a31 * other.a33,
            a12 * other.a11 + a22 * other.a12 + a32 * other.a13,
            a12 * other.a21 + a22 * other.a22 + a32 * other.a23,
            a12 * other.a31 + a22 * other.a32 + a32 * other.a33,
            a13 * other.a11 + a23 * other.a12 + a33 * other.a13,
            a13 * other.a21 + a23 * other.a22 + a33 * other.a23,
            a13 * other.a31 + a23 * other.a32 + a33 * other.a33
        )

        fun buildAdjoint() = PerspectiveTransform(
            a22 * a33 - a23 * a32, a23 * a31 - a21 * a33, a21 * a32 - a22 * a31,
            a13 * a32 - a12 * a33, a11 * a33 - a13 * a31, a12 * a31 - a11 * a32,
            a12 * a23 - a13 * a22, a13 * a21 - a11 * a23, a11 * a22 - a12 * a21
        )

        companion object {
            fun quadrilateralToQuadrilateral(
                x0: Float, y0: Float, x1: Float, y1: Float,
                x2: Float, y2: Float, x3: Float, y3: Float,
                x0p: Float, y0p: Float, x1p: Float, y1p: Float,
                x2p: Float, y2p: Float, x3p: Float, y3p: Float
            ): PerspectiveTransform {
                val qToS = quadrilateralToSquare(x0, y0, x1, y1, x2, y2, x3, y3)
                val sToQ = squareToQuadrilateral(x0p, y0p, x1p, y1p, x2p, y2p, x3p, y3p)
                return sToQ.times(qToS)
            }

            private fun squareToQuadrilateral(
                x0: Float, y0: Float, x1: Float, y1: Float,
                x2: Float, y2: Float, x3: Float, y3: Float
            ): PerspectiveTransform {
                val dx3 = x0 - x1 + x2 - x3
                val dy3 = y0 - y1 + y2 - y3
                if (dx3 == 0.0f && dy3 == 0.0f) {
                    return PerspectiveTransform(
                        x1 - x0, x2 - x1, x0,
                        y1 - y0, y2 - y1, y0,
                        0f, 0f, 1f
                    )
                }
                val dx1 = x1 - x2
                val dx2 = x3 - x2
                val dy1 = y1 - y2
                val dy2 = y3 - y2
                val denominator = dx1 * dy2 - dx2 * dy1
                val a13 = (dx3 * dy2 - dx2 * dy3) / denominator
                val a23 = (dx1 * dy3 - dx3 * dy1) / denominator
                return PerspectiveTransform(
                    x1 - x0 + a13 * x1, x3 - x0 + a23 * x3, x0,
                    y1 - y0 + a13 * y1, y3 - y0 + a23 * y3, y0,
                    a13, a23, 1f
                )
            }

            private fun quadrilateralToSquare(
                x0: Float, y0: Float, x1: Float, y1: Float,
                x2: Float, y2: Float, x3: Float, y3: Float
            ): PerspectiveTransform =
                squareToQuadrilateral(x0, y0, x1, y1, x2, y2, x3, y3).buildAdjoint()
        }
    }
}
