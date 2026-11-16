package `in`.setu.app.relay

import android.util.Log
import `in`.setu.app.Graph
import `in`.setu.protocol.CborReader
import `in`.setu.protocol.CborWriter
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketType
import `in`.setu.protocol.toHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Drains everything not yet uploaded to the relay in batches, highest priority
 * first, and feeds the ACKs that come back into the store so they ride the
 * mesh back to the people who sent the SOS.
 */
class RelayUploader(private val g: Graph) {
    private val mutex = Mutex()

    /** Returns the number of packets uploaded. Never throws; failures are logged and counted. */
    suspend fun uploadAll(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            var total = 0
            val base = g.settings.snapshot().relayUrl
            try {
                while (true) {
                    val batch = g.packetDao.pendingUpload(BATCH)
                    if (batch.isEmpty()) break
                    val acks = post(base, batch.map { e -> e.wire })
                    g.packetDao.markUploaded(batch.map { e -> e.idHex })
                    total += batch.size
                    acceptAcks(acks)
                    if (batch.size < BATCH) break
                }
                if (total > 0) {
                    g.stats.uploaded(total)
                    g.settings.setLastUploadAt(g.clock())
                }
                g.stats.error(null)
            } catch (e: Exception) {
                Log.w(TAG, "upload failed", e)
                g.stats.error("upload: ${e.message}")
            }
            total
        }
    }

    private fun acceptAcks(acks: List<ByteArray>) {
        val now = g.clock()
        val ids = ArrayList<String>()
        for (bytes in acks) {
            val p = PacketCodec.tryDecode(bytes, now) ?: continue
            if (p.type != PacketType.ACK) continue
            g.store.accept(p)
            ids += p.id.hex
            g.sos.onPacketAccepted(p)
        }
        // These came from the relay; do not send them straight back.
        if (ids.isNotEmpty()) g.scope.launch { g.packetDao.markUploaded(ids) }
    }

    private fun post(base: String, packets: List<ByteArray>): List<ByteArray> {
        val body = CborWriter().array(packets.size).also { w -> packets.forEach { w.bytes(it) } }.bytes()
        val conn = URL("$base/v1/upload").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/cbor")
            conn.setRequestProperty("Accept", "application/cbor")
            conn.setRequestProperty("X-Setu-Origin", g.identityStore.identity.publicKey.toHex())
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("relay returned HTTP $code")
            val response = conn.inputStream.use { it.readBytes() }
            return parseAcks(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseAcks(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val r = CborReader(bytes)
        val n = r.array()
        val out = ArrayList<ByteArray>(n)
        repeat(n) { out += r.bytes() }
        r.end()
        return out
    }

    companion object {
        private const val TAG = "RelayUploader"
        const val BATCH = 200
    }
}
