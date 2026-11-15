package `in`.setu.routing

import `in`.setu.protocol.Frame
import `in`.setu.protocol.FrameCodec
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketException

/** Outcome of one exchange with one neighbour. */
data class ExchangeResult(
    val sent: Int,
    val received: Int,
    val accepted: Int,
    val rejected: Int,
    val complete: Boolean,
)

/**
 * Drives one side of the HELLO / PACKET* / BYE exchange with a single peer.
 * Transport-agnostic: the caller feeds in received frames and ships out the
 * frames this returns. Both sides run the same state machine, so the protocol
 * is symmetric and either side may start.
 *
 * Usage:
 *   val s = ExchangeSession(store, clock)
 *   send(s.start())                       // our HELLO
 *   loop: for each inbound frame f: send(s.onFrame(f))
 *   until s.isDone
 */
class ExchangeSession(
    private val store: PacketStore,
    private val clock: () -> Long,
    private val maxPacketsPerExchange: Int = DEFAULT_MAX_PACKETS,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onAccepted(packet: Packet) {}
        fun onRejected(reason: String) {}
    }

    private var helloSent = false
    private var byeSent = false
    private var byeReceived = false
    private var sent = 0
    private var received = 0
    private var accepted = 0
    private var rejected = 0

    val isDone: Boolean get() = byeSent && byeReceived

    fun result() = ExchangeResult(sent, received, accepted, rejected, isDone)

    /** Our opening HELLO carrying the bloom filter of what we already hold. */
    fun start(): List<ByteArray> {
        check(!helloSent) { "already started" }
        helloSent = true
        return listOf(FrameCodec.encode(Frame.Hello(store.bloom().toBytes())))
    }

    /** Handles one inbound frame and returns frames to send in response. */
    fun onFrame(bytes: ByteArray): List<ByteArray> {
        val frame = try {
            FrameCodec.decode(bytes)
        } catch (e: PacketException) {
            rejected++
            listener?.onRejected("frame: ${e.message}")
            return emptyList()
        }
        return when (frame) {
            is Frame.Hello -> onHello(frame)
            is Frame.PacketFrame -> onPacket(frame)
            Frame.Bye -> {
                byeReceived = true
                emptyList()
            }
        }
    }

    private fun onHello(hello: Frame.Hello): List<ByteArray> {
        if (byeSent) return emptyList()
        val out = ArrayList<ByteArray>()
        if (!helloSent) out += start()
        val peer = BloomFilter.fromBytes(hello.bloom)
        for (p in store.missingFrom(peer, maxPacketsPerExchange)) {
            out += FrameCodec.encode(Frame.PacketFrame(PacketCodec.encode(p.relayed())))
            sent++
        }
        out += FrameCodec.encode(Frame.Bye)
        byeSent = true
        return out
    }

    private fun onPacket(frame: Frame.PacketFrame): List<ByteArray> {
        received++
        try {
            val p = PacketCodec.decode(frame.packetBytes, clock())
            if (store.accept(p)) {
                accepted++
                listener?.onAccepted(p)
            }
        } catch (e: PacketException) {
            rejected++
            listener?.onRejected(e.message ?: "invalid packet")
        }
        return emptyList()
    }

    companion object {
        /** Keeps a single Nearby connection under a few seconds even at 1 Mbit/s. */
        const val DEFAULT_MAX_PACKETS = 400
    }
}
