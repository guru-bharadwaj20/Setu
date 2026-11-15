package `in`.setu.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShortCodeTest {
    @Test
    fun `known vector`() {
        // sha256(32 zero bytes) starts 66 68 7a ad: bits 01100 11001 10100 00111 10101 01011
        assertEquals("CSM7NB", ShortCode.of(ByteArray(32)))
        assertEquals("CSM-7NB", ShortCode.display(ShortCode.of(ByteArray(32))))
    }

    @Test
    fun `deterministic and alphabet-safe`() {
        val id = Identity.generate()
        val a = ShortCode.of(id.publicKey)
        assertEquals(a, ShortCode.of(id.publicKey))
        assertEquals(6, a.length)
        assertTrue(a.none { it in "ILOU" })
    }

    @Test
    fun `normalise tolerates human input`() {
        assertEquals("CSM7NB", ShortCode.normalise("csm-7nb"))
        assertEquals("CSM7NB", ShortCode.normalise(" CSM 7NB "))
        assertEquals("C0M1NB", ShortCode.normalise("com-inb"))
        assertNull(ShortCode.normalise("CSM7N"))
        assertNull(ShortCode.normalise("CSM7N*"))
    }
}
