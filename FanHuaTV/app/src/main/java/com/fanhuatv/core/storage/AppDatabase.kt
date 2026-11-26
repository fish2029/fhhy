package com.fanhuatv.core.storage

import androidx.room.Database
import androidx.room.RoomDatabase
import com.fanhuatv.core.storage.dao.FavoriteDao
import com.fanhuatv.core.storage.dao.HistoryDao
import com.fanhuatv.core.storage.dao.SavedScriptDao
import com.fanhuatv.core.storage.dao.SearchHistoryDao
import com.fanhuatv.core.storage.entity.FavoriteEntity
import com.fanhuatv.core.storage.entity.HistoryEntity
import com.fanhuatv.core.storage.entity.SavedScriptEntity
import com.fanhuatv.core.storage.entity.SearchHistoryEntity

@Database(
    entities = [
        HistoryEntity::class,
        FavoriteEntity::class,
        SearchHistoryEntity::class,
        SavedScriptEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun savedScriptDao(): SavedScriptDao

    companion object {
        /** v1 -> v2：history 加 episode（集名）列；保留旧历史不清库 */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN episode TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 -> v3：history 加 episodeId（剧集 id）列，断点续播重新解析新直链用 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE history ADD COLUMN episodeId TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}
