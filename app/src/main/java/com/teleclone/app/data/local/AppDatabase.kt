package com.teleclone.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teleclone.app.data.model.CloneTaskEntity

@Database(
    entities = [CloneTaskEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun cloneTaskDao(): CloneTaskDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "teleclone.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
