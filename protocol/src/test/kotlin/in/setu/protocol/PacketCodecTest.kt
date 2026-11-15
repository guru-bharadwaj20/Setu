package `in`.setu.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketCodecTest {
    private val now = 1_800_000_000L
    private val me = Identity.fromSeed(ByteArray(32) { 7 })

    private fun sos(ts: Long = now, note: String? = null, ttl: Long = Protocol.TTL_SOS_SECONDS) = PacketBody(
        origin = me.publicKey, type = PacketType.SOS, ts = ts,
        latE5 = 1297600, lonE5 = 7759460, accuracyM = 12, headcount = 4,
        needs = Needs.WATER or Needs.MEDICAL, battery = 63, ttl = ttl, note = note,
    )

    @Test
    fun `sos packet round trips and stays small`() {
        val p = PacketCodec.create(sos(note = "2nd floor, blue house"), me)
        val wire = PacketCodec.encode(p)
        assertTrue(wire.size <= Protocol.MAX_PACKET_BYTES, "size ${wire.size}")
        assertTrue(wire.size < 200, "expected under 200 bytes, got ${wire.size}")
        val q = PacketCodec.decode(wire, now)
        assertEquals(p.body, q.body)
        assertEquals(p.id, q.id)
        assertEquals(0, q.hops)
        assertArrayEquals(p.signature, q.signature)
    }

    @Test
    fun `body encoding is canonical and deterministic`() {
        val a = PacketCodec.encodeBody(sos())
        assertArrayEquals(a, PacketCodec.encodeBody(sos()))
        assertEquals(0xab, a[0].toInt() and 0xff) // map of 11 keys
        assertEquals(0xac, PacketCodec.encodeBody(sos(note = "x"))[0].toInt() and 0xff)
    }

    @Test
    fun `id is first 16 bytes of sha256 over body`() {
        val p = PacketCodec.create(sos(), me)
        assertArrayEquals(sha256(p.bodyBytes).copyOf(16), p.id.bytes)
        assertNotEquals(p.id, PacketCodec.create(sos(ts = now + 1), me).id)
    }

    @Test
    fun `hop count is outside signature and increments`() {
        val p = PacketCodec.create(sos(), me)
        val r = p.relayed().relayed()
        assertEquals(2, r.hops)
        assertEquals(p.id, r.id)
        val q = PacketCodec.decode(PacketCodec.encode(r), now)
        assertEquals(2, q.hops)
    }

    @Test
    fun `tampered body is rejected`() {
        val wire = PacketCodec.encode(PacketCodec.create(sos(), me))
        // Flip a bit inside the body (well before the 64-byte signature at the tail).
        val idx = wire.size - 70
        wire[idx] = (wire[idx].toInt() xor 0x01).toByte()
        assertNull(PacketCodec.tryDecode(wire, now))
    }

    @Test
    fun `validity rules`() {
        val p = PacketCodec.create(sos(), me)
        val wire = PacketCodec.encode(p)
        assertThrows(PacketException::class.java) { PacketCodec.decode(wire, now + Protocol.TTL_SOS_SECONDS) }
        assertThrows(PacketException::class.java) { PacketCodec.decode(wire, now - 3601) }
        PacketCodec.decode(wire, now - 3600) // exactly one hour of skew is allowed
        assertThrows(PacketException::class.java) { PacketCodec.decode(ByteArray(257), now) }
        assertThrows(IllegalArgumentException::class.java) { sos(ttl = Protocol.MAX_TTL_SECONDS + 1) }
        var far = p
        repeat(31) { far = far.relayed() }
        assertThrows(IllegalArgumentException::class.java) { far.relayed() }
        val overHop = PacketCodec.encode(far).also { it[it.size - 1] = 0x20 } // hops 31 -> encoded 0x18 0x1f; patch to 32
        assertThrows(PacketException::class.java) { PacketCodec.decode(overHop, now) }
    }

    @Test
    fun `ack references an sos`() {
        val s = PacketCodec.create(sos(), me)
        val relay = Identity.fromSeed(ByteArray(32) { 9 })
        val ack = PacketCodec.create(
            PacketBody(relay.publicKey, PacketType.ACK, now + 60, 0, 0, ref = s.id, ttl = Protocol.TTL_ACK_SECONDS),
            relay,
        )
        val back = PacketCodec.decode(PacketCodec.encode(ack), now)
        assertEquals(s.id, back.body.ref)
        assertThrows(IllegalArgumentException::class.java) { PacketBody(relay.publicKey, PacketType.ACK, now, 0, 0) }
    }

    @Test
    fun `wrong signer is rejected at creation`() {
        val other = Identity.fromSeed(ByteArray(32) { 1 })
        assertThrows(IllegalArgumentException::class.java) { PacketCodec.create(sos(), other) }
    }

    @Test
    fun `unknown keys and bad ordering are rejected`() {
        val w = CborWriter().map(1).uint(99).uint(1)
        assertThrows(PacketException::class.java) { PacketCodec.decodeBody(w.bytes()) }
        val good = PacketCodec.encodeBody(sos())
        // good = [a-b map header][00 01][01 58 20 <32 bytes>]... ; swap the first two pairs.
        val swapped = byteArrayOf(good[0]) + good.copyOfRange(3, 3 + 35) + good.copyOfRange(1, 3) + good.copyOfRange(38, good.size)
        assertThrows(PacketException::class.java) { PacketCodec.decodeBody(swapped) }
    }

    @Test
    fun `note limit is 40 bytes of utf8`() {
        assertThrows(IllegalArgumentException::class.java) { sos(note = "x".repeat(41)) }
        sos(note = "x".repeat(40))
        assertThrows(IllegalArgumentException::class.java) { sos(note = "न".repeat(14)) } // 42 bytes
    }
}
