package `in`.setu.app.sos

import android.util.Log
import `in`.setu.app.Graph
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketId
import `in`.setu.protocol.PacketType
import `in`.setu.routing.SOS_RESEND_INTERVAL_SECONDS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

/**
 * Owns the user's own SOS: creating it, resending it on schedule, and
 * noticing when an ACK for it comes back through the mesh or the relay.
 */
class SosController(private val g: Graph) {
    /** Ids of every SOS this phone has sent this process, so ACKs for old resends still count. */
    private val mySosIds: MutableSet<PacketId> = Collections.synchronizedSet(HashSet())

    suspend fun send(input: SosInput): Packet = withContext(Dispatchers.IO) {
        val fix = g.location.current()
        val packet = g.composer.sos(input, fix, g.battery.read().percent)
        g.store.accept(packet)
        mySosIds += packet.id
        val now = g.clock()
        g.settings.setActiveSos(packet.id.hex, since = now)
        Log.i(TAG, "sent $packet")
        packet
    }

    /** Re-issues the active SOS with fresh time and position when the interval has passed. */
    suspend fun resendIfDue(): Boolean = withContext(Dispatchers.IO) {
        val s = g.settings.snapshot()
        val idHex = s.activeSosIdHex ?: return@withContext false
        val now = g.clock()
        if (now - s.sosLastSentAt < SOS_RESEND_INTERVAL_SECONDS) return@withContext false
        val previous = g.store.get(PacketId.fromHex(idHex)) ?: run {
            g.settings.setActiveSos(null, 0)
            return@withContext false
        }
        val fix = g.location.current(timeoutMs = 5_000)
        val packet = g.composer.resend(previous, fix, g.battery.read().percent)
        g.store.accept(packet)
        mySosIds += packet.id
        g.settings.setActiveSos(packet.id.hex, since = s.activeSosSince, lastSent = now)
        Log.i(TAG, "resent $packet")
        true
    }

    /** Sends a SAFE packet and clears the active SOS. */
    suspend fun markSafe() = withContext(Dispatchers.IO) {
        val fix = g.location.current(timeoutMs = 5_000)
        g.store.accept(g.composer.safe(fix, g.battery.read().percent))
        g.settings.setActiveSos(null, 0)
    }

    /** Called for every packet the mesh or the relay hands us. Returns true if it acknowledges our SOS. */
    fun onPacketAccepted(p: Packet): Boolean {
        if (p.type != PacketType.ACK) return false
        val ref = p.body.ref ?: return false
        val mine = ref in mySosIds ||
            g.store.get(ref)?.body?.origin?.contentEquals(g.identityStore.identity.publicKey) == true
        if (!mine) return false
        g.scope.launch { g.settings.setSosAckedAt(g.clock()) }
        return true
    }

    private companion object {
        const val TAG = "SosController"
    }
}
