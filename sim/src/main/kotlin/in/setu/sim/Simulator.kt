package `in`.setu.sim

import `in`.setu.protocol.Identity
import `in`.setu.protocol.PacketBody
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketId
import `in`.setu.protocol.PacketType
import `in`.setu.routing.ExchangeSession
import java.util.Random

/** Everything we measure. */
data class SimReport(
    val config: SimConfig,
    val sosRaised: Int,
    val sosDelivered: Int,
    val sosAcknowledged: Int,
    val deliverySeconds: List<Long>,
    val hopsAtDelivery: List<Int>,
    val totalExchanges: Int,
    val totalFramesSent: Long,
    val meanBatteryDrop: Double,
) {
    val deliveryRatio: Double get() = if (sosRaised == 0) 0.0 else sosDelivered.toDouble() / sosRaised
    val ackRatio: Double get() = if (sosRaised == 0) 0.0 else sosAcknowledged.toDouble() / sosRaised
    fun medianDeliverySeconds(): Long = deliverySeconds.sorted().let { if (it.isEmpty()) -1 else it[it.size / 2] }
    fun p90DeliverySeconds(): Long = deliverySeconds.sorted().let { if (it.isEmpty()) -1 else it[(it.size * 9) / 10] }
    fun meanHops(): Double = if (hopsAtDelivery.isEmpty()) 0.0 else hopsAtDelivery.average()
    fun maxHops(): Int = hopsAtDelivery.maxOrNull() ?: 0

    fun summary(): String = buildString {
        appendLine("nodes=${config.nodes} area=${config.areaMetres.toInt()}m range=${config.radioRangeMetres.toInt()}m " +
            "connected=${(config.connectedFraction * 100).toInt()}% duration=${config.durationSeconds}s seed=${config.seed}")
        appendLine("SOS raised        : $sosRaised")
        appendLine("SOS reached relay : $sosDelivered  (${"%.1f".format(deliveryRatio * 100)} %)")
        appendLine("SOS acknowledged  : $sosAcknowledged  (${"%.1f".format(ackRatio * 100)} %)")
        appendLine("delivery time     : median ${medianDeliverySeconds()}s, p90 ${p90DeliverySeconds()}s")
        appendLine("hops at delivery  : mean ${"%.1f".format(meanHops())}, max ${maxHops()}")
        appendLine("exchanges         : $totalExchanges, frames $totalFramesSent")
        appendLine("mean battery drop : ${"%.2f".format(meanBatteryDrop)} %")
    }
}

/**
 * Discrete-time simulator. Every step: move nodes, flip connectivity, let
 * awake nodes in range run the real [ExchangeSession] against each other, and
 * let connected nodes drain to a relay that answers with ACKs.
 */
class Simulator(private val cfg: SimConfig) {
    private val rnd = Random(cfg.seed)
    private var now = START
    private val nodes: List<Node>
    private val relay = Relay()
    private val sosRaisedAt = HashMap<PacketId, Long>()
    private val sosOwner = HashMap<PacketId, Node>()
    private val deliveredAt = HashMap<PacketId, Long>()
    private val hopsAtDelivery = HashMap<PacketId, Int>()
    private var exchanges = 0
    private var frames = 0L
    private val initialBattery: Double

    init {
        nodes = (0 until cfg.nodes).map { i ->
            Node(
                index = i,
                x = rnd.nextDouble() * cfg.areaMetres,
                y = rnd.nextDouble() * cfg.areaMetres,
                battery = (cfg.batteryMin + rnd.nextInt(cfg.batteryMax - cfg.batteryMin + 1)).toDouble(),
                identity = Identity.fromSeed(ByteArray(32).also(rnd::nextBytes)),
                clock = { now },
            )
        }
        initialBattery = nodes.map { it.battery }.average()
        val connectedCount = Math.round(cfg.nodes * cfg.connectedFraction).toInt()
        nodes.shuffled(rnd).take(connectedCount).forEach { it.connected = true }
        val sosCount = Math.round(cfg.nodes * cfg.sosFraction).toInt()
        nodes.shuffled(rnd).take(sosCount).forEach { n ->
            val p = n.raiseSos(now, rnd)
            sosRaisedAt[p.id] = now
            sosOwner[p.id] = n
        }
    }

    fun run(): SimReport {
        repeat(cfg.steps) { step() }
        return report()
    }

    private fun step() {
        now += cfg.stepSeconds
        for (n in nodes) {
            move(n)
            if (rnd.nextDouble() < cfg.linkFlipProbability) n.connected = !n.connected
        }
        // Keep the connected share roughly constant so results are comparable.
        val target = Math.round(cfg.nodes * cfg.connectedFraction).toInt()
        val connected = nodes.filter { it.connected }
        if (connected.size > target) connected.shuffled(rnd).take(connected.size - target).forEach { it.connected = false }

        val awake = nodes.filter { it.isAwake() }
        // Simple spatial bucketing to keep pair search near linear.
        val cell = cfg.radioRangeMetres
        val grid = HashMap<Long, MutableList<Node>>()
        for (n in awake) grid.getOrPut(key(n, cell)) { ArrayList() }.add(n)
        for (n in awake) {
            val cx = (n.x / cell).toInt()
            val cy = (n.y / cell).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                val bucket = grid[((cx + dx).toLong() shl 32) or ((cy + dy).toLong() and 0xffffffffL)] ?: continue
                for (m in bucket) {
                    if (m.index <= n.index) continue
                    if (n.distanceTo(m) <= cfg.radioRangeMetres) exchange(n, m)
                }
            }
        }
        for (n in nodes) {
            if (n.connected && n.store.size > 0) drain(n)
            n.advance(cfg.stepSeconds, cfg)
        }
    }

    private fun key(n: Node, cell: Double): Long =
        ((n.x / cell).toLong() shl 32) or ((n.y / cell).toLong() and 0xffffffffL)

    private fun move(n: Node) {
        if (cfg.walkSpeed <= 0) return
        val d = cfg.walkSpeed * cfg.stepSeconds
        val angle = rnd.nextDouble() * 2 * Math.PI
        n.x = (n.x + Math.cos(angle) * d).coerceIn(0.0, cfg.areaMetres)
        n.y = (n.y + Math.sin(angle) * d).coerceIn(0.0, cfg.areaMetres)
    }

    private fun exchange(a: Node, b: Node) {
        val sa = ExchangeSession(a.store, { now })
        val sb = ExchangeSession(b.store, { now })
        val toB = ArrayDeque(sa.start())
        val toA = ArrayDeque<ByteArray>()
        while (toB.isNotEmpty() || toA.isNotEmpty()) {
            toB.removeFirstOrNull()?.let { frames++; toA.addAll(sb.onFrame(it)) }
            toA.removeFirstOrNull()?.let { frames++; toB.addAll(sa.onFrame(it)) }
        }
        exchanges++
        a.exchanges++
        b.exchanges++
        for (n in listOf(a, b)) {
            if (!n.acknowledged && n.activeSos) checkAck(n)
        }
    }

    private fun checkAck(n: Node) {
        val mine = n.store.byType(PacketType.SOS).filter { it.origin.contentEquals(n.identity.publicKey) }.map { it.id }.toSet()
        if (n.store.acksFor(mine).isNotEmpty()) n.acknowledged = true
    }

    private fun drain(n: Node) {
        val acks = relay.upload(n.store.all(), now)
        for (p in n.store.all()) {
            if (p.type == PacketType.SOS && p.id in sosRaisedAt && p.id !in deliveredAt) {
                deliveredAt[p.id] = now
                hopsAtDelivery[p.id] = p.hops
            }
        }
        acks.forEach(n.store::accept)
        if (n.activeSos && !n.acknowledged) checkAck(n)
    }

    private fun report(): SimReport {
        val delivered = deliveredAt.keys
        val acknowledged = sosOwner.values.count { it.acknowledged }
        return SimReport(
            config = cfg,
            sosRaised = sosRaisedAt.size,
            sosDelivered = delivered.size,
            sosAcknowledged = acknowledged,
            deliverySeconds = delivered.map { deliveredAt[it]!! - sosRaisedAt[it]!! },
            hopsAtDelivery = delivered.map { hopsAtDelivery[it]!! },
            totalExchanges = exchanges,
            totalFramesSent = frames,
            meanBatteryDrop = initialBattery - nodes.map { it.battery }.average(),
        )
    }

    /** Stand-in for the Cloudflare relay: stores everything, ACKs every SOS once. */
    private inner class Relay {
        private val identity = Identity.fromSeed(ByteArray(32) { 0x52 })
        private val seen = HashSet<PacketId>()
        private val acks = HashMap<PacketId, `in`.setu.protocol.Packet>()

        fun upload(packets: List<`in`.setu.protocol.Packet>, now: Long): List<`in`.setu.protocol.Packet> {
            val out = ArrayList<`in`.setu.protocol.Packet>()
            for (p in packets) {
                seen.add(p.id)
                if (p.type == PacketType.SOS) {
                    val ack = acks.getOrPut(p.id) {
                        PacketCodec.create(
                            PacketBody(identity.publicKey, PacketType.ACK, now, p.body.latE5, p.body.lonE5, ref = p.id),
                            identity,
                        )
                    }
                    out += ack
                }
            }
            return out
        }
    }

    companion object {
        const val START = 1_800_000_000L
    }
}
