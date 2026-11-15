package `in`.setu.routing

import `in`.setu.protocol.Identity
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketBody
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PacketStoreTest {
    private var now = 1_800_000_000L
    private val me = Identity.fromSeed(ByteArray(32) { 3 })

    private fun packet(type: PacketType = PacketType.SOS, ts: Long = now, hops: Int = 0, ttl: Long = type.defaultTtl, ref: Packet? = null): Packet {
        val body = PacketBody(me.publicKey, type, ts, 100, 200, ttl = ttl, ref = ref?.id)
        var p = PacketCodec.create(body, me)
        repeat(hops) { p = p.relayed() }
        return p
    }

    private fun store(capacity: Int = 2000, storage: PacketStorage = MemoryStorage()) =
        PacketStore(storage, capacity) { now }

    @Test
    fun `accept dedupes and keeps the fewest hops`() {
        val s = store()
        val p = packet(hops = 5)
        assertTrue(s.accept(p))
        assertFalse(s.accept(p))
        assertFalse(s.accept(packet(hops = 2)))
        assertEquals(2, s.get(p.id)!!.hops)
        assertFalse(s.accept(packet(hops = 9)))
        assertEquals(2, s.get(p.id)!!.hops)
        assertEquals(1, s.size)
    }

    @Test
    fun `expired packets are refused and purged`() {
        val s = store()
        val p = packet(ttl = 60)
        assertTrue(s.accept(p))
        now += 61
        assertEquals(1, s.expire())
        assertEquals(0, s.size)
        assertFalse(s.accept(p))
    }

    @Test
    fun `eviction removes expired first then lowest priority`() {
        val s = store(capacity = 3)
        val expiring = packet(PacketType.SOS, ts = now - 100, ttl = 101)
        val safe = packet(PacketType.SAFE)
        val ackRef = packet(PacketType.SOS, ts = now - 5)
        val ack = packet(PacketType.ACK, ref = ackRef)
        assertTrue(s.accept(expiring)); assertTrue(s.accept(safe)); assertTrue(s.accept(ack))
        now += 2 // `expiring` is now dead
        assertTrue(s.accept(packet(PacketType.SOS, ts = now)))
        assertEquals(3, s.size)
        assertFalse(s.contains(expiring.id))
        assertTrue(s.contains(safe.id))
        // Next insertion must evict SAFE (lowest priority), not the ACK or SOS.
        assertTrue(s.accept(packet(PacketType.SOS, ts = now + 1)))
        assertFalse(s.contains(safe.id))
        assertTrue(s.contains(ack.id))
    }

    @Test
    fun `missingFrom honours peer filter and priority order`() {
        val s = store()
        val sos = packet(PacketType.SOS, hops = 3)
        val sosNear = packet(PacketType.SOS, ts = now - 1, hops = 1)
        val safe = packet(PacketType.SAFE)
        val ack = packet(PacketType.ACK, ref = sos)
        listOf(safe, ack, sos, sosNear).forEach { assertTrue(s.accept(it)) }
        val peer = BloomFilter().apply { add(ack.id) }
        val missing = s.missingFrom(peer)
        assertEquals(listOf(sosNear.id, sos.id, safe.id), missing.map { it.id })
        assertEquals(1, s.missingFrom(peer, limit = 1).size)
        assertTrue(s.missingFrom(s.bloom()).isEmpty())
    }

    @Test
    fun `packets at the hop limit are stored but not relayed`() {
        val s = store()
        val p = packet(hops = 31)
        assertTrue(s.accept(p))
        assertTrue(s.missingFrom(BloomFilter()).isEmpty())
    }

    @Test
    fun `reloads from storage and drops expired on startup`() {
        val mem = MemoryStorage()
        val s1 = store(storage = mem)
        val live = packet()
        val dead = packet(ttl = 10)
        s1.accept(live); s1.accept(dead)
        now += 20
        val s2 = store(storage = mem)
        assertEquals(1, s2.size)
        assertTrue(s2.contains(live.id))
    }

    @Test
    fun `acksFor finds acknowledgements by reference`() {
        val s = store()
        val sos = packet()
        val other = packet(ts = now - 1)
        val ack = packet(PacketType.ACK, ref = sos)
        listOf(sos, other, ack).forEach(s::accept)
        assertEquals(listOf(ack.id), s.acksFor(setOf(sos.id)).map { it.id })
        assertTrue(s.acksFor(setOf(other.id)).isEmpty())
    }
}
