package `in`.setu.routing

import `in`.setu.protocol.Frame
import `in`.setu.protocol.FrameCodec
import `in`.setu.protocol.Identity
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketBody
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExchangeSessionTest {
    private val now = 1_800_000_000L

    private fun sos(id: Identity, ts: Long = now): Packet =
        PacketCodec.create(PacketBody(id.publicKey, PacketType.SOS, ts, 10, 20), id)

    /** Runs a full symmetric exchange between two stores and returns both results. */
    private fun exchange(a: PacketStore, b: PacketStore, max: Int = 400): Pair<ExchangeResult, ExchangeResult> {
        val sa = ExchangeSession(a, { now }, max)
        val sb = ExchangeSession(b, { now }, max)
        val toB = ArrayDeque(sa.start())
        val toA = ArrayDeque<ByteArray>()
        var guard = 0
        while ((toB.isNotEmpty() || toA.isNotEmpty()) && guard++ < 10_000) {
            toB.removeFirstOrNull()?.let { toA.addAll(sb.onFrame(it)) }
            toA.removeFirstOrNull()?.let { toB.addAll(sa.onFrame(it)) }
        }
        assertTrue(sa.isDone && sb.isDone, "both sides must finish")
        return sa.result() to sb.result()
    }

    @Test
    fun `two phones end up with the union of their stores`() {
        val a = PacketStore { now }
        val b = PacketStore { now }
        val ida = Identity.generate()
        val idb = Identity.generate()
        val pa = (0 until 5).map { sos(ida, now - it) }
        val pb = (0 until 3).map { sos(idb, now - it) }
        val shared = sos(Identity.generate())
        (pa + shared).forEach(a::accept)
        (pb + shared).forEach(b::accept)

        val (ra, rb) = exchange(a, b)
        assertEquals(9, a.size)
        assertEquals(9, b.size)
        assertEquals(5, ra.sent); assertEquals(3, ra.accepted)
        assertEquals(3, rb.sent); assertEquals(5, rb.accepted)
        assertEquals(0, ra.rejected + rb.rejected)
        // Hop count grew by exactly one on the receiving side.
        pb.forEach { assertEquals(1, a.get(it.id)!!.hops) }
        pa.forEach { assertEquals(0, a.get(it.id)!!.hops) }
    }

    @Test
    fun `second exchange sends nothing`() {
        val a = PacketStore { now }
        val b = PacketStore { now }
        (0 until 4).forEach { a.accept(sos(Identity.generate(), now - it)) }
        exchange(a, b)
        val (ra, rb) = exchange(a, b)
        assertEquals(0, ra.sent + rb.sent)
    }

    @Test
    fun `per-exchange cap is respected and highest priority goes first`() {
        val a = PacketStore { now }
        val b = PacketStore { now }
        val id = Identity.generate()
        val safe = PacketCodec.create(PacketBody(id.publicKey, PacketType.SAFE, now, 0, 0), id)
        a.accept(safe)
        (0 until 10).forEach { a.accept(sos(Identity.generate(), now - it)) }
        val (ra, _) = exchange(a, b, max = 5)
        assertEquals(5, ra.sent)
        assertEquals(5, b.size)
        assertTrue(b.all().all { it.type == PacketType.SOS })
    }

    @Test
    fun `garbage and forged packets are counted as rejected and never stored`() {
        val b = PacketStore { now }
        val sb = ExchangeSession(b, { now })
        sb.start()
        val forged = PacketCodec.encode(sos(Identity.generate())).also { it[it.size - 10] = 0 }
        assertTrue(sb.onFrame(FrameCodec.encode(Frame.PacketFrame(forged))).isEmpty())
        assertTrue(sb.onFrame(byteArrayOf(1, 2, 3)).isEmpty())
        assertTrue(sb.onFrame(FrameCodec.encode(Frame.PacketFrame(byteArrayOf(0x40)))).isEmpty())
        assertEquals(3, sb.result().rejected)
        assertEquals(0, b.size)
    }

    @Test
    fun `receiver that did not start still replies with hello first`() {
        val b = PacketStore { now }
        val sb = ExchangeSession(b, { now })
        val out = sb.onFrame(FrameCodec.encode(Frame.Hello(BloomFilter().toBytes())))
        assertEquals(2, out.size)
        assertTrue(FrameCodec.decode(out[0]) is Frame.Hello)
        assertEquals(Frame.Bye, FrameCodec.decode(out[1]))
    }
}
