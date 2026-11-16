package `in`.setu.app

import `in`.setu.app.location.Fix
import `in`.setu.app.sos.SosComposer
import `in`.setu.app.sos.SosInput
import `in`.setu.protocol.Identity
import `in`.setu.protocol.Needs
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketType
import `in`.setu.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SosComposerTest {
    private var now = 1_800_000_000L
    private val id = Identity.fromSeed(ByteArray(32) { 5 })
    private val composer = SosComposer(id) { now }

    @Test
    fun `builds a valid signed sos from input`() {
        val p = composer.sos(
            SosInput(headcount = 3, needs = Needs.WATER or Needs.TRAPPED, note = "  roof, north side  "),
            Fix(12.97160, 77.59456, 25, 0),
            batteryPercent = 42,
        )
        assertEquals(PacketType.SOS, p.type)
        assertEquals(1297160, p.body.latE5)
        assertEquals(7759456, p.body.lonE5)
        assertEquals(3, p.body.headcount)
        assertEquals("roof, north side", p.body.note)
        assertEquals(42, p.body.battery)
        val back = PacketCodec.decode(PacketCodec.encode(p), now)
        assertEquals(p.id, back.id)
    }

    @Test
    fun `sos without a fix still goes out`() {
        val p = composer.sos(SosInput(1, 0, ""), null, 80)
        assertEquals(0, p.body.latE5)
        assertEquals(0, p.body.accuracyM)
        assertNull(p.body.note)
    }

    @Test
    fun `out of range input is clamped not rejected`() {
        val p = composer.sos(SosInput(500, Needs.FOOD, ""), Fix(0.0, 0.0, 99_999, 0), 250)
        assertEquals(Protocol.MAX_HEADCOUNT, p.body.headcount)
        assertEquals(100, p.body.battery)
        assertEquals(65_000, p.body.accuracyM)
    }

    @Test
    fun `note is trimmed to 40 utf8 bytes on a character boundary`() {
        assertEquals("x".repeat(40), SosComposer.trimNote("x".repeat(45)))
        val devanagari = "न".repeat(20) // 3 bytes each
        assertEquals("न".repeat(13), SosComposer.trimNote(devanagari))
        val emoji = "a" + "😀".repeat(12) // 1 + 4*12 bytes
        val t = SosComposer.trimNote(emoji)!!
        assertTrue(t.toByteArray().size <= 40)
        assertTrue(!Character.isHighSurrogate(t.last()))
        assertNull(SosComposer.trimNote("   "))
    }

    @Test
    fun `resend keeps the message and refreshes time and battery`() {
        val first = composer.sos(SosInput(2, Needs.MEDICAL, "hello"), Fix(1.0, 2.0, 10, 0), 70)
        now += 600
        val again = composer.resend(first, null, 55)
        assertEquals(first.body.headcount, again.body.headcount)
        assertEquals(first.body.needs, again.body.needs)
        assertEquals(first.body.note, again.body.note)
        assertEquals(first.body.latE5, again.body.latE5)
        assertEquals(now, again.body.ts)
        assertEquals(55, again.body.battery)
        assertTrue(first.id != again.id)
    }

    @Test
    fun `safe packet has the right type and ttl`() {
        val p = composer.safe(Fix(1.0, 2.0, 5, 0), 30)
        assertEquals(PacketType.SAFE, p.type)
        assertEquals(Protocol.TTL_SAFE_SECONDS, p.body.ttl)
    }
}
