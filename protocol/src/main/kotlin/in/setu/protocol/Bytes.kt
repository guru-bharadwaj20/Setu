package `in`.setu.protocol

import java.security.MessageDigest

private const val HEX = "0123456789abcdef"

fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        out[i * 2] = HEX[v ushr 4]
        out[i * 2 + 1] = HEX[v and 0x0f]
    }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) or Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

/**
 * 64-bit FNV-1a over `data`, optionally followed by one extra byte. Used for
 * bloom filter indices: `fnv1a64(id, i)` is the hash of `id || i`.
 */
fun fnv1a64(data: ByteArray, extra: Int = -1): Long {
    var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325 as a signed literal
    for (b in data) {
        h = h xor (b.toLong() and 0xff)
        h *= 0x100000001b3L
    }
    if (extra >= 0) {
        h = h xor (extra.toLong() and 0xff)
        h *= 0x100000001b3L
    }
    return h
}
