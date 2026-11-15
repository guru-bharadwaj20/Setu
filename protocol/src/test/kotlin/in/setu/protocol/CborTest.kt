package `in`.setu.protocol

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CborTest {
    private fun enc(block: CborWriter.() -> Unit): String = CborWriter().apply(block).bytes().toHex()

    @Test
    fun `integers use shortest form per RFC 8949 appendix A`() {
        assertEquals("00", enc { uint(0) })
        assertEquals("17", enc { uint(23) })
        assertEquals("1818", enc { uint(24) })
        assertEquals("18ff", enc { uint(255) })
        assertEquals("190100", enc { uint(256) })
        assertEquals("1a00010000", enc { uint(65536) })
        assertEquals("1b0000000100000000", enc { uint(4294967296) })
        assertEquals("20", enc { int(-1) })
        assertEquals("3863", enc { int(-100) })
        assertEquals("3a000f423f", enc { int(-1000000) })
    }

    @Test
    fun `strings arrays and maps`() {
        assertEquals("4401020304", enc { bytes(byteArrayOf(1, 2, 3, 4)) })
        assertEquals("6449455446", enc { text("IETF") })
        assertEquals("62c3bc", enc { text("ü") })
        assertEquals("83010203", enc { array(3); uint(1); uint(2); uint(3) })
        assertEquals("a201020304", enc { map(2); uint(1); uint(2); uint(3); uint(4) })
    }

    @Test
    fun `round trips`() {
        val bytes = CborWriter().apply {
            array(4); int(-70000); uint(Long.MAX_VALUE); text("नमस्ते"); bytes(ByteArray(300) { it.toByte() })
        }.bytes()
        val r = CborReader(bytes)
        assertEquals(4, r.array())
        assertEquals(-70000L, r.int())
        assertEquals(Long.MAX_VALUE, r.uint())
        assertEquals("नमस्ते", r.text())
        assertArrayEquals(ByteArray(300) { it.toByte() }, r.bytes())
        r.end()
    }

    @Test
    fun `rejects non-minimal integers`() {
        assertThrows(CborException::class.java) { CborReader("1800".hexToBytes()).uint() }
        assertThrows(CborException::class.java) { CborReader("190010".hexToBytes()).uint() }
    }

    @Test
    fun `rejects truncated input, indefinite lengths and trailing bytes`() {
        assertThrows(CborException::class.java) { CborReader("4401".hexToBytes()).bytes() }
        assertThrows(CborException::class.java) { CborReader("0000".hexToBytes()).apply { uint() }.end() }
        assertThrows(CborException::class.java) { CborReader("5f".hexToBytes()).bytes() }
    }

    @Test
    fun `rejects wrong major type`() {
        assertThrows(CborException::class.java) { CborReader("6449455446".hexToBytes()).bytes() }
        assertThrows(CborException::class.java) { CborReader("20".hexToBytes()).uint() }
    }
}
