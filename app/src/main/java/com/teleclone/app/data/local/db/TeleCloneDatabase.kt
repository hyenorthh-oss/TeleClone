package com.teleclone.app.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.teleclone.app.data.local.db.dao.ChatDao
import com.teleclone.app.data.local.db.dao.MessageDao
import com.teleclone.app.data.local.db.entity.ChatEntity
import com.teleclone.app.data.local.db.entity.MessageEntity

@Database(
    entities = [ChatEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TeleCloneDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: TeleCloneDatabase? = null

        fun getInstance(context: Context): TeleCloneDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TeleCloneDatabase::class.java,
                    "teleclone.db",
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
