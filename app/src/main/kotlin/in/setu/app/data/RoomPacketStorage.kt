package `in`.setu.app.data

import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketId
import `in`.setu.routing.PacketStorage

/**
 * Persists the routing store in Room. Called from the store's own lock, off
 * the main thread (the store is only ever touched by the service and
 * background coroutines), so blocking DAO calls are fine here.
 */
class RoomPacketStorage(private val dao: PacketDao) : PacketStorage {
    override fun loadAll(): List<Packet> = dao.allBlocking().mapNotNull { it.toPacket() }

    override fun put(packet: Packet) = dao.upsertBlocking(PacketEntity.from(packet))

    override fun remove(ids: Collection<PacketId>) {
        if (ids.isEmpty()) return
        ids.map { it.hex }.chunked(500).forEach(dao::deleteBlocking)
    }
}
