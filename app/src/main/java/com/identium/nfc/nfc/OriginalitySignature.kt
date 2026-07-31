package com.identium.nfc.nfc

import java.math.BigInteger

/**
 * Offline verifier for the NXP NTAG21x originality signature.
 *
 * Genuine NXP NTAG213/215/216 chips carry a 32-byte ECDSA signature over
 * their 7-byte UID, programmed at the factory and readable with the
 * READ_SIG (0x3C) command. A cloned chip can copy the *bytes* of a
 * signature, but those bytes only validate against the original chip's UID
 * — re-using them on a tag with a different UID fails verification. So a
 * passing signature on a tag whose UID it actually signs is strong evidence
 * the silicon is real NXP.
 *
 * Implemented with pure [BigInteger] elliptic-curve math over secp128r1.
 * No external crypto library, no network — works entirely offline. The NXP
 * public key is bundled below.
 *
 * Curve: secp128r1.  Message: the raw UID bytes used directly as the ECDSA
 * digest e (the 56-bit UID is smaller than the 128-bit group order, so no
 * hashing or truncation is applied — this matches NXP AN11350).
 */
object OriginalitySignature {

    // ── secp128r1 domain parameters ──
    private val P  = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A  = BigInteger("FFFFFFFDFFFFFFFFFFFFFFFFFFFFFFFC", 16)
    private val N  = BigInteger("FFFFFFFE0000000075A30D1B9038A115", 16)
    private val GX = BigInteger("161FF7528B899B2D0C28607CA52C5B86", 16)
    private val GY = BigInteger("CF5AC8395BAFEB13C02DA292DDED7A83", 16)

    // ── NXP "NTAG21x (2013)" public key (uncompressed point 0x04||X||Y) ──
    // 04 49 4E 1A 38 6D 3D 3C FE 3D C1 0E 5D E6 8A 49 9B  1C 20 2D B5 B1 32 39 3E 89 ED 19 FE 5B E8 BC 61
    private val QX = BigInteger("494E1A386D3D3CFE3DC10E5DE68A499B", 16)
    private val QY = BigInteger("1C202DB5B132393E89ED19FE5BE8BC61", 16)

    private val TWO = BigInteger.valueOf(2)
    private val THREE = BigInteger.valueOf(3)

    private val G = Point(GX, GY)
    private val Q = Point(QX, QY)

    enum class Result { GENUINE, INVALID, BAD_INPUT }

    /**
     * Verify [signature] (32 bytes from READ_SIG) against [uid].
     * Returns GENUINE only if the ECDSA signature validates against NXP's
     * public key for the given UID.
     */
    fun verify(uid: ByteArray, signature: ByteArray): Result {
        if (signature.size != 32 || uid.isEmpty()) return Result.BAD_INPUT
        return try {
            val r = BigInteger(1, signature.copyOfRange(0, 16))
            val s = BigInteger(1, signature.copyOfRange(16, 32))
            if (r < BigInteger.ONE || r >= N) return Result.INVALID
            if (s < BigInteger.ONE || s >= N) return Result.INVALID

            val e = BigInteger(1, uid)            // UID used directly as digest
            val w = s.modInverse(N)
            val u1 = e.multiply(w).mod(N)
            val u2 = r.multiply(w).mod(N)

            val point = add(scalarMult(u1, G), scalarMult(u2, Q))
            if (point.isInfinity) return Result.INVALID

            if (point.x!!.mod(N) == r.mod(N)) Result.GENUINE else Result.INVALID
        } catch (_: Exception) {
            Result.INVALID
        }
    }

    // ── EC point arithmetic (affine, prime field) ──

    private data class Point(val x: BigInteger?, val y: BigInteger?) {
        val isInfinity get() = x == null
        companion object { val INFINITY = Point(null, null) }
    }

    private fun add(p: Point, q: Point): Point {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        val x1 = p.x!!; val y1 = p.y!!
        val x2 = q.x!!; val y2 = q.y!!

        if (x1 == x2) {
            // P + (-P) = ∞
            if (y1.add(y2).mod(P) == BigInteger.ZERO) return Point.INFINITY
            // P == Q → doubling
            val num = x1.multiply(x1).multiply(THREE).add(A).mod(P)
            val den = y1.multiply(TWO).modInverse(P)
            val lambda = num.multiply(den).mod(P)
            val x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(P)
            val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)
            return Point(x3, y3)
        }
        val lambda = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(P)).mod(P)
        val x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(P)
        val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)
        return Point(x3, y3)
    }

    private fun scalarMult(k: BigInteger, point: Point): Point {
        var result = Point.INFINITY
        var addend = point
        var n = k
        while (n > BigInteger.ZERO) {
            if (n.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }
}
