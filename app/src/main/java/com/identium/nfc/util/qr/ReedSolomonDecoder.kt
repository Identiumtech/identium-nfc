package com.identium.nfc.util.qr

/**
 * GF(256) arithmetic for QR codes — primitive polynomial x^8+x^4+x^3+x^2+1
 * (0x11D), the field used by the QR specification.
 *
 * Written from scratch so the app keeps zero third-party dependencies.
 */
object Gf256 {
    private const val PRIMITIVE = 0x11D

    /** exp[i] = a^i, duplicated to 512 entries so log sums never need a modulo. */
    private val exp = IntArray(512)
    private val log = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIMITIVE
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
    }

    fun mul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

    fun div(a: Int, b: Int): Int {
        require(b != 0) { "GF(256) division by zero" }
        return if (a == 0) 0 else exp[(log[a] - log[b] + 255) % 255]
    }

    fun inverse(a: Int): Int {
        require(a != 0)
        return exp[255 - log[a]]
    }

    /** a^n where a is the generator (2). */
    fun expOf(n: Int): Int = exp[((n % 255) + 255) % 255]
}

/**
 * Polynomial over GF(256). [coefficients] is highest-degree first, matching
 * the usual textbook layout.
 */
class GfPoly(coefficients: IntArray) {

    val coefficients: IntArray

    init {
        require(coefficients.isNotEmpty())
        if (coefficients.size > 1 && coefficients[0] == 0) {
            // Strip leading zeros.
            var firstNonZero = 1
            while (firstNonZero < coefficients.size && coefficients[firstNonZero] == 0) firstNonZero++
            this.coefficients = if (firstNonZero == coefficients.size) intArrayOf(0)
            else coefficients.copyOfRange(firstNonZero, coefficients.size)
        } else {
            this.coefficients = coefficients
        }
    }

    val degree: Int get() = coefficients.size - 1
    val isZero: Boolean get() = coefficients[0] == 0

    /** Coefficient of x^degree. */
    fun coefficient(degree: Int): Int = coefficients[coefficients.size - 1 - degree]

    fun evaluate(x: Int): Int {
        if (x == 0) return coefficient(0)
        var result = coefficients[0]
        for (i in 1 until coefficients.size) {
            result = Gf256.mul(result, x) xor coefficients[i]
        }
        return result
    }

    fun addOrSubtract(other: GfPoly): GfPoly {
        if (isZero) return other
        if (other.isZero) return this
        var smaller = coefficients
        var larger = other.coefficients
        if (smaller.size > larger.size) { val t = smaller; smaller = larger; larger = t }
        val sum = IntArray(larger.size)
        val lengthDiff = larger.size - smaller.size
        System.arraycopy(larger, 0, sum, 0, lengthDiff)
        for (i in lengthDiff until larger.size) {
            sum[i] = smaller[i - lengthDiff] xor larger[i]
        }
        return GfPoly(sum)
    }

    fun multiply(other: GfPoly): GfPoly {
        if (isZero || other.isZero) return ZERO
        val a = coefficients
        val b = other.coefficients
        val product = IntArray(a.size + b.size - 1)
        for (i in a.indices) {
            for (j in b.indices) {
                product[i + j] = product[i + j] xor Gf256.mul(a[i], b[j])
            }
        }
        return GfPoly(product)
    }

    fun multiplyByScalar(scalar: Int): GfPoly {
        if (scalar == 0) return ZERO
        if (scalar == 1) return this
        return GfPoly(IntArray(coefficients.size) { Gf256.mul(coefficients[it], scalar) })
    }

    fun multiplyByMonomial(degree: Int, coefficient: Int): GfPoly {
        require(degree >= 0)
        if (coefficient == 0) return ZERO
        val product = IntArray(coefficients.size + degree)
        for (i in coefficients.indices) product[i] = Gf256.mul(coefficients[i], coefficient)
        return GfPoly(product)
    }

    /** Returns [quotient, remainder]. */
    fun divide(other: GfPoly): Pair<GfPoly, GfPoly> {
        require(!other.isZero) { "divide by zero polynomial" }
        var quotient: GfPoly = ZERO
        var remainder: GfPoly = this
        val denominatorLeading = other.coefficient(other.degree)
        val inverseDenominatorLeading = Gf256.inverse(denominatorLeading)
        while (remainder.degree >= other.degree && !remainder.isZero) {
            val degreeDiff = remainder.degree - other.degree
            val scale = Gf256.mul(remainder.coefficient(remainder.degree), inverseDenominatorLeading)
            val term = other.multiplyByMonomial(degreeDiff, scale)
            quotient = quotient.addOrSubtract(buildMonomial(degreeDiff, scale))
            remainder = remainder.addOrSubtract(term)
        }
        return Pair(quotient, remainder)
    }

    companion object {
        val ZERO = GfPoly(intArrayOf(0))
        val ONE = GfPoly(intArrayOf(1))

        fun buildMonomial(degree: Int, coefficient: Int): GfPoly {
            require(degree >= 0)
            if (coefficient == 0) return ZERO
            val c = IntArray(degree + 1)
            c[0] = coefficient
            return GfPoly(c)
        }
    }
}

/**
 * Reed-Solomon error correction decoder.
 *
 * Uses syndromes + the extended Euclidean algorithm to find the error
 * locator and evaluator polynomials, Chien search for positions and
 * Forney's formula for magnitudes. This is what lets a QR still decode
 * when it's partly damaged, dirty or blurred.
 */
object ReedSolomonDecoder {

    /**
     * Corrects [received] in place. [twoS] is the number of EC codewords.
     * Returns false when the block has more errors than the code can fix.
     */
    fun decode(received: IntArray, twoS: Int): Boolean {
        val poly = GfPoly(received)
        val syndromeCoefficients = IntArray(twoS)
        var noError = true
        for (i in 0 until twoS) {
            val eval = poly.evaluate(Gf256.expOf(i))
            syndromeCoefficients[syndromeCoefficients.size - 1 - i] = eval
            if (eval != 0) noError = false
        }
        if (noError) return true

        val syndrome = GfPoly(syndromeCoefficients)
        val sigmaOmega = runEuclideanAlgorithm(
            GfPoly.buildMonomial(twoS, 1), syndrome, twoS
        ) ?: return false

        val sigma = sigmaOmega.first
        val omega = sigmaOmega.second
        val errorLocations = findErrorLocations(sigma) ?: return false
        val errorMagnitudes = findErrorMagnitudes(omega, errorLocations)

        for (i in errorLocations.indices) {
            val position = received.size - 1 - logOf(errorLocations[i])
            if (position < 0 || position >= received.size) return false
            received[position] = received[position] xor errorMagnitudes[i]
        }
        return true
    }

    private fun logOf(value: Int): Int {
        // Small helper: find i such that a^i == value.
        for (i in 0 until 255) if (Gf256.expOf(i) == value) return i
        return -1
    }

    private fun runEuclideanAlgorithm(a0: GfPoly, b0: GfPoly, R: Int): Pair<GfPoly, GfPoly>? {
        var a = a0
        var b = b0
        if (a.degree < b.degree) { val t = a; a = b; b = t }

        var rLast = a
        var r = b
        var tLast = GfPoly.ZERO
        var t = GfPoly.ONE

        while (r.degree >= R / 2) {
            val rLastLast = rLast
            val tLastLast = tLast
            rLast = r
            tLast = t

            if (rLast.isZero) return null   // r_{i-1} was zero — cannot continue

            r = rLastLast
            var q = GfPoly.ZERO
            val denominatorLeading = rLast.coefficient(rLast.degree)
            val dltInverse = Gf256.inverse(denominatorLeading)
            while (r.degree >= rLast.degree && !r.isZero) {
                val degreeDiff = r.degree - rLast.degree
                val scale = Gf256.mul(r.coefficient(r.degree), dltInverse)
                q = q.addOrSubtract(GfPoly.buildMonomial(degreeDiff, scale))
                r = r.addOrSubtract(rLast.multiplyByMonomial(degreeDiff, scale))
            }
            t = q.multiply(tLast).addOrSubtract(tLastLast)
            if (r.degree >= rLast.degree) return null
        }

        val sigmaTildeAtZero = t.coefficient(0)
        if (sigmaTildeAtZero == 0) return null
        val inverse = Gf256.inverse(sigmaTildeAtZero)
        return Pair(t.multiplyByScalar(inverse), r.multiplyByScalar(inverse))
    }

    /** Chien search — the roots of sigma give the error positions. */
    private fun findErrorLocations(errorLocator: GfPoly): IntArray? {
        val numErrors = errorLocator.degree
        if (numErrors == 1) return intArrayOf(errorLocator.coefficient(1))
        val result = IntArray(numErrors)
        var e = 0
        var i = 1
        while (i < 256 && e < numErrors) {
            if (errorLocator.evaluate(i) == 0) {
                result[e] = Gf256.inverse(i)
                e++
            }
            i++
        }
        return if (e != numErrors) null else result
    }

    /** Forney's formula for the error values. */
    private fun findErrorMagnitudes(errorEvaluator: GfPoly, errorLocations: IntArray): IntArray {
        val s = errorLocations.size
        val result = IntArray(s)
        for (i in 0 until s) {
            val xiInverse = Gf256.inverse(errorLocations[i])
            var denominator = 1
            for (j in 0 until s) {
                if (i != j) {
                    val term = Gf256.mul(errorLocations[j], xiInverse)
                    val termPlus1 = if (term and 0x1 == 0) term or 1 else term and 1.inv()
                    denominator = Gf256.mul(denominator, termPlus1)
                }
            }
            // NOTE: no final multiply by xiInverse. That correction only
            // applies when the RS generator starts at a^1; QR's generator
            // starts at a^0 (see the encoder, whose root starts at 1), so
            // applying it here produced correct error *positions* with wrong
            // *magnitudes* — corrupting the data instead of repairing it.
            result[i] = Gf256.mul(
                errorEvaluator.evaluate(xiInverse),
                Gf256.inverse(denominator)
            )
        }
        return result
    }
}
