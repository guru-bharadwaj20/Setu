package `in`.setu.protocol

/** Protocol constants shared by every implementation. See docs/PROTOCOL.md. */
object Protocol {
    const val VERSION = 1
    const val ORIGIN_BYTES = 32
    const val SIG_BYTES = 64
    const val ID_BYTES = 16
    const val MAX_PACKET_BYTES = 256
    const val MAX_HOPS = 32
    const val MAX_TTL_SECONDS = 259_200L
    const val MAX_CLOCK_SKEW_SECONDS = 3_600L
    const val MAX_NOTE_BYTES = 40
    const val MAX_HEADCOUNT = 99

    const val TTL_SOS_SECONDS = 172_800L
    const val TTL_ACK_SECONDS = 259_200L
    const val TTL_SAFE_SECONDS = 86_400L

    /** Keys of the signed body map. */
    object Key {
        const val V = 0
        const val ORIGIN = 1
        const val TYPE = 2
        const val TS = 3
        const val LAT = 4
        const val LON = 5
        const val ACC = 6
        const val HEAD = 7
        const val NEEDS = 8
        const val BATT = 9
        const val TTL = 10
        const val REF = 11
        const val NOTE = 12
    }
}

enum class PacketType(val code: Int, val defaultTtl: Long, val priority: Int) {
    SOS(0, Protocol.TTL_SOS_SECONDS, priority = 0),
    ACK(1, Protocol.TTL_ACK_SECONDS, priority = 1),
    SAFE(2, Protocol.TTL_SAFE_SECONDS, priority = 2);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

/** Bit flags for the `needs` field. */
object Needs {
    const val WATER = 1
    const val FOOD = 2
    const val MEDICAL = 4
    const val TRAPPED = 8
    const val EVACUATE = 16
    const val ALL = WATER or FOOD or MEDICAL or TRAPPED or EVACUATE

    fun labels(flags: Int): List<String> = buildList {
        if (flags and WATER != 0) add("water")
        if (flags and FOOD != 0) add("food")
        if (flags and MEDICAL != 0) add("medical")
        if (flags and TRAPPED != 0) add("trapped")
        if (flags and EVACUATE != 0) add("evacuate")
    }
}

/** A 16-byte packet id with value semantics. */
class PacketId(val bytes: ByteArray) {
    init {
        require(bytes.size == Protocol.ID_BYTES) { "id must be ${Protocol.ID_BYTES} bytes" }
    }

    val hex: String get() = bytes.toHex()

    override fun equals(other: Any?): Boolean = other is PacketId && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    override fun toString(): String = hex

    companion object {
        fun fromHex(hex: String): PacketId = PacketId(hex.hexToBytes())
    }
}

/**
 * The signed part of a packet. Immutable; building one does not sign it.
 * Latitude and longitude are fixed-point with 5 decimals.
 */
data class PacketBody(
    val origin: ByteArray,
    val type: PacketType,
    val ts: Long,
    val latE5: Int,
    val lonE5: Int,
    val accuracyM: Int = 0,
    val headcount: Int = 1,
    val needs: Int = 0,
    val battery: Int = 0,
    val ttl: Long = type.defaultTtl,
    val ref: PacketId? = null,
    val note: String? = null,
) {
    init {
        require(origin.size == Protocol.ORIGIN_BYTES) { "origin must be 32 bytes" }
        require(ts >= 0) { "ts must be non-negative" }
        require(latE5 in -90_00000..90_00000) { "lat out of range" }
        require(lonE5 in -180_00000..180_00000) { "lon out of range" }
        require(accuracyM >= 0) { "accuracy must be non-negative" }
        require(headcount in 0..Protocol.MAX_HEADCOUNT) { "headcount out of range" }
        require(needs and Needs.ALL.inv() == 0) { "unknown needs flag" }
        require(battery in 0..100) { "battery out of range" }
        require(ttl in 1..Protocol.MAX_TTL_SECONDS) { "ttl out of range" }
        require(type != PacketType.ACK || ref != null) { "ACK needs a ref" }
        require(note == null || note.toByteArray(Charsets.UTF_8).size <= Protocol.MAX_NOTE_BYTES) {
            "note longer than ${Protocol.MAX_NOTE_BYTES} bytes"
        }
    }

    /** False for the (0, 0) "no fix" position, docs/PROTOCOL.md "No fix". */
    val hasPosition: Boolean get() = latE5 != 0 || lonE5 != 0

    val lat: Double get() = latE5 / 1e5
    val lon: Double get() = lonE5 / 1e5
    val expiresAt: Long get() = ts + ttl

    fun isExpired(now: Long): Boolean = expiresAt <= now

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketBody) return false
        return origin.contentEquals(other.origin) && type == other.type && ts == other.ts &&
            latE5 == other.latE5 && lonE5 == other.lonE5 && accuracyM == other.accuracyM &&
            headcount == other.headcount && needs == other.needs && battery == other.battery &&
            ttl == other.ttl && ref == other.ref && note == other.note
    }

    override fun hashCode(): Int {
        var h = origin.contentHashCode()
        h = 31 * h + type.hashCode()
        h = 31 * h + ts.hashCode()
        h = 31 * h + latE5
        h = 31 * h + lonE5
        h = 31 * h + (ref?.hashCode() ?: 0)
        return h
    }
}

/**
 * A complete packet as carried over the mesh: the encoded body, its signature,
 * and the mutable hop count. `bodyBytes` is kept verbatim so the id and the
 * signature stay stable no matter who re-encodes the packet.
 */
class Packet(
    val body: PacketBody,
    val bodyBytes: ByteArray,
    val signature: ByteArray,
    val hops: Int,
) {
    init {
        require(signature.size == Protocol.SIG_BYTES) { "signature must be 64 bytes" }
        require(hops in 0 until Protocol.MAX_HOPS) { "hops out of range" }
    }

    val id: PacketId by lazy { PacketId(sha256(bodyBytes).copyOf(Protocol.ID_BYTES)) }
    val type: PacketType get() = body.type
    val origin: ByteArray get() = body.origin

    /** The same packet after one more transfer between phones. */
    fun relayed(): Packet = Packet(body, bodyBytes, signature, hops + 1)

    fun canRelay(): Boolean = hops + 1 < Protocol.MAX_HOPS

    override fun equals(other: Any?): Boolean = other is Packet && id == other.id && hops == other.hops
    override fun hashCode(): Int = id.hashCode() * 31 + hops
    override fun toString(): String = "Packet(${type.name} id=${id.hex.take(8)} hops=$hops ts=${body.ts})"
}
