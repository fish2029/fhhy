package com.fanhuatv.core.network

import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.BannerConfig
import com.fanhuatv.core.model.CategoryConfig
import com.fanhuatv.core.model.LiveChannel
import com.fanhuatv.core.model.VodItem
import com.google.gson.Gson
import com.google.gson.JsonParser
import javax.inject.Inject
import javax.inject.Singleton

/** 配置解析器：容错解析远程 JSON + m3u/txt 直播源，由 Kotlin 实现（Native 仅承担密钥/签名）。 */
@Singleton
class ConfigParser @Inject constructor(private val gson: Gson) {

    fun parseConfig(raw: String): Result<AppConfig> = runCatching {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "配置内容为空" }
        // 兼容 BOM 头与前后垃圾字符
        val jsonStart = trimmed.indexOfFirst { it == '{' || it == '[' }
        require(jsonStart >= 0) { "配置不是合法 JSON" }
        val jsonText = trimmed.substring(jsonStart)
        val obj = JsonParser.parseString(jsonText).asJsonObject

        // 鱼壳/TVBox 订阅格式 -> 转成 AppConfig（wallpaper/lives/sites 分别翻译）
        if (FishShellAdapter.isFishShell(obj)) {
            return Result.success(FishShellAdapter.toAppConfig(obj))
        }
        gson.fromJson(jsonText, AppConfig::class.java)
            ?: throw IllegalArgumentException("解析结果为空")
    }

    /** JSON null / 对象字段直接 asString 会抛异常，导致整条分类或影片被丢弃 */
    private fun safeStr(o: com.google.gson.JsonObject, key: String): String {
        val e = o.get(key) ?: return ""
        if (e.isJsonNull) return ""
        if (e.isJsonPrimitive) return runCatching { e.asString }.getOrDefault("")
        return ""
    }

    /**
     * 解析 dbapi（鱼壳内置 spider 数据源，如 csp_Douban）：
     * {"class":[{"type_id","type_name"}], "list":[{"vod_id","vod_name","vod_pic","vod_remarks"}]}
     * 分类名/影片全部来自接口，不内置任何分类。
     */
    fun parseDbapi(raw: String): Result<Pair<List<CategoryConfig>, List<VodItem>>> = runCatching {
        val obj = JsonParser.parseString(raw.trim()).asJsonObject
        val categories = obj.getAsJsonArray("class")?.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                CategoryConfig(key = safeStr(o, "type_id"), name = safeStr(o, "type_name"), siteKey = "dbapi")
            }.getOrNull()
        }.orEmpty().filter { it.key.isNotBlank() && it.name.isNotBlank() }

        val videos = obj.getAsJsonArray("list")?.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                val remarks = safeStr(o, "vod_remarks")
                VodItem(
                    id = safeStr(o, "vod_id"),
                    title = safeStr(o, "vod_name"),
                    cover = safeStr(o, "vod_pic"),
                    rating = remarks.removePrefix("★").trim(),
                    source = "dbapi"
                )
            }.getOrNull()
        }.orEmpty().filter { it.id.isNotBlank() && it.title.isNotBlank() }
        require(categories.isNotEmpty() || videos.isNotEmpty()) { "dbapi 返回无有效数据" }
        categories to videos
    }

    /**
     * 解析 MacCMS / 苹果CMS 的 ?ac=list 返回：
     * {"class":[{"type_id","type_name"}], "list":[{"vod_id","vod_name","vod_pic",...}]}
     * 转成 AppConfig 的 categories / homeItems，供没有 PY 脚本时兜底。
     */
    fun parseMacCmsList(raw: String): Result<Pair<List<CategoryConfig>, List<VodItem>>> = runCatching {
        val obj = JsonParser.parseString(raw.trim()).asJsonObject
        val categories = obj.getAsJsonArray("class")?.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                CategoryConfig(
                    key = safeStr(o, "type_id"),
                    name = safeStr(o, "type_name"),
                    siteKey = "maccms"
                )
            }.getOrNull()
        }.orEmpty()

        val videos = obj.getAsJsonArray("list")?.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                fun s(vararg k: String): String = k.firstNotNullOfOrNull { key ->
                    safeStr(o, key).takeIf { v -> v.isNotBlank() }
                }.orEmpty()
                VodItem(
                    id = s("vod_id", "id"),
                    title = s("vod_name", "name"),
                    cover = s("vod_pic", "pic"),
                    rating = s("vod_score", "score"),
                    year = s("vod_year", "year"),
                    category = s("type_name", "category"),
                    desc = s("vod_content", "vod_blurb"),
                    source = "maccms",
                    playUrl = s("vod_play_url")
                )
            }.getOrNull()
        }.orEmpty()
        require(categories.isNotEmpty() || videos.isNotEmpty()) { "ac=list 返回无有效数据" }
        categories to videos
    }

    /**
     * 解析 MacCMS XML 格式（TVBox type 0 站点，?ac=list 返回 <rss>）：
     * <rss><class><ty id="1">动作</ty></class>
     *     <list><video><id/><name/><pic/><note/><year/>...</video></list></rss>
     */
    fun parseMacCmsXml(raw: String): Result<Pair<List<CategoryConfig>, List<VodItem>>> = runCatching {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(raw.reader())

        val categories = mutableListOf<CategoryConfig>()
        val videos = mutableListOf<VodItem>()
        var tag = ""
        var inClass = false
        var inVideo = false
        var tyId = ""
        val cur = mutableMapOf<String, String>()

        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (event) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    tag = parser.name.lowercase()
                    when (tag) {
                        "class" -> inClass = true
                        "video" -> { inVideo = true; cur.clear() }
                        "ty" -> tyId = parser.getAttributeValue(null, "id").orEmpty()
                    }
                }
                org.xmlpull.v1.XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when {
                            tag == "ty" && inClass && tyId.isNotBlank() ->
                                categories.add(CategoryConfig(key = tyId, name = text, siteKey = "maccms"))
                            inVideo && tag in setOf(
                                "id", "tid", "name", "pic", "note", "year",
                                "score", "content", "director", "actor"
                            ) -> cur[tag] = text
                        }
                    }
                }
                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    when (parser.name.lowercase()) {
                        "class" -> inClass = false
                        "video" -> {
                            inVideo = false
                            val id = cur["id"].orEmpty()
                            val title = cur["name"].orEmpty()
                            if (id.isNotBlank() && title.isNotBlank()) {
                                videos.add(
                                    VodItem(
                                        id = id,
                                        title = title,
                                        cover = cur["pic"].orEmpty(),
                                        rating = cur["score"].orEmpty(),
                                        year = cur["year"].orEmpty(),
                                        category = cur["tid"].orEmpty(),
                                        desc = cur["content"].orEmpty(),
                                        director = cur["director"].orEmpty(),
                                        actor = cur["actor"].orEmpty(),
                                        source = "maccms"
                                    )
                                )
                            }
                        }
                    }
                    tag = ""
                }
            }
            event = parser.next()
        }
        require(categories.isNotEmpty() || videos.isNotEmpty()) { "XML 返回无有效数据" }
        categories to videos
    }

    fun parseLive(raw: String, format: String): List<LiveChannel> = when (format.lowercase()) {
        "m3u" -> parseM3u(raw)
        "txt" -> parseTxt(raw)
        else -> parseM3u(raw).ifEmpty { parseTxt(raw) }
    }

    /** m3u: #EXTINF:-1 tvg-name="央视1台" group-title="央视",央视综合\nhttp://... */
    private fun parseM3u(raw: String): List<LiveChannel> {
        val result = mutableListOf<LiveChannel>()
        var pendingName = ""
        var pendingGroup = "默认"
        var pendingLogo = ""
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            if (line.startsWith("#EXTINF")) {
                val name = regexAttr(line, "tvg-name")
                    ?: line.substringAfterLast(",").trim().ifBlank { null }
                    ?: "未命名"
                pendingName = name
                pendingGroup = regexAttr(line, "group-title") ?: "默认"
                pendingLogo = regexAttr(line, "tvg-logo") ?: ""
            } else if (line.startsWith("http://") || line.startsWith("https://")) {
                if (pendingName.isNotBlank()) {
                    result.add(LiveChannel(pendingName, line, pendingGroup, pendingLogo))
                    pendingName = ""; pendingGroup = "默认"; pendingLogo = ""
                }
            }
        }
        return result
    }

    /** txt: 央视综合,http://...  （逗号或 # 分隔） */
    private fun parseTxt(raw: String): List<LiveChannel> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val parts = line.split(Regex("[,#\\s]+"), limit = 2)
            if (parts.size == 2 && parts[1].startsWith("http")) {
                LiveChannel(parts[0], parts[1])
            } else null
        }

    private fun regexAttr(line: String, key: String): String? {
        val regex = Regex("$key=\"([^\"]*)\"")
        return regex.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }
}
