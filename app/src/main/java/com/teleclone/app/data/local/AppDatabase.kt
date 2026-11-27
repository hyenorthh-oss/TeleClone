package com.teleclone.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teleclone.app.data.model.CloneMessageEntity
import com.teleclone.app.data.model.CloneTaskEntity

@Database(
    entities = [CloneTaskEntity::class, CloneMessageEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cloneTaskDao(): CloneTaskDao
    abstract fun cloneMessageDao(): CloneMessageDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN isBatchMode INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN batchBlockSize INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN batchJitterMinMs INTEGER NOT NULL DEFAULT 2500")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN batchJitterMaxMs INTEGER NOT NULL DEFAULT 2500")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN preserveAlbumIntegrity INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN totalFound INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN videoCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN photoCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN otherCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN topicCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN filteredSkippedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN failedPermanentCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN lastFailureReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE clone_tasks ADD COLUMN targetIsForum INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS clone_messages (
                        taskId INTEGER NOT NULL,
                        messageId INTEGER NOT NULL,
                        albumId INTEGER NOT NULL,
                        originThreadId INTEGER NOT NULL,
                        targetThreadId INTEGER NOT NULL,
                        mediaType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        targetMessageId INTEGER NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        errorMessage TEXT NOT NULL,
                        PRIMARY KEY(taskId, messageId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_clone_messages_taskId_status ON clone_messages(taskId, status)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "teleclone.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
