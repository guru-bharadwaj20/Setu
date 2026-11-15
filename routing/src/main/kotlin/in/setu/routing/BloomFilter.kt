package `in`.setu.routing

import `in`.setu.protocol.Frame
import `in`.setu.protocol.PacketId

/**
 * Fixed-size bloom filter over packet ids, wire-compatible with the HELLO
 * frame: 32768 bits, 7 indices, each the low 15 bits of one big-endian
 * 16-bit word of the id (ids are SHA-256 prefixes, so they are already random).
 *
 * At the 2000-packet store bound the false positive rate is below 0.1 %, and
 * about 2 % at 4000 ids. A false positive only
 * means one neighbour is not offered one packet, which the next neighbour fixes.
 */
class BloomFilter private constructor(private val bits: ByteArray) {

    constructor() : this(ByteArray(Frame.BLOOM_BYTES))

    private var count = 0

    /** Number of ids added through this instance (unknown for filters parsed from the wire). */
    val insertions: Int get() = count

    fun add(id: PacketId) {
        for (i in 0 until Frame.BLOOM_HASHES) {
            val idx = index(id, i)
            bits[idx ushr 3] = (bits[idx ushr 3].toInt() or (1 shl (idx and 7))).toByte()
        }
        count++
    }

    fun mightContain(id: PacketId): Boolean {
        for (i in 0 until Frame.BLOOM_HASHES) {
            val idx = index(id, i)
            if (bits[idx ushr 3].toInt() and (1 shl (idx and 7)) == 0) return false
        }
        return true
    }

    fun toBytes(): ByteArray = bits.copyOf()

    /** Fraction of bits set; above about 0.5 the filter is saturated and should be rebuilt. */
    fun fillRatio(): Double {
        var set = 0
        for (b in bits) set += Integer.bitCount(b.toInt() and 0xff)
        return set.toDouble() / Frame.BLOOM_BITS
    }

    companion object {
        fun fromBytes(bytes: ByteArray): BloomFilter {
            require(bytes.size == Frame.BLOOM_BYTES) { "bloom must be ${Frame.BLOOM_BYTES} bytes" }
            return BloomFilter(bytes.copyOf())
        }

        fun index(id: PacketId, i: Int): Int =
            (((id.bytes[2 * i].toInt() and 0xff) shl 8) or (id.bytes[2 * i + 1].toInt() and 0xff)) and (Frame.BLOOM_BITS - 1)
    }
}
