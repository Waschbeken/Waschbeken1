package de.waschbeken.heliolink.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupCryptoTest {
    private val pw = "geheim 123".toCharArray()

    private fun seal(data: ByteArray, password: CharArray = pw): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password, iterations = 1_000).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, password: CharArray = pw): ByteArray =
        BackupCrypto.decrypt(ByteArrayInputStream(sealed), password).use { it.readBytes() }

    @Test
    fun roundTripsAllSizes() {
        val c = BackupCrypto.CHUNK
        for (size in listOf(0, 1, 1000, c - 1, c, c + 1, 3 * c, 3 * c + 17)) {
            val data = Random(size).nextBytes(size)
            val sealed = seal(data)
            assertTrue(BackupCrypto.isEncrypted(sealed))
            assertContentEquals(data, open(sealed), "size $size")
        }
    }

    @Test
    fun hidesThePlainText() {
        val data = "Ruhepuls 58, Auth-Key 0011223344".repeat(50).toByteArray()
        val sealed = seal(data)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("Auth-Key"))
        assertFalse(BackupCrypto.isEncrypted("PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun rejectsWrongPassword() {
        val sealed = seal(ByteArray(10))
        assertFailsWith<BackupCrypto.WrongPassword> { BackupCrypto.decrypt(ByteArrayInputStream(sealed), "falsch".toCharArray()) }
    }

    @Test
    fun detectsChangedData() {
        val sealed = seal(Random(1).nextBytes(5000))
        sealed[sealed.size - 30] = (sealed[sealed.size - 30].toInt() xor 1).toByte()
        assertFailsWith<BackupCrypto.Damaged> { open(sealed) }
    }

    @Test
    fun detectsCutOffFile() {
        val c = BackupCrypto.CHUNK
        val sealed = seal(Random(2).nextBytes(2 * c + 100))
        // drop the last chunk completely: what is left still ends on a chunk boundary
        val firstTwo = sealed.size - (4 + 100 + 16)
        assertFailsWith<BackupCrypto.Damaged> { open(sealed.copyOf(firstTwo)) }
        assertFailsWith<BackupCrypto.Damaged> { open(sealed.copyOf(sealed.size - 5)) }
    }

    @Test
    fun detectsAppendedData() {
        val sealed = seal(Random(3).nextBytes(100))
        assertFailsWith<BackupCrypto.Damaged> { open(sealed + byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun saltMakesEveryFileDifferent() {
        val data = ByteArray(100)
        assertFalse(seal(data).contentEquals(seal(data)))
    }
}
