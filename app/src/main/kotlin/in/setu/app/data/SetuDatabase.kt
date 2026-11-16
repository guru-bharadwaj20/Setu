package `in`.setu.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PacketEntity::class], version = 1, exportSchema = true)
abstract class SetuDatabase : RoomDatabase() {
    abstract fun packets(): PacketDao

    companion object {
        fun open(context: Context): SetuDatabase =
            Room.databaseBuilder(context, SetuDatabase::class.java, "setu.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
