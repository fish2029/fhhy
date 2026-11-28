package com.fanhuatv.feature.history

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 一部 TMDB 影视：官方高清海报 + 资料行（年份/评分）+ 简介 + 类型（movie/tv） */
data class TmdbItem(
    val title: String,
    val poster: String,
    val backdrop: String,
    val rating: String,
    val year: String,
    val overview: String,
    val mediaType: String
)

/**
 * TMDB 影视资料客户端：热门电影 / 热门剧集榜单，官方原图海报。
 * 需要 API Key（我的 → TMDB 配置 里填）；网络不通或 Key 无效时返回空列表，不阻塞界面。
 */
object TmdbClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
    const val BACKDROP_BASE = "https://image.tmdb.org/t/p/w780"

    /** 阻塞调用，请在 IO 线程执行；page 从 1 开始 */
    fun trending(kind: String, key: String, page: Int, language: String = "zh-CN"): List<TmdbItem> {
        if (key.isBlank()) return emptyList()
        return runCatching {
            // 国内网络 api.themoviedb.org 常连不通，优先走官方备用域名 api.tmdb.org，失败再试主域名
            val hosts = listOf("https://api.tmdb.org", "https://api.themoviedb.org")
            var lastBody: String? = null
            var parsed: List<TmdbItem>? = null
            for (base in hosts) {
                val url = "$base/3/trending/$kind/week" +
                    "?api_key=" + URLEncoder.encode(key, "UTF-8") +
                    "&language=" + URLEncoder.encode(language, "UTF-8") +
                    "&page=$page"
                val req = Request.Builder().url(url).build()
                val ok = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { lastBody = null; false } else {
                        lastBody = resp.body?.string()
                        true
                    }
                }
                if (ok && lastBody != null) {
                    parsed = parseTrending(lastBody!!, kind)
                    if (parsed.isNotEmpty()) return@runCatching parsed
                }
            }
            return@runCatching parsed ?: emptyList<TmdbItem>()
        }.getOrDefault(emptyList())
    }

    /** 解析 trending 响应体；无海报或无片名的条目跳过 */
    private fun parseTrending(body: String, kind: String): List<TmdbItem> {
        val results: JSONArray = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val posterPath = o.optString("poster_path").orEmpty()
            if (posterPath.isBlank()) return@mapNotNull null
            val title = when (kind) {
                "tv" -> o.optString("name").ifBlank { o.optString("original_name") }
                else -> o.optString("title").ifBlank { o.optString("original_title") }
            }
            if (title.isBlank()) return@mapNotNull null
            val date = when (kind) {
                "tv" -> o.optString("first_air_date")
                else -> o.optString("release_date")
            }
            val rating = o.optDouble("vote_average", 0.0)
            val backdropPath = o.optString("backdrop_path").orEmpty()
            TmdbItem(
                title = title,
                poster = IMG_BASE + posterPath,
                backdrop = if (backdropPath.isBlank()) "" else BACKDROP_BASE + backdropPath,
                rating = if (rating > 0) String.format("%.1f", rating) else "",
                year = if (date.length >= 4) date.take(4) else "",
                overview = o.optString("overview").orEmpty(),
                mediaType = kind
            )
        }
    }
}
