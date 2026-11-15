package `in`.setu.routing

import `in`.setu.protocol.Frame
import `in`.setu.protocol.PacketId
import `in`.setu.protocol.hexToBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class BloomFilterTest {
    private fun id(seed: Long): PacketId {
        val r = Random(seed)
        return PacketId(ByteArray(16).also(r::nextBytes))
    }

    @Test
    fun `no false negatives`() {
        val f = BloomFilter()
        val ids = (0L until 4000L).map(::id)
        ids.forEach(f::add)
        assertTrue(ids.all(f::mightContain))
        assertEquals(4000, f.insertions)
    }

    @Test
    fun `false positive rate is below one percent at the store bound`() {
        val f = BloomFilter()
        (0L until PacketStore.DEFAULT_CAPACITY.toLong()).forEach { f.add(id(it)) }
        val probes = 20_000
        val fp = (100_000L until 100_000L + probes).count { f.mightContain(id(it)) }
        assertTrue(fp < probes / 100, "false positives: $fp of $probes")
        assertTrue(f.fillRatio() < 0.5)
    }

    @Test
    fun `wire round trip preserves membership`() {
        val f = BloomFilter()
        (0L until 100L).forEach { f.add(id(it)) }
        val bytes = f.toBytes()
        assertEquals(Frame.BLOOM_BYTES, bytes.size)
        val g = BloomFilter.fromBytes(bytes)
        assertTrue((0L until 100L).all { g.mightContain(id(it)) })
        assertArrayEquals(bytes, g.toBytes())
        assertFalse(BloomFilter().mightContain(id(1)))
    }

    @Test
    fun `index matches the spec formula`() {
        val id = PacketId("0123456789abcdeffedcba9876543210".hexToBytes())
        assertEquals(0x0123, BloomFilter.index(id, 0))
        assertEquals(0x4567, BloomFilter.index(id, 1))
        assertEquals(0x09ab and 0x7fff, BloomFilter.index(id, 2))
        assertEquals(0xcdef and 0x7fff, BloomFilter.index(id, 3))
        assertEquals(0xfedc and 0x7fff, BloomFilter.index(id, 4))
        assertEquals(0xba98 and 0x7fff, BloomFilter.index(id, 5))
        assertEquals(0x7654, BloomFilter.index(id, 6))
    }
}
