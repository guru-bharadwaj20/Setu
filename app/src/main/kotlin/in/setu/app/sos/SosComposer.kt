package `in`.setu.app.sos

import `in`.setu.app.location.Fix
import `in`.setu.protocol.Identity
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketBody
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketType
import `in`.setu.protocol.Protocol
import kotlin.math.roundToInt

/** What the user filled in on the SOS screen. */
data class SosInput(
    val headcount: Int,
    val needs: Int,
    val note: String,
)

/**
 * Turns UI input, a location fix and battery level into signed packets.
 * Pure Kotlin so it is unit-tested without Android.
 */
class SosComposer(private val identity: Identity, private val clock: () -> Long) {

    fun sos(input: SosInput, fix: Fix?, batteryPercent: Int): Packet {
        val body = PacketBody(
            origin = identity.publicKey,
            type = PacketType.SOS,
            ts = clock(),
            latE5 = fix?.let { (it.lat * 1e5).roundToInt() } ?: 0,
            lonE5 = fix?.let { (it.lon * 1e5).roundToInt() } ?: 0,
            accuracyM = fix?.accuracyM?.coerceIn(0, 65_000) ?: 0,
            headcount = input.headcount.coerceIn(0, Protocol.MAX_HEADCOUNT),
            needs = input.needs,
            battery = batteryPercent.coerceIn(0, 100),
            note = trimNote(input.note),
        )
        return PacketCodec.create(body, identity)
    }

    /** A fresh copy of an active SOS with the current time, position and battery. */
    fun resend(previous: Packet, fix: Fix?, batteryPercent: Int): Packet {
        val b = previous.body
        return sos(
            SosInput(b.headcount, b.needs, b.note ?: ""),
            fix ?: Fix(b.lat, b.lon, b.accuracyM, 0),
            batteryPercent,
        )
    }

    fun safe(fix: Fix?, batteryPercent: Int): Packet {
        val body = PacketBody(
            origin = identity.publicKey,
            type = PacketType.SAFE,
            ts = clock(),
            latE5 = fix?.let { (it.lat * 1e5).roundToInt() } ?: 0,
            lonE5 = fix?.let { (it.lon * 1e5).roundToInt() } ?: 0,
            accuracyM = fix?.accuracyM?.coerceIn(0, 65_000) ?: 0,
            headcount = 0,
            battery = batteryPercent.coerceIn(0, 100),
        )
        return PacketCodec.create(body, identity)
    }

    companion object {
        /** Cuts a note to the 40-byte UTF-8 budget without splitting a character. */
        fun trimNote(note: String): String? {
            val t = note.trim()
            if (t.isEmpty()) return null
            var end = t.length
            while (end > 0 && t.substring(0, end).toByteArray(Charsets.UTF_8).size > Protocol.MAX_NOTE_BYTES) end--
            // Do not end on a high surrogate.
            if (end > 0 && Character.isHighSurrogate(t[end - 1])) end--
            return t.substring(0, end).ifEmpty { null }
        }
    }
}
