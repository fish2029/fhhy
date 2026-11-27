package com.fanhuatv.core.network

import com.fanhuatv.core.model.BannerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 豆瓣热播榜轮播图源：拉「热播剧 + 热播电影」真实榜单，
 * 用豆瓣高清海报做首页轮播——比站源竖版海报硬裁的横图好看得多。
 * 拉不到（断网/豆瓣风控）返回空表，调用方回退站源图，不阻塞不报错。
 */
object DoubanBanners {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 Mobile Safari/604.1"

    /** 榜单集合：热播剧 + 热播电影，各取前几名合成轮播 */
    private val COLLECTIONS = listOf("tv_hot", "movie_hot")

    suspend fun fetch(): List<BannerConfig> = withContext(Dispatchers.IO) {
        val items = mutableListOf<BannerConfig>()
        for (collection in COLLECTIONS) {
            runCatching {
                val req = Request.Builder()
                    .url("https://m.douban.com/rexxar/api/v2/subject_collection/$collection/items?start=0&count=5")
                    .header("User-Agent", UA)
                    .header("Referer", "https://m.douban.com/")
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val body = resp.body?.string() ?: return@runCatching
                    val obj = JSONObject(body)
                    val arr = obj.optJSONArray("subject_collection_items") ?: return@runCatching
                    for (i in 0 until arr.length()) {
                        val entry = arr.optJSONObject(i) ?: continue
                        val pic = entry.optJSONObject("pic") ?: continue
                        val title = entry.optString("title")
                        if (title.isBlank()) continue
                        // 优先用条目自带剧照（横版，正适合轮播宽幅），/m/ 升级 /l/ 高清；
                        // 没有剧照再退回竖版海报 large
                        val photos = entry.optJSONArray("photos")
                        var url = ""
                        if (photos != null && photos.length() > 0) {
                            url = photos.optString(0).replace("/photo/m/", "/photo/l/")
                        }
                        if (url.isBlank() || !url.startsWith("http")) {
                            url = pic.optString("large").ifBlank { pic.optString("normal") }
                        }
                        if (url.isBlank() || !url.startsWith("http")) continue
                        val score = entry.optJSONObject("rating")?.optDouble("value") ?: 0.0
                        items.add(
                            BannerConfig(
                                title = title,
                                image = url,
                                targetId = "",
                                rating = if (score > 0) String.format("%.1f", score) else ""
                            )
                        )
                    }
                }
            }
        }
        items.take(8)
    }
}
