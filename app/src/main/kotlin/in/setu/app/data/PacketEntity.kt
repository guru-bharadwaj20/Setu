package `in`.setu.app.data

import `in`.setu.protocol.toHex
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import `in`.setu.protocol.Packet
import `in`.setu.protocol.PacketCodec
import `in`.setu.protocol.PacketException

/**
 * One stored packet. The wire bytes are the source of truth; the other columns
 * are denormalised so the UI can query without decoding every row.
 */
@Entity(
    tableName = "packets",
    indices = [Index("type"), Index("expiresAt"), Index("originHex")],
)
data class PacketEntity(
    @PrimaryKey val idHex: String,
    val type: Int,
    val originHex: String,
    val ts: Long,
    val expiresAt: Long,
    val hops: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val wire: ByteArray,
    /** Set when this device uploaded the packet to a relay. */
    val uploaded: Boolean = false,
) {
    /** Decodes without signature verification; the row was verified before it was written. */
    fun toPacket(): Packet? = try {
        PacketCodec.decode(wire, now = 0, verifySignature = false)
    } catch (_: PacketException) {
        null
    }

    companion object {
        fun from(p: Packet, uploaded: Boolean = false) = PacketEntity(
            idHex = p.id.hex,
            type = p.type.code,
            originHex = p.origin.toHex(),
            ts = p.body.ts,
            expiresAt = p.body.expiresAt,
            hops = p.hops,
            wire = PacketCodec.encode(p),
            uploaded = uploaded,
        )
    }
}
