package de.waschbeken.heliolink.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** AES-128 in ECB mode without padding, which is what the strap uses everywhere. */
internal object Aes {
    fun encrypt(data: ByteArray, key: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, data, key)

    fun decrypt(data: ByteArray, key: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, data, key)

    private fun run(mode: Int, data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(mode, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}
