package `in`.setu.protocol

import java.io.ByteArrayOutputStream

/**
 * Minimal CBOR (RFC 8949) codec covering exactly what the protocol needs:
 * unsigned and negative integers, byte strings, text strings, arrays and maps.
 * The encoder always produces the shortest integer form and the decoder
 * rejects anything else, which is what keeps packet ids stable across
 * implementations.
 */
class CborWriter {
    private val out = ByteArrayOutputStream(192)

    fun bytes(): ByteArray = out.toByteArray()

    fun uint(value: Long): CborWriter = apply {
        require(value >= 0) { "uint must be non-negative" }
        head(0, value)
    }

    fun int(value: Long): CborWriter = apply {
        if (value >= 0) head(0, value) else head(1, -1 - value)
    }

    fun bytes(value: ByteArray): CborWriter = apply {
        head(2, value.size.toLong())
        out.write(value)
    }

    fun text(value: String): CborWriter = apply {
        val b = value.toByteArray(Charsets.UTF_8)
        head(3, b.size.toLong())
        out.write(b)
    }

    fun array(size: Int): CborWriter = apply { head(4, size.toLong()) }

    fun map(size: Int): CborWriter = apply { head(5, size.toLong()) }

    private fun head(major: Int, value: Long) {
        val m = major shl 5
        when {
            value < 24 -> out.write(m or value.toInt())
            value < 0x100 -> {
                out.write(m or 24)
                out.write(value.toInt())
            }
            value < 0x10000 -> {
                out.write(m or 25)
                out.write((value shr 8).toInt())
                out.write(value.toInt())
            }
            value < 0x100000000L -> {
                out.write(m or 26)
                for (s in 24 downTo 0 step 8) out.write((value shr s).toInt())
            }
            else -> {
                out.write(m or 27)
                for (s in 56 downTo 0 step 8) out.write((value shr s).toInt())
            }
        }
    }
}

class CborException(message: String) : Exception(message)

class CborReader(private val buf: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = buf.size - pos

    private fun next(): Int {
        if (pos >= buf.size) throw CborException("unexpected end of input")
        return buf[pos++].toInt() and 0xff
    }

    private fun argument(initial: Int): Long {
        val info = initial and 0x1f
        return when {
            info < 24 -> info.toLong()
            info == 24 -> next().toLong().also { if (it < 24) throw CborException("non-minimal int") }
            info == 25 -> ((next().toLong() shl 8) or next().toLong())
                .also { if (it < 0x100) throw CborException("non-minimal int") }
            info == 26 -> {
                var v = 0L
                repeat(4) { v = (v shl 8) or next().toLong() }
                if (v < 0x10000) throw CborException("non-minimal int")
                v
            }
            info == 27 -> {
                var v = 0L
                repeat(8) { v = (v shl 8) or next().toLong() }
                if (v < 0x100000000L) throw CborException("non-minimal or oversized int")
                v
            }
            else -> throw CborException("indefinite length or reserved additional info $info")
        }
    }

    private fun expect(major: Int, what: String): Long {
        val initial = next()
        if (initial ushr 5 != major) throw CborException("expected $what, got major ${initial ushr 5}")
        return argument(initial)
    }

    fun uint(): Long = expect(0, "uint")

    fun int(): Long {
        val initial = next()
        val arg = argument(initial)
        return when (val major = initial ushr 5) {
            0 -> arg
            1 -> -1 - arg
            else -> throw CborException("expected int, got major $major")
        }
    }

    fun bytes(): ByteArray {
        val n = expect(2, "bytes")
        if (n > remaining) throw CborException("byte string exceeds input")
        val out = buf.copyOfRange(pos, pos + n.toInt())
        pos += n.toInt()
        return out
    }

    fun text(): String {
        val n = expect(3, "text")
        if (n > remaining) throw CborException("text string exceeds input")
        val out = String(buf, pos, n.toInt(), Charsets.UTF_8)
        pos += n.toInt()
        return out
    }

    fun array(): Int = expect(4, "array").toInt()

    fun map(): Int = expect(5, "map").toInt()

    fun end() {
        if (pos != buf.size) throw CborException("trailing bytes after value")
    }
}
