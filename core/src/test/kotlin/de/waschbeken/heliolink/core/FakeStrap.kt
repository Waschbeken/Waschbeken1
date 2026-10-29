package de.waschbeken.heliolink.core

import java.security.SecureRandom
import kotlin.test.assertContentEquals

/** Minimal simulation of the strap side of the protocol. */
class FakeStrap(private val authKey: ByteArray, private val encrypted: Set<Int> = setOf(HealthConfigCodec.ENDPOINT)) {
    private val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(99) }
    private val privateKey = Ecdh163.generatePrivateKey(random)
    private val challenge = ByteArray(16).also { random.nextBytes(it) }
    private val encoder = ChunkedEncoder()
    private val decoder = ChunkedDecoder()
    private var sessionKey: ByteArray? = null

    val received = ArrayList<Pair<Int, ByteArray>>()
    val outgoing = ArrayDeque<ByteArray>()
    var acceptKey = true

    fun onWrite(w: GattWrite) {
        if (w.target != WriteTarget.CHUNKED_WRITE) return
        val msg = decoder.decode(w.value).message ?: return
        received += msg.endpoint to msg.payload
        if (msg.endpoint == 0x0082) handleAuth(msg.payload)
        if (msg.endpoint == 0x0000) {
            val p = byteArrayOf(0x04) + le16(encrypted.size + 1) + byteArrayOf(0x29, 0x00, 0x00) +
                encrypted.flatMap { le16(it).toList() + listOf(1.toByte()) }.toByteArray()
            send(0x0000, p)
        }
        if (msg.endpoint == 0x0029) send(0x0029, byteArrayOf(0x04, 0x0f, 77, 0) + ByteArray(17))
    }

    private fun handleAuth(p: ByteArray) {
        if (p[0] == 0x04.toByte()) {
            val shared = Ecdh163.sharedSecret(privateKey, p.copyOfRange(4, 52))
            val key = ByteArray(16) { (shared[it + 8].toInt() xor authKey[it].toInt()).toByte() }
            sessionKey = key
            encoder.setEncryption(shared.s32(0), key)
            decoder.setSessionKey(key)
            send(0x0082, byteArrayOf(0x10, 0x04, 0x01) + challenge + Ecdh163.publicKey(privateKey))
        } else if (p[0] == 0x05.toByte()) {
            val ok = acceptKey && p.copyOfRange(1, 17).contentEquals(Aes.encrypt(challenge, authKey))
            if (ok) assertContentEquals(Aes.encrypt(challenge, sessionKey!!), p.copyOfRange(17, 33))
            send(0x0082, byteArrayOf(0x10, 0x05, if (ok) 0x01 else 0x25))
        }
    }

    fun send(endpoint: Int, payload: ByteArray, encrypt: Boolean = false) {
        outgoing.addAll(encoder.encode(endpoint, payload, encrypt))
    }
}
