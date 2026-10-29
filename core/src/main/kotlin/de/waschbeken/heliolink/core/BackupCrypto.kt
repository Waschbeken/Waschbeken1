package de.waschbeken.heliolink.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password encryption for backups. The key comes from the password with PBKDF2-SHA256; the data is
 * cut into chunks of 64 KiB, each sealed with AES-GCM, so even large databases never have to fit
 * into memory. A changed, cut off or reordered file fails instead of restoring wrong data.
 *
 * Layout: header (magic, iterations, salt, nonce prefix, password check) followed by chunks of
 * `[length][ciphertext + tag]`. The nonce is the prefix plus the chunk number; the header and a
 * "last chunk" flag are authenticated with every chunk.
 */
object BackupCrypto {
    class WrongPassword : Exception("Falsches Passwort")
    class Damaged(message: String) : Exception(message)

    private val MAGIC = "HLENC1".toByteArray()
    private const val SALT = 16
    private const val PREFIX = 8
    private const val CHECK = 16
    private const val TAG_BITS = 128
    const val CHUNK = 64 * 1024
    const val DEFAULT_ITERATIONS = 210_000
    private val HEADER = MAGIC.size + 4 + SALT + PREFIX + CHECK

    private class Keys(val aes: SecretKeySpec, val check: ByteArray)

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): Keys {
        val spec = PBEKeySpec(password, salt, iterations, 512)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            val check = MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(32, 64)).copyOf(CHECK)
            return Keys(SecretKeySpec(bytes.copyOf(32), "AES"), check)
        } finally {
            spec.clearPassword()
        }
    }

    /** True if [head] (the first bytes of a file) starts like an encrypted backup. */
    fun isEncrypted(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** Stream that encrypts everything written to it into [out]; closing it writes the last chunk and closes [out]. */
    fun encrypt(out: OutputStream, password: CharArray, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): OutputStream {
        val salt = ByteArray(SALT).also(random::nextBytes)
        val prefix = ByteArray(PREFIX).also(random::nextBytes)
        val keys = derive(password, salt, iterations)
        val header = ByteArrayOutputStream().apply {
            write(MAGIC)
            write(int(iterations))
            write(salt)
            write(prefix)
            write(keys.check)
        }.toByteArray()
        out.write(header)
        return ChunkWriter(out, keys.aes, header, prefix)
    }

    /** Stream that reads and checks the encrypted backup in [input]. Throws [WrongPassword] right away. */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val header = ByteArray(HEADER)
        try {
            data.readFully(header)
        } catch (e: EOFException) {
            throw Damaged("Die Sicherung ist unvollständig.")
        }
        if (!isEncrypted(header)) throw Damaged("Das ist keine verschlüsselte HelioLink-Sicherung.")
        var p = MAGIC.size
        val iterations = ((header[p].toInt() and 0xff) shl 24) or ((header[p + 1].toInt() and 0xff) shl 16) or
            ((header[p + 2].toInt() and 0xff) shl 8) or (header[p + 3].toInt() and 0xff)
        p += 4
        if (iterations !in 1_000..10_000_000) throw Damaged("Die Sicherung ist beschädigt.")
        val salt = header.copyOfRange(p, p + SALT).also { p += SALT }
        val prefix = header.copyOfRange(p, p + PREFIX).also { p += PREFIX }
        val check = header.copyOfRange(p, p + CHECK)
        val keys = derive(password, salt, iterations)
        if (!MessageDigest.isEqual(check, keys.check)) throw WrongPassword()
        return ChunkReader(data, keys.aes, header, prefix)
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun nonce(prefix: ByteArray, index: Long): ByteArray {
        if (index > 0xffffffffL) throw Damaged("Die Sicherung ist zu groß.")
        return prefix + int(index.toInt())
    }

    private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray, index: Long, last: Boolean): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce(prefix, index)))
            updateAAD(header)
            updateAAD(byteArrayOf(if (last) 1 else 0))
        }

    private class ChunkWriter(
        private val target: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : FilterOutputStream(target) {
        private val buffer = ByteArray(CHUNK)
        private var size = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) {
            if (size == CHUNK) flushChunk(false)
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (size == CHUNK) flushChunk(false)
                val n = minOf(left, CHUNK - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n
                o += n
                left -= n
            }
        }

        private fun flushChunk(last: Boolean) {
            val sealed = cipher(Cipher.ENCRYPT_MODE, key, header, prefix, index++, last).doFinal(buffer, 0, size)
            target.write(int(sealed.size))
            target.write(sealed)
            size = 0
        }

        override fun flush() = target.flush()

        override fun close() {
            if (closed) return
            closed = true
            try {
                flushChunk(true)
                target.flush()
            } finally {
                target.close()
            }
        }
    }

    private class ChunkReader(
        private val source: DataInputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : InputStream() {
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0L
        private var done = false

        /** Reads the next chunk; the last one is the one with nothing after it. */
        private fun next(): Boolean {
            if (done) return false
            val len = try {
                source.readInt()
            } catch (e: EOFException) {
                throw Damaged("Die Sicherung ist unvollständig.")
            }
            if (len < TAG_BITS / 8 || len > CHUNK + TAG_BITS / 8) throw Damaged("Die Sicherung ist beschädigt.")
            val sealed = ByteArray(len)
            try {
                source.readFully(sealed)
            } catch (e: EOFException) {
                throw Damaged("Die Sicherung ist unvollständig.")
            }
            // A chunk is the last one exactly when it was sealed as last; try that first only if it is short.
            val last = len < CHUNK + TAG_BITS / 8
            plain = open(sealed, last) ?: open(sealed, !last) ?: throw Damaged("Die Sicherung ist beschädigt oder verändert.")
            index++
            pos = 0
            if (done && source.read() != -1) throw Damaged("Die Sicherung enthält unerwartete Daten.")
            return true
        }

        private fun open(sealed: ByteArray, last: Boolean): ByteArray? = try {
            cipher(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(sealed).also { done = last }
        } catch (e: AEADBadTagException) {
            null
        }

        override fun read(): Int {
            while (pos >= plain.size) if (!next()) return -1
            return plain[pos++].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= plain.size) if (!next()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = source.close()
    }
}
