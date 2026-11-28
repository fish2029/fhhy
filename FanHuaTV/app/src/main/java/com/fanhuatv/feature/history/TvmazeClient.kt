package com.fanhuatv.feature.history

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 一次 TMaze 匹配结果：高清海报 + 资料行（英文名/年份/评分）+ 简介 */
data class TmShow(
    val poster: String,
    val originalName: String,
    val year: String,
    val rating: String,
    val summary: String
)

/**
 * TMaze 影视资料匹配：拿本地历史里的剧名搜官方资料，换高清海报、补英文名/年份/评分/简介。
 * 免 Key 免账号；搜不到或网络不通时静默回退本地封面，绝不阻塞海报墙展示。
 */
object TvmazeClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // 进程级缓存：同一剧名整个会话只查一次，null 也缓存避免反复打无效请求
    private val cache = ConcurrentHashMap<String, TmShow?>()

    /** 阻塞调用，请在 IO 线程执行 */
    fun search(query: String): TmShow? {
        if (query.isBlank()) return null
        cache[query]?.let { return it }
        val result = runCatching {
            val req = Request.Builder()
                .url("https://api.tvmaze.com/search/shows?q=" + URLEncoder.encode(query, "UTF-8"))
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val arr = JSONArray(resp.body?.string().orEmpty())
                if (arr.length() == 0) return@runCatching null
                val show = arr.getJSONObject(0).getJSONObject("show")
                val poster = show.optJSONObject("image")?.optString("original").orEmpty()
                if (poster.isBlank()) return@runCatching null
                val rating = show.optJSONObject("rating")?.optDouble("average")
                TmShow(
                    poster = poster,
                    originalName = show.optString("name"),
                    year = show.optString("premiered").take(4),
                    rating = if (rating != null && !rating.isNaN()) rating.toString() else "",
                    summary = show.optString("summary").replace(Regex("<[^>]*>"), "").trim()
                )
            }
        }.getOrNull()
        cache[query] = result
        return result
    }
}
