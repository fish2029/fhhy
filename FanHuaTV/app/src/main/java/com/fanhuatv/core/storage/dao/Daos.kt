package com.fanhuatv.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fanhuatv.core.storage.entity.FavoriteEntity
import com.fanhuatv.core.storage.entity.HistoryEntity
import com.fanhuatv.core.storage.entity.SavedScriptEntity
import com.fanhuatv.core.storage.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE vodId = :vodId LIMIT 1")
    suspend fun find(vodId: String): HistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryEntity)

    @Query("UPDATE history SET progress = :progress, duration = :duration, episode = :episode, episodeId = :episodeId, updatedAt = :time WHERE vodId = :vodId")
    suspend fun updateProgress(vodId: String, progress: Long, duration: Long, episode: String, episodeId: String, time: Long)

    @Query("DELETE FROM history WHERE vodId = :vodId")
    suspend fun deleteByVodId(vodId: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorite ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorite WHERE vodId = :vodId LIMIT 1")
    suspend fun find(vodId: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: FavoriteEntity)

    @Query("DELETE FROM favorite WHERE vodId = :vodId")
    suspend fun delete(vodId: String)

    @Query("DELETE FROM favorite")
    suspend fun clear()
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT 20")
    fun observeAll(): Flow<List<SearchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SearchHistoryEntity)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface SavedScriptDao {
    @Query("SELECT * FROM saved_script ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<SavedScriptEntity>>

    @Query("SELECT * FROM saved_script WHERE id = :id LIMIT 1")
    suspend fun find(id: String): SavedScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SavedScriptEntity)

    @Query("UPDATE saved_script SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM saved_script WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM saved_script")
    suspend fun clear()
}
