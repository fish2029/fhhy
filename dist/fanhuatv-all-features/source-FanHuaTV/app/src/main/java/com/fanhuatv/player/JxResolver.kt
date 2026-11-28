package com.fanhuatv.player

import com.fanhuatv.core.model.FlagConfig
import com.fanhuatv.core.model.ParseConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * TVBox jx 解析接口接入：parse=1/jx=1 的片源先走配置里的解析接口拿真实地址，
 * 拿不到再回退 WebView 嗅探——两条路都试，谁先出真实流用谁。
 *
 * TVBox 约定：
 * - parses[]: 全局解析接口列表，type 0=JSON 1=JSONP 2=网页播放页
 * - flags[]:  指定线路（flag）强制走指定接口，exclusions 是该线路下不走解析的地址片段
 * - 请求方式：解析接口 URL 直接拼接播放页地址（parseUrl + playUrl）
 * - 返回：{"code":1/200,"url":"真实流地址"}；JSONP 外层是回调函数包裹
 *
 * 原则不变：接口返回什么就是什么——返回直链才采信，返回网页地址原样交给
 * 嗅探流程，不伪造、不注入。
 */
object JxResolver {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * 尝试用解析接口解析播放页地址。
     * @return 真实流地址 + 请求头；接口没配/失败/返回的不是直链时返回 null（回退嗅探）
     */
    suspend fun resolve(
        playUrl: String,
        playFlag: String,
        parses: List<ParseConfig>,
        flags: List<FlagConfig>
    ): Pair<String, Map<String, String>?>? = withContext(Dispatchers.IO) {
        if (playUrl.isBlank() || !playUrl.startsWith("http")) return@withContext null
        if (parses.isEmpty() && flags.isEmpty()) return@withContext null

        // ① 线路精确匹配优先：flags 里指定了这条线路的接口就用它（排除片段命中的除外）
        val flagMatch = flags.firstOrNull { it.flag == playFlag.trim() }
        if (flagMatch != null) {
            if (flagMatch.exclusions.any { it.isNotBlank() && playUrl.contains(it) }) {
                return@withContext null
            }
            if (flagMatch.url.startsWith("http")) {
                resolveOne(flagMatch.url, playUrl)?.let { return@withContext it }
            }
        }

        // ② 全局 parses：JSON/JSONP 接口逐个试（type 2 是网页播放页，直接交给嗅探）
        for (parse in parses) {
            when (parse.type) {
                0, 1 -> resolveOne(parse.url, playUrl)?.let { return@withContext it }
                else -> Unit // type 2：接口本身就是播放页，走 WebView 嗅探更合适
            }
        }
        null
    }

    /** 调单个解析接口：parseUrl + playUrl 拼接请求，从返回里取真实流地址 */
    private fun resolveOne(parseUrl: String, playUrl: String): Pair<String, Map<String, String>?>? {
        return runCatching {
            val request = Request.Builder()
                .url(parseUrl + playUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                extractUrl(body)?.takeIf { it.startsWith("http") }?.let { url ->
                    // 接口返回的直链才采信；返回的还是网页地址就交给嗅探流程
                    if (PlaySourceResolver.isVideoFormat(url)) url to null else null
                }
            }
        }.getOrNull()
    }

    /** 从返回体提取 url 字段：纯 JSON / JSONP（回调包裹）都认 */
    private fun extractUrl(body: String): String? {
        return runCatching {
            val jsonText = if (body.trim().startsWith("{")) body else {
                // JSONP：callback({...}) -> 取第一对配平花括号里的 JSON
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start >= 0 && end > start) body.substring(start, end + 1) else return null
            }
            val obj = JsonParser.parseString(jsonText).asJsonObject
            val url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            url.ifBlank { null }
        }.getOrNull()
    }
}
