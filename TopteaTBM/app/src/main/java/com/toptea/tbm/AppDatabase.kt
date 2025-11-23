package com.toptea.tbm

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LocalSong::class, PlaySchedule::class, LocalPlaylist::class, AppConfig::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "toptea_tbm_db"
                )
                    .fallbackToDestructiveMigration() // 清除旧测试数据，强制重建
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}