package com.fanhuatv.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val vodId: String,
    val title: String,
    val cover: String,
    val siteKey: String,
    val source: String = "json",
    val scriptId: String = "",
    val playUrl: String = "",
    val progress: Long = 0L,
    val duration: Long = 0L,
    /** 上次看到的一集（集名），断点续播时展示与定位 */
    val episode: String = "",
    /** 上次看的这集的剧集 id（详情页 playableUrl），续播时拿它重新解析新直链——
     *  播放地址多是带时效 token 的，存下来的直链过会儿就失效，直接重放会报容器不支持 */
    val episodeId: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "favorite")
data class FavoriteEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val vodId: String,
    val title: String,
    val cover: String,
    val siteKey: String,
    val source: String = "json",
    val scriptId: String = "",
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val keyword: String,
    val searchedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "saved_script")
data class SavedScriptEntity(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val siteName: String,
    val author: String,
    val filePath: String,
    val enabled: Boolean = true,
    val importedAt: Long = System.currentTimeMillis()
)
