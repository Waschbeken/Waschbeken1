package de.waschbeken.heliolink.core

import java.util.zip.CRC32

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff

internal fun ByteArray.u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

internal fun ByteArray.u32(i: Int): Long =
    (u8(i).toLong()) or (u8(i + 1).toLong() shl 8) or (u8(i + 2).toLong() shl 16) or (u8(i + 3).toLong() shl 24)

internal fun ByteArray.s32(i: Int): Int = u32(i).toInt()

internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

internal fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

internal fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
    val crc = CRC32()
    crc.update(data, offset, length)
    return crc.value.toInt()
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Parses a hex string such as the 32 character auth key, tolerating a "0x" prefix and spaces. */
fun parseHex(input: String): ByteArray? {
    val clean = input.trim().removePrefix("0x").removePrefix("0X").replace(" ", "").replace(":", "")
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    if (!clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
