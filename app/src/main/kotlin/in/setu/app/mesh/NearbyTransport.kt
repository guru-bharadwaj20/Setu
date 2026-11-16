package `in`.setu.app.mesh

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import `in`.setu.protocol.Packet
import `in`.setu.routing.ExchangeSession
import `in`.setu.routing.PacketStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one [ExchangeSession] per connected endpoint over Nearby Connections.
 *
 * Advertising stays on while the service runs (it is cheap, mostly BLE).
 * Discovery is the expensive part and is switched by the duty cycle. Every
 * phone both advertises and discovers, so either side can initiate; Nearby
 * resolves the race when both request at once.
 */
class NearbyTransport(
    context: Context,
    private val store: PacketStore,
    private val clock: () -> Long,
    private val stats: MeshStats,
    private val onAccepted: (Packet) -> Unit,
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val sessions = ConcurrentHashMap<String, ExchangeSession>()
    private val lastExchange = ConcurrentHashMap<String, Long>()

    @Volatile var advertising = false; private set
    @Volatile var discovering = false; private set

    fun startAdvertising() {
        if (advertising) return
        advertising = true
        client.startAdvertising(
            ENDPOINT_NAME, SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnFailureListener { fail("advertise", it) }
    }

    fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        client.stopAdvertising()
    }

    fun startDiscovery() {
        if (discovering) return
        discovering = true
        stats.update { it.copy(radioOn = true) }
        client.startDiscovery(
            SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build(),
        ).addOnFailureListener { fail("discover", it) }
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        stats.update { it.copy(radioOn = false) }
        client.stopDiscovery()
    }

    fun stopAll() {
        stopDiscovery()
        stopAdvertising()
        client.stopAllEndpoints()
        sessions.clear()
    }

    private val discovery: EndpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val last = lastExchange[endpointId] ?: 0L
            if (clock() - last < EXCHANGE_COOLDOWN_SECONDS) return
            client.requestConnection(ENDPOINT_NAME, endpointId, lifecycle)
                .addOnFailureListener { if (!alreadyConnected(it)) fail("request", it) }
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val lifecycle: ConnectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // No pairing UI: packets are signed, so a hostile peer can only waste bandwidth.
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode != ConnectionsStatusCodes.STATUS_OK) return
            stats.peerMet()
            val session = ExchangeSession(store, clock, listener = object : ExchangeSession.Listener {
                override fun onAccepted(packet: Packet) { this@NearbyTransport.onAccepted(packet) }
            })
            sessions[endpointId] = session
            session.start().forEach { send(endpointId, it) }
        }

        override fun onDisconnected(endpointId: String) {
            sessions.remove(endpointId)?.let { finish(endpointId, it) }
        }
    }

    private val payloads: PayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            val session = sessions[endpointId] ?: return
            try {
                session.onFrame(bytes).forEach { send(endpointId, it) }
            } catch (e: Exception) {
                Log.w(TAG, "bad frame from $endpointId", e)
                client.disconnectFromEndpoint(endpointId)
                return
            }
            if (session.isDone) {
                sessions.remove(endpointId)
                finish(endpointId, session)
                client.disconnectFromEndpoint(endpointId)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun send(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private fun finish(endpointId: String, session: ExchangeSession) {
        val r = session.result()
        lastExchange[endpointId] = clock()
        stats.exchangeDone(r.sent, r.received)
        Log.i(TAG, "exchange with $endpointId: sent=${r.sent} received=${r.received} accepted=${r.accepted} complete=${r.complete}")
    }

    private fun alreadyConnected(e: Exception): Boolean =
        e.message?.contains("8003") == true || e.message?.contains("ALREADY_CONNECTED") == true

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, "$what failed", e)
        stats.error("$what: ${e.message}")
    }

    companion object {
        private const val TAG = "NearbyTransport"
        const val SERVICE_ID = "in.setu.mesh.v1"
        private const val ENDPOINT_NAME = "setu"
        /** Do not re-exchange with the same endpoint more often than this. */
        const val EXCHANGE_COOLDOWN_SECONDS = 60L
    }
}
