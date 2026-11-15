package `in`.setu.routing

import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketId
import `in`.setu.protocol.PacketType

/**
 * Ordering used for both transmission and retention:
 * SOS before ACK before SAFE, then fewer hops, then newer timestamp.
 */
val PacketPriority: Comparator<Packet> = compareBy<Packet> { it.type.priority }
    .thenBy { it.hops }
    .thenByDescending { it.body.ts }

/** Where a packet persists. The app backs this with Room; tests and the simulator use memory. */
interface PacketStorage {
    fun loadAll(): List<Packet>
    fun put(packet: Packet)
    fun remove(ids: Collection<PacketId>)
}

class MemoryStorage : PacketStorage {
    private val map = LinkedHashMap<PacketId, Packet>()
    override fun loadAll(): List<Packet> = map.values.toList()
    override fun put(packet: Packet) { map[packet.id] = packet }
    override fun remove(ids: Collection<PacketId>) { ids.forEach(map::remove) }
    val size: Int get() = map.size
}

/**
 * Bounded in-memory index over [PacketStorage]. Holds at most [capacity] live
 * packets; eviction removes expired packets first, then the lowest-priority
 * ones (the reverse of [PacketPriority]). Thread-safe via a single lock, which
 * is fine at the packet rates a phone sees.
 */
class PacketStore(
    private val storage: PacketStorage = MemoryStorage(),
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long,
) {
    private val lock = Any()
    private val packets = HashMap<PacketId, Packet>()
    private val seen = BloomFilter()

    init {
        require(capacity > 0)
        val now = clock()
        for (p in storage.loadAll()) {
            if (!p.body.isExpired(now)) {
                packets[p.id] = p
                seen.add(p.id)
            }
        }
        if (packets.size > capacity) evictTo(capacity)
    }

    val size: Int get() = synchronized(lock) { packets.size }

    fun contains(id: PacketId): Boolean = synchronized(lock) { id in packets }

    fun get(id: PacketId): Packet? = synchronized(lock) { packets[id] }

    /**
     * Adds a validated packet. Returns true when it was new. A copy with fewer
     * hops replaces an existing one so the best path survives.
     */
    fun accept(packet: Packet): Boolean = synchronized(lock) {
        if (packet.body.isExpired(clock())) return false
        val existing = packets[packet.id]
        if (existing != null) {
            if (packet.hops < existing.hops) {
                packets[packet.id] = packet
                storage.put(packet)
            }
            return false
        }
        if (packets.size >= capacity) evictTo(capacity - 1)
        packets[packet.id] = packet
        seen.add(packet.id)
        storage.put(packet)
        true
    }

    /** Bloom filter of every live id, as sent in HELLO. */
    fun bloom(): BloomFilter = synchronized(lock) {
        // Rebuild from live ids so expired or evicted packets do not pin bits forever.
        val f = BloomFilter()
        packets.keys.forEach(f::add)
        f
    }

    /** Live packets the peer lacks, in transmission order. */
    fun missingFrom(peer: BloomFilter, limit: Int = Int.MAX_VALUE): List<Packet> = synchronized(lock) {
        val now = clock()
        packets.values.asSequence()
            .filter { !it.body.isExpired(now) && !peer.mightContain(it.id) && it.canRelay() }
            .sortedWith(PacketPriority)
            .take(limit)
            .toList()
    }

    fun all(): List<Packet> = synchronized(lock) { packets.values.sortedWith(PacketPriority) }

    fun byType(type: PacketType): List<Packet> = synchronized(lock) {
        packets.values.filter { it.type == type }.sortedWith(PacketPriority)
    }

    /** ACKs whose `ref` names one of the given SOS ids. */
    fun acksFor(ids: Set<PacketId>): List<Packet> = synchronized(lock) {
        packets.values.filter { it.type == PacketType.ACK && it.body.ref in ids }
    }

    /** Drops expired packets. Returns how many were removed. */
    fun expire(): Int = synchronized(lock) {
        val now = clock()
        val dead = packets.values.filter { it.body.isExpired(now) }.map { it.id }
        dead.forEach(packets::remove)
        if (dead.isNotEmpty()) storage.remove(dead)
        dead.size
    }

    private fun evictTo(target: Int) {
        val now = clock()
        val victims = ArrayList<PacketId>()
        val expired = packets.values.filter { it.body.isExpired(now) }.map { it.id }
        victims += expired
        expired.forEach(packets::remove)
        if (packets.size > target) {
            val ordered = packets.values.sortedWith(PacketPriority.reversed())
            for (p in ordered.take(packets.size - target)) {
                victims += p.id
            }
            victims.drop(expired.size).forEach(packets::remove)
        }
        if (victims.isNotEmpty()) storage.remove(victims)
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}
