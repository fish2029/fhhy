package com.fanhuatv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * B 站弹幕自动匹配：jar 源没给弹幕地址时，按剧名 + 集数去 B 站匹配弹幕库。
 * 链路：wbi 签名搜索 → 视频 bvid → view 接口拿分 P cid → list.so 拉弹幕 XML → 解析。
 * 全程匿名（无 Cookie），失败一律返回空列表，不影响播放。
 */
object BilibiliDanmaku {

    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    /** wbi 混淆表（B 站公开算法，固定不变） */
    private val MIXIN_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 63, 52, 59, 56, 62, 20, 57, 54, 44, 36, 22, 25,
        34, 21, 11, 6
    )

    /** 匹配结果：时间轴 + 文本（与播放页弹幕管线同构） */
    data class Result(val list: List<Pair<Long, String>>, val message: String)

    /**
     * 按剧名 + 集数匹配弹幕。
     * @param title 剧名（详情页 item.name）
     * @param episode 集名（如「第03集」「03」「第3话」），空则取第 1 P
     */
    suspend fun match(title: String, episode: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val keyword = cleanTitle(title)
            if (keyword.isBlank()) return@runCatching Result(emptyList(), "片名无效")
            val candidates = searchCandidates(keyword)
            if (candidates.isEmpty()) return@runCatching Result(emptyList(), "未找到匹配视频")
            // 最多试 3 个候选：正片合集也可能没弹幕（搬运资源常见），换下一个碰运气
            for (bvid in candidates.take(3)) {
                val cid = pickCid(bvid, episode)
                if (cid <= 0L) continue
                val xml = fetchDanmakuXml(cid) ?: continue
                val list = parseDanmakuXml(xml)
                if (list.isNotEmpty()) return@runCatching Result(list, "已匹配 ${list.size} 条弹幕")
            }
            Result(emptyList(), "弹幕为空")
        }.getOrDefault(Result(emptyList(), "匹配失败"))
    }

    /** 去掉剧名里的清晰度/来源后缀等噪音，提高搜索命中率 */
    internal fun cleanTitle(raw: String): String = raw
        .replace(Regex("\\[.*?]|【.*?】"), "")
        .replace(Regex("\\d{3,4}[PpKk]|高清|蓝光|国语|粤语|4K|1080P|720P", RegexOption.IGNORE_CASE), "")
        .trim()

    // 噪音标题：OST/主题曲/花絮/reaction 等非正片内容，匹配到也只会是空弹幕
    private val NOISE = Regex(
        "OST|主题曲|插曲|原声带|配乐|reaction|直播|预告|花絮|解说|饭制|路透|采访|报道|混剪|速看|MV",
        RegexOption.IGNORE_CASE
    )

    // 正片特征：标题带集数（第X集/X集全/全集/更新至X集）或时长超过 20 分钟
    private val EP_MARK = Regex("第\\s*\\d+\\s*[集话期]|\\d+\\s*集\\s*全|全集|合集|更新至\\s*\\d+|EP\\s*\\d+", RegexOption.IGNORE_CASE)

    /** "1:02:03"/"302:58" → 秒 */
    private fun durationSec(d: String): Long = runCatching {
        val parts = d.split(":").map { it.toLong() }
        when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0L
        }
    }.getOrDefault(0L)

    /** wbi 签名搜索：返回按「正片优先」排序的候选 bvid 列表（最多 3 个） */
    private fun searchCandidates(keyword: String): List<String> {
        val wbi = wbiKey() ?: return emptyList()
        val wts = System.currentTimeMillis() / 1000
        val params = sortedMapOf(
            "keyword" to keyword,
            "search_type" to "video",
            "wts" to wts.toString()
        )
        val query = params.entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" }
        val wRid = md5(query + wbi)
        val url = "https://api.bilibili.com/x/web-interface/search/type?$query&w_rid=$wRid"
        val body = httpText(url) ?: return emptyList()
        val obj = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        if (runCatching { obj.get("code")?.asInt }.getOrNull() != 0) return emptyList()
        val result = obj.getAsJsonObject("data")?.getAsJsonArray("result") ?: return emptyList()
        data class Cand(val bvid: String, val score: Int)
        val cands = mutableListOf<Cand>()
        for ((idx, el) in result.withIndex()) {
            val o = el.asJsonObject
            val bvid = o.get("bvid")?.takeIf { !it.isJsonNull }?.asString ?: continue
            if (bvid.isBlank()) continue
            val title = o.get("title")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (NOISE.containsMatchIn(title)) continue
            var score = -idx  // 原始顺序微调：同分时靠前的优先
            if (EP_MARK.containsMatchIn(title)) score += 40
            if (durationSec(o.get("duration")?.takeIf { !it.isJsonNull }?.asString.orEmpty()) >= 1200) score += 30
            cands.add(Cand(bvid, score))
        }
        return cands.sortedByDescending { it.score }.take(3).map { it.bvid }
    }

    /** view 接口拿 cid：优先匹配集数对应的分 P，否则第 1 P */
    private fun pickCid(bvid: String, episode: String): Long {
        val body = httpText("https://api.bilibili.com/x/web-interface/view?bvid=$bvid") ?: return 0
        val obj = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return 0
        if (runCatching { obj.get("code")?.asInt }.getOrNull() != 0) return 0
        val data = obj.getAsJsonObject("data") ?: return 0
        val epNum = Regex("\\d+").findAll(episode).lastOrNull()?.value?.toIntOrNull() ?: 0
        val pages = data.getAsJsonArray("pages")
        if (pages != null && pages.size() > 0 && epNum > 0) {
            // 分 P 的 part 名带集数（「第03集 xxx」/「03」）时优先命中
            for (el in pages) {
                val p = el.asJsonObject
                val part = p.get("part")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                val partNum = Regex("\\d+").findAll(part).firstOrNull()?.value?.toIntOrNull() ?: 0
                if (partNum == epNum) return runCatching { p.get("cid").asLong }.getOrDefault(0)
            }
        }
        // 兜底：data.cid 就是第 1 P
        return runCatching { data.get("cid").asLong }.getOrDefault(0)
    }

    /** 拉弹幕 XML：list.so 返回 zlib 压缩数据，需要手动解压 */
    private fun fetchDanmakuXml(cid: Long): String? {
        val bytes = httpBytes("https://api.bilibili.com/x/v1/dm/list.so?oid=$cid") ?: return null
        if (bytes.isEmpty()) return null
        // 已是明文 XML 直接用；否则按 zlib 解压（Inflater 默认即 zlib 格式）
        if (bytes[0] == '<'.code.toByte()) return bytes.toString(Charsets.UTF_8)
        return runCatching {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray().toString(Charsets.UTF_8)
        }.getOrNull()
    }

    /** wbi 密钥：nav 接口取 img/sub key，按混淆表重排取前 32 位 */
    private fun wbiKey(): String? {
        val body = httpText("https://api.bilibili.com/x/web-interface/nav") ?: return null
        val obj = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val img = runCatching {
            obj.getAsJsonObject("data").getAsJsonObject("wbi_img").get("img_url").asString
        }.getOrNull() ?: return null
        val sub = runCatching {
            obj.getAsJsonObject("data").getAsJsonObject("wbi_img").get("sub_url").asString
        }.getOrNull() ?: return null
        val imgKey = img.substringAfterLast('/').substringBefore('.')
        val subKey = sub.substringAfterLast('/').substringBefore('.')
        val raw = imgKey + subKey
        val sb = StringBuilder()
        for (i in 0 until 32) sb.append(raw[MIXIN_TAB[i]])
        return sb.toString()
    }

    /** 解析 Bilibili 格式弹幕 XML：<d p="时间,模式,字号,颜色,...">内容</d>，按出现时间排序 */
    internal fun parseDanmakuXml(xml: String): List<Pair<Long, String>> {
        return runCatching {
            Regex("<d\\s+p=\"([^\"]+)\"[^>]*>(.*?)</d>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .mapNotNull { m ->
                    val sec = m.groupValues[1].substringBefore(',').toDoubleOrNull() ?: return@mapNotNull null
                    val text = m.groupValues[2]
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&#39;", "'").replace("&amp;", "&")
                        .replace(Regex("<[^>]*>"), "")
                        .trim()
                    if (text.isEmpty()) null else ((sec * 1000).toLong() to text)
                }
                .sortedBy { it.first }
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** B 站风控 cookie（buvid3/buvid4）：搜索接口匿名请求没有它会被 412 拦截，
     *  表现就是永远「未找到匹配视频」。finger/spi 匿名可取，进程内缓存一份。 */
    @Volatile
    private var buvidCookie: String? = null

    private fun getBuvidCookie(): String? {
        buvidCookie?.let { return it }
        return runCatching {
            val conn = java.net.URL("https://api.bilibili.com/x/frontend/finger/spi").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val obj = com.google.gson.JsonParser.parseString(body).asJsonObject
            val b3 = obj.getAsJsonObject("data")?.get("b_3")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            val b4 = obj.getAsJsonObject("data")?.get("b_4")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            if (b3.isBlank()) return@runCatching null
            "buvid3=$b3; buvid4=$b4".also { buvidCookie = it }
        }.getOrNull()
    }

    private fun httpText(url: String): String? = httpBytes(url)?.toString(Charsets.UTF_8)

    private fun httpBytes(url: String): ByteArray? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Referer", "https://www.bilibili.com/")
        getBuvidCookie()?.let { conn.setRequestProperty("Cookie", it) }
        if (conn.responseCode != 200) return@runCatching null
        conn.inputStream.use { it.readBytes() }
    }.getOrNull()
}
