package `in`.setu.protocol

/** Frames exchanged between two peers over a reliable byte stream. */
sealed class Frame {
    class Hello(val bloom: ByteArray) : Frame() {
        override fun equals(other: Any?) = other is Hello && bloom.contentEquals(other.bloom)
        override fun hashCode() = bloom.contentHashCode()
    }

    class PacketFrame(val packetBytes: ByteArray) : Frame() {
        override fun equals(other: Any?) = other is PacketFrame && packetBytes.contentEquals(other.packetBytes)
        override fun hashCode() = packetBytes.contentHashCode()
    }

    data object Bye : Frame()

    companion object {
        const val KIND_HELLO = 0L
        const val KIND_PACKET = 1L
        const val KIND_BYE = 2L
        const val BLOOM_BYTES = 4096
        const val BLOOM_BITS = BLOOM_BYTES * 8
        const val BLOOM_HASHES = 7

        /** Largest legal frame: header plus a full bloom filter. */
        const val MAX_FRAME_BYTES = BLOOM_BYTES + 16
    }
}

object FrameCodec {
    fun encode(f: Frame): ByteArray = when (f) {
        is Frame.Hello -> CborWriter().array(2).uint(Frame.KIND_HELLO).bytes(f.bloom).bytes()
        is Frame.PacketFrame -> CborWriter().array(2).uint(Frame.KIND_PACKET).bytes(f.packetBytes).bytes()
        Frame.Bye -> CborWriter().array(2).uint(Frame.KIND_BYE).bytes(ByteArray(0)).bytes()
    }

    fun decode(bytes: ByteArray): Frame {
        if (bytes.size > Frame.MAX_FRAME_BYTES) throw PacketException("frame too large")
        try {
            val r = CborReader(bytes)
            if (r.array() != 2) throw PacketException("frame must be a 2-array")
            val kind = r.uint()
            val payload = r.bytes()
            r.end()
            return when (kind) {
                Frame.KIND_HELLO -> {
                    if (payload.size != Frame.BLOOM_BYTES) throw PacketException("bloom must be ${Frame.BLOOM_BYTES} bytes")
                    Frame.Hello(payload)
                }
                Frame.KIND_PACKET -> {
                    if (payload.size > Protocol.MAX_PACKET_BYTES) throw PacketException("packet payload too large")
                    Frame.PacketFrame(payload)
                }
                Frame.KIND_BYE -> Frame.Bye
                else -> throw PacketException("unknown frame kind $kind")
            }
        } catch (e: CborException) {
            throw PacketException("cbor: ${e.message}")
        }
    }
}
