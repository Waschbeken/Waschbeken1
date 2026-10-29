package de.waschbeken.heliolink.core

import org.bouncycastle.asn1.sec.SECNamedCurves
import java.math.BigInteger
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class Ecdh163Test {
    private val curve = SECNamedCurves.getByName("sect163r2")

    private fun le(b: ByteArray, off: Int, len: Int): BigInteger =
        BigInteger(1, b.copyOfRange(off, off + len).reversedArray())

    private fun toLe(v: BigInteger, size: Int): ByteArray {
        val be = v.toByteArray()
        return ByteArray(size) { i -> if (i < be.size) be[be.size - 1 - i] else 0 }
    }

    @Test
    fun publicKeyMatchesBouncyCastle() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42) }
        repeat(5) {
            val priv = Ecdh163.generatePrivateKey(random)
            val pub = Ecdh163.publicKey(priv)
            val expected = curve.g.multiply(le(priv, 0, 24)).normalize()
            assertContentEquals(toLe(expected.affineXCoord.toBigInteger(), 24), pub.copyOfRange(0, 24))
            assertContentEquals(toLe(expected.affineYCoord.toBigInteger(), 24), pub.copyOfRange(24, 48))
            assertTrue(Ecdh163.isValidPublicKey(pub))
        }
    }

    @Test
    fun sharedSecretIsSymmetric() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7) }
        val a = Ecdh163.generatePrivateKey(random)
        val b = Ecdh163.generatePrivateKey(random)
        val ab = Ecdh163.sharedSecret(a, Ecdh163.publicKey(b))
        val ba = Ecdh163.sharedSecret(b, Ecdh163.publicKey(a))
        assertContentEquals(ab, ba)
    }

    @Test
    fun knownRemoteKeyFromGadgetbridgeIsOnCurve() {
        val remote = intArrayOf(
            0xe6, 0x01, 0x6a, 0xba, 0x1d, 0xe7, 0xac, 0x0f, 0x0c, 0x7f, 0x0f, 0xf7, 0xe2, 0x24, 0x3e, 0x66,
            0x62, 0xb5, 0xe0, 0x3b, 0x01, 0x00, 0x00, 0x00, 0xad, 0x8a, 0x4b, 0xed, 0xc7, 0x6a, 0x1e, 0xfd,
            0xe7, 0x72, 0x5c, 0xc6, 0x62, 0xb5, 0x48, 0x35, 0x51, 0x3e, 0x3d, 0x57, 0x05, 0x00, 0x00, 0x00,
        ).map { it.toByte() }.toByteArray()
        assertTrue(Ecdh163.isValidPublicKey(remote))
    }
}


