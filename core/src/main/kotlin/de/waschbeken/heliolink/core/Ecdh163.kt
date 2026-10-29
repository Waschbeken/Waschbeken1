package de.waschbeken.heliolink.core

import java.math.BigInteger
import java.security.SecureRandom

/**
 * Elliptic curve Diffie-Hellman on NIST B-163 (sect163r2), as used by the Zepp OS
 * authentication handshake.
 *
 * Keys use the wire format of the strap: a private key is 24 bytes little-endian, a point is
 * x || y with each coordinate 24 bytes little-endian.
 */
object Ecdh163 {
    const val PRIVATE_KEY_SIZE = 24
    const val PUBLIC_KEY_SIZE = 48

    private const val DEGREE = 163

    // f(x) = x^163 + x^7 + x^6 + x^3 + 1
    private val POLY: BigInteger = BigInteger.ONE.shiftLeft(163)
        .or(BigInteger.valueOf(0xc9))

    private val B = BigInteger("20a601907b8c953ca1481eb10512f78744a3205fd", 16)
    private val GX = BigInteger("3f0eba16286a2d57ea0991168d4994637e8343e36", 16)
    private val GY = BigInteger("0d51fbc6c71a0094fa2cdd545b11c5c0c797324f1", 16)
    private val ORDER = BigInteger("40000000000000000000292fe77e70c12a4234c33", 16)

    private data class Point(val x: BigInteger, val y: BigInteger) {
        val isInfinity get() = x.signum() == 0 && y.signum() == 0
    }

    private val INFINITY = Point(BigInteger.ZERO, BigInteger.ZERO)
    private val G = Point(GX, GY)

    /** Generates a random private key that satisfies the constraints of the strap firmware. */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray {
        while (true) {
            val key = ByteArray(PRIVATE_KEY_SIZE)
            random.nextBytes(key)
            val clamped = clampPrivateKey(key)
            if (fromLe(clamped).bitLength() >= DEGREE / 2) return clamped
        }
    }

    /** Clears all bits at and above bit 162 so that 1 <= k < n, like the reference implementation. */
    fun clampPrivateKey(key: ByteArray): ByteArray {
        val k = fromLe(key).and(BigInteger.ONE.shiftLeft(ORDER.bitLength() - 1).subtract(BigInteger.ONE))
        return toLe(k, PRIVATE_KEY_SIZE)
    }

    fun publicKey(privateKey: ByteArray): ByteArray = encode(multiply(G, fromLe(privateKey)))

    /** Returns the shared point x || y (48 bytes). */
    fun sharedSecret(privateKey: ByteArray, remotePublicKey: ByteArray): ByteArray {
        require(remotePublicKey.size == PUBLIC_KEY_SIZE) { "public key must be 48 bytes" }
        val remote = decode(remotePublicKey)
        require(isOnCurve(remote)) { "remote public key is not on the curve" }
        return encode(multiply(remote, fromLe(privateKey)))
    }

    fun isValidPublicKey(publicKey: ByteArray): Boolean =
        publicKey.size == PUBLIC_KEY_SIZE && isOnCurve(decode(publicKey))

    // --- encoding

    private fun fromLe(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): BigInteger {
        val be = ByteArray(length + 1)
        for (i in 0 until length) be[length - i] = bytes[offset + i]
        return BigInteger(be)
    }

    private fun toLe(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    private fun encode(p: Point) = toLe(p.x, 24) + toLe(p.y, 24)

    private fun decode(b: ByteArray) = Point(fromLe(b, 0, 24), fromLe(b, 24, 24))

    // --- GF(2^163) arithmetic, elements are polynomials stored as BigInteger bit sets

    private fun add(a: BigInteger, b: BigInteger) = a.xor(b)

    private fun reduce(v: BigInteger): BigInteger {
        var r = v
        while (r.bitLength() > DEGREE) {
            r = r.xor(POLY.shiftLeft(r.bitLength() - 1 - DEGREE))
        }
        return r
    }

    private fun mul(a: BigInteger, b: BigInteger): BigInteger {
        var result = BigInteger.ZERO
        var shifted = a
        for (i in 0 until b.bitLength()) {
            if (b.testBit(i)) result = result.xor(shifted)
            shifted = shifted.shiftLeft(1)
        }
        return reduce(result)
    }

    private fun sqr(a: BigInteger) = mul(a, a)

    /** Inverse via the extended Euclidean algorithm over GF(2)[x]. */
    private fun inv(a: BigInteger): BigInteger {
        require(a.signum() != 0) { "zero has no inverse" }
        var u = a
        var v = POLY
        var g1 = BigInteger.ONE
        var g2 = BigInteger.ZERO
        while (u != BigInteger.ONE) {
            var j = u.bitLength() - v.bitLength()
            if (j < 0) {
                u = v.also { v = u }
                g1 = g2.also { g2 = g1 }
                j = -j
            }
            u = u.xor(v.shiftLeft(j))
            g1 = g1.xor(g2.shiftLeft(j))
        }
        return reduce(g1)
    }

    // --- curve arithmetic: y^2 + xy = x^3 + a x^2 + b with a = 1

    private fun isOnCurve(p: Point): Boolean {
        if (p.isInfinity) return false
        if (p.x.bitLength() > DEGREE || p.y.bitLength() > DEGREE) return false
        val lhs = add(sqr(p.y), mul(p.x, p.y))
        val x2 = sqr(p.x)
        val rhs = add(add(mul(x2, p.x), x2), B)
        return lhs == rhs
    }

    private fun double(p: Point): Point {
        if (p.isInfinity || p.x.signum() == 0) return INFINITY
        val l = add(p.x, mul(p.y, inv(p.x)))
        val x3 = add(add(sqr(l), l), BigInteger.ONE)
        val y3 = add(sqr(p.x), mul(add(l, BigInteger.ONE), x3))
        return Point(x3, y3)
    }

    private fun add(p: Point, q: Point): Point {
        if (p.isInfinity) return q
        if (q.isInfinity) return p
        if (p.x == q.x) {
            return if (p.y == q.y) double(p) else INFINITY
        }
        val l = mul(add(p.y, q.y), inv(add(p.x, q.x)))
        val x3 = add(add(add(add(sqr(l), l), p.x), q.x), BigInteger.ONE)
        val y3 = add(add(mul(l, add(p.x, x3)), x3), p.y)
        return Point(x3, y3)
    }

    private fun multiply(p: Point, k: BigInteger): Point {
        var r = INFINITY
        for (i in k.bitLength() - 1 downTo 0) {
            r = double(r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }
}
