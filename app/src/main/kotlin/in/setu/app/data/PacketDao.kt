package `in`.setu.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PacketDao {
    @Query("SELECT * FROM packets")
    fun allBlocking(): List<PacketEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(entity: PacketEntity)

    @Query("DELETE FROM packets WHERE idHex IN (:ids)")
    fun deleteBlocking(ids: List<String>)

    @Query("SELECT COUNT(*) FROM packets")
    fun count(): Flow<Int>

    @Query("SELECT COUNT(*) FROM packets WHERE type = :type")
    fun countByType(type: Int): Flow<Int>

    @Query("SELECT * FROM packets WHERE uploaded = 0 ORDER BY type ASC, hops ASC, ts DESC LIMIT :limit")
    suspend fun pendingUpload(limit: Int): List<PacketEntity>

    @Query("UPDATE packets SET uploaded = 1 WHERE idHex IN (:ids)")
    suspend fun markUploaded(ids: List<String>)

    @Query("SELECT * FROM packets WHERE originHex = :originHex AND type = :type ORDER BY ts DESC LIMIT 1")
    fun latestFromOrigin(originHex: String, type: Int): Flow<PacketEntity?>
}
