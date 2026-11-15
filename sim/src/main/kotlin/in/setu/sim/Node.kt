package `in`.setu.sim

import `in`.setu.protocol.Identity
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketBody
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketType
import `in`.setu.routing.DutyCycle
import `in`.setu.routing.PacketStore
import java.util.Random

/** One virtual phone. */
class Node(
    val index: Int,
    var x: Double,
    var y: Double,
    var battery: Double,
    val identity: Identity,
    clock: () -> Long,
) {
    val store = PacketStore(clock = clock)
    var connected = false
    var activeSos = false
    var exchanges = 0
    var acknowledged = false

    /** Seconds elapsed within the current duty-cycle period. */
    private var phase = 0L

    fun dutyCycle(): DutyCycle = DutyCycle.forState(battery.toInt(), activeSos)

    /** True when the radio is in its discovery window at this instant. */
    fun isAwake(): Boolean = battery > 0 && phase < dutyCycle().discoverySeconds

    fun advance(stepSeconds: Long, cfg: SimConfig) {
        val dc = dutyCycle()
        val awake = isAwake()
        phase = (phase + stepSeconds) % dc.periodSeconds
        if (awake) battery -= cfg.drainPerActiveHour * stepSeconds / 3600.0
        if (battery < 0) battery = 0.0
    }

    fun distanceTo(o: Node): Double {
        val dx = x - o.x
        val dy = y - o.y
        return Math.sqrt(dx * dx + dy * dy)
    }

    fun raiseSos(now: Long, rnd: Random): Packet {
        val body = PacketBody(
            origin = identity.publicKey,
            type = PacketType.SOS,
            ts = now,
            latE5 = (12_97000 + y * 0.9).toInt(),
            lonE5 = (77_59000 + x * 0.9).toInt(),
            headcount = 1 + rnd.nextInt(5),
            needs = rnd.nextInt(32),
            battery = battery.toInt(),
        )
        val p = PacketCodec.create(body, identity)
        store.accept(p)
        activeSos = true
        return p
    }
}
