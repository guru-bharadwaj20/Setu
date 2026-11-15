package `in`.setu.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FrameCodecTest {
    @Test
    fun `frames round trip`() {
        val bloom = ByteArray(Frame.BLOOM_BYTES) { (it % 7).toByte() }
        assertEquals(Frame.Hello(bloom), FrameCodec.decode(FrameCodec.encode(Frame.Hello(bloom))))
        val pkt = Frame.PacketFrame(byteArrayOf(1, 2, 3))
        assertEquals(pkt, FrameCodec.decode(FrameCodec.encode(pkt)))
        assertEquals(Frame.Bye, FrameCodec.decode(FrameCodec.encode(Frame.Bye)))
        assertEquals("820240", FrameCodec.encode(Frame.Bye).toHex())
    }

    @Test
    fun `malformed frames are rejected`() {
        assertThrows(PacketException::class.java) {
            FrameCodec.decode(CborWriter().array(2).uint(9).bytes(ByteArray(0)).bytes())
        }
        assertThrows(PacketException::class.java) {
            FrameCodec.decode(CborWriter().array(2).uint(0).bytes(ByteArray(10)).bytes())
        }
        assertThrows(PacketException::class.java) {
            FrameCodec.decode(CborWriter().array(2).uint(1).bytes(ByteArray(300)).bytes())
        }
        assertThrows(PacketException::class.java) { FrameCodec.decode(ByteArray(Frame.MAX_FRAME_BYTES + 1)) }
        assertThrows(PacketException::class.java) { FrameCodec.decode(byteArrayOf(0x01)) }
    }
}
