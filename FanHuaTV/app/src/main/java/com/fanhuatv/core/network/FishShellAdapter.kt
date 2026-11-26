package com.fanhuatv.core.network

import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.BannerConfig
import com.fanhuatv.core.model.LiveSource
import com.fanhuatv.core.model.SiteConfig
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 鱼壳 / TVBox 订阅格式适配器。
 *
 * 用户实际接口（如 https://www.612668.xyz/T4/config.json）是鱼壳格式，字段为
 * spider / wallpaper / sites / lives，跟本 App 的 AppConfig 契约不同：
 *   - sites[].api 是 .py 脚本 URL（type=3），由 MainRepository.syncPySites 拉取导入
 *   - sites[].ext 既可能是字符串，也可能是对象（如 {"host":..., "site":[...]}）
 *   - wallpaper 是分号分隔的豆瓣图 URL
 *   - lives 是直播源 m3u
 *
 * 关键：TVBox 的字段类型很随意，绝对不能直接用 asString/asBoolean/asInt ——
 * 对 JsonObject 调 asString 会抛 UnsupportedOperationException("JsonObject")，
 * 之前"全部接口加载失败：JsonObject"就是这么来的。
 */
object FishShellAdapter {

    /** 类型安全地读字符串：基本类型取文本，对象/数组转 JSON 字符串，绝不抛异常 */
    private fun str(o: JsonObject, key: String): String {
        val e: JsonElement = o.get(key) ?: return ""
        if (e.isJsonNull) return ""
        if (e.isJsonPrimitive) return runCatching { e.asString }.getOrDefault("")
        return runCatching { e.toString() }.getOrDefault("")
    }

    private fun bool(o: JsonObject, key: String, default: Boolean = false): Boolean {
        val e: JsonElement = o.get(key) ?: return default
        if (e.isJsonNull) return default
        if (e.isJsonPrimitive) {
            return runCatching { e.asBoolean }.getOrDefault(
                runCatching { e.asString == "1" || e.asString.equals("true", true) }.getOrDefault(default)
            )
        }
        return default
    }

    private fun int(o: JsonObject, key: String, default: Int = 0): Int {
        val e: JsonElement = o.get(key) ?: return default
        if (e.isJsonNull) return default
        if (e.isJsonPrimitive) return runCatching { e.asInt }.getOrDefault(default)
        return default
    }

    /** 是否鱼壳格式：有 wallpaper 或 lives，且 sites[].api 多为脚本 */
    fun isFishShell(obj: JsonObject): Boolean {
        if (!obj.has("sites")) return false
        if (obj.has("wallpaper") || obj.has("lives") || obj.has("spider")) return true
        val sites = obj.getAsJsonArray("sites") ?: return false
        return sites.take(5).any { el ->
            el.isJsonObject && str(el.asJsonObject, "api").let {
                it.startsWith("http") && (it.contains(".py") || it.contains(".js"))
            }
        }
    }

    /**
     * 把鱼壳 JSON 转成 AppConfig。
     * @param sourceUrl 订阅地址，用于拼接 .py 相对路径（本接口 api 均为绝对 URL，用不到）
     */
    fun toAppConfig(obj: JsonObject, sourceUrl: String = ""): AppConfig {
        // 0) spider 字段 -> jar 爬虫包地址（TVBox 约定：spider 指向含 CSP 类的 jar）
        val spiderUrl = str(obj, "spider")

        // 0.5) jx 解析接口 / 线路标记 / 广告黑名单（TVBox 标准字段，缺省为空）
        val parses = mutableListOf<com.fanhuatv.core.model.ParseConfig>()
        runCatching {
            obj.getAsJsonArray("parses")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val url = str(o, "url")
                if (url.startsWith("http")) {
                    parses.add(
                        com.fanhuatv.core.model.ParseConfig(
                            name = str(o, "name"),
                            type = int(o, "type", 0),
                            url = url
                        )
                    )
                }
            }
        }
        val flags = mutableListOf<com.fanhuatv.core.model.FlagConfig>()
        runCatching {
            obj.getAsJsonArray("flags")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val flag = str(o, "flag")
                if (flag.isNotBlank()) {
                    val exclusions = mutableListOf<String>()
                    runCatching {
                        o.getAsJsonArray("exclusions")?.forEach { ex ->
                            if (ex.isJsonPrimitive) exclusions.add(ex.asString)
                        }
                    }
                    flags.add(
                        com.fanhuatv.core.model.FlagConfig(
                            flag = flag,
                            url = str(o, "url"),
                            exclusions = exclusions
                        )
                    )
                }
            }
        }
        val ads = mutableListOf<String>()
        runCatching {
            obj.getAsJsonArray("ads")?.forEach { el ->
                if (el.isJsonPrimitive) ads.add(el.asString)
            }
        }

        // 1) 壁纸 -> Banner 轮播（取前 8 张）
        val banners = str(obj, "wallpaper").split(";")
            .map { it.trim() }.filter { it.startsWith("http") }
            .take(8).mapIndexed { i, url ->
                BannerConfig(title = "热播 $i", image = url, targetId = "wallpaper_$i")
            }

        // 2) lives -> 直播源
        val liveSources = mutableListOf<LiveSource>()
        runCatching {
            obj.getAsJsonArray("lives")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val url = str(o, "url")
                if (url.isNotBlank()) {
                    liveSources.add(
                        LiveSource(
                            key = str(o, "name").ifBlank { "live" },
                            name = str(o, "name").ifBlank { "直播" },
                            url = url,
                            format = "m3u",
                            enabled = true
                        )
                    )
                }
            }
        }

        // 3) sites -> 站点。带 http .py 地址的标记为 py 源（scriptId 先存 URL，导入时替换）
        val sites = mutableListOf<SiteConfig>()
        runCatching {
            obj.getAsJsonArray("sites")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val key = str(o, "key")
                val name = str(o, "name")
                val api = str(o, "api")
                if (key.isBlank()) return@forEach
                val isPyUrl = api.startsWith("http") && (api.contains(".py") || api.contains(".js"))
                // csp_ 开头的 api 是 jar 爬虫站点（type=3 spider 模式），标记为 jar 源
                val isJar = api.startsWith("csp_")
                // sites[].headers：对象或字符串都可能是请求头，统一转 Map
                val siteHeaders = mutableMapOf<String, String>()
                runCatching {
                    when (val h = o.get("headers")) {
                        is JsonObject -> h.entrySet().forEach { (k, v) ->
                            if (v.isJsonPrimitive) siteHeaders[k] = v.asString
                        }
                        else -> if (h != null && h.isJsonPrimitive) {
                            runCatching {
                                val ho = JsonParser.parseString(h.asString).asJsonObject
                                ho.entrySet().forEach { (k, v) ->
                                    if (v.isJsonPrimitive) siteHeaders[k] = v.asString
                                }
                            }
                        }
                    }
                }
                sites.add(
                    SiteConfig(
                        key = key,
                        name = name.ifBlank { key },
                        api = api,
                        searchable = bool(o, "searchable", true),
                        enabled = true,
                        source = when {
                            isPyUrl -> "py"
                            isJar -> "jar"
                            else -> "json"
                        },
                        scriptId = if (isPyUrl) api else "",   // 临时存 URL，导入后由 PyRuntime 回调替换
                        type = int(o, "type"),
                        ext = str(o, "ext"),                   // 对象类型转 JSON 字符串，不再抛异常
                        index = int(o, "index"),               // index=1 = 首页分类站点
                        headers = siteHeaders
                    )
                )
            }
        }

        return AppConfig(
            banners = banners,
            liveSources = liveSources,
            sites = sites,
            categories = emptyList(),
            homeItems = emptyList(),
            spiderUrl = spiderUrl,
            parses = parses,
            flags = flags,
            ads = ads
        )
    }

    /** TVBox 订阅里的一个 .py 站点：URL + 站点名 + ext（脚本 init 的扩展参数） */
    data class PySite(val url: String, val name: String, val ext: String)

    /**
     * 提取所有可拉取的 .py 站点。
     * TVBox 的约定：sites[].api 指向脚本地址，sites[].ext 是传给 spider.init(extend) 的参数，
     * 同一份脚本靠 ext 区分不同站点，所以 ext 必须跟着 URL 一起带过去。
     */
    fun pySites(obj: JsonObject): List<PySite> {
        val result = mutableListOf<PySite>()
        runCatching {
            obj.getAsJsonArray("sites")?.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val o = el.asJsonObject
                val api = str(o, "api")
                if (!api.startsWith("http") || !api.contains(".py")) return@forEach
                val name = str(o, "name").ifBlank {
                    api.substringAfterLast('/').removeSuffix(".py")
                }
                result.add(PySite(api, name, str(o, "ext")))
            }
        }
        return result.distinctBy { it.url }
    }

    /** 供 PyRuntime 使用：从站点里提取所有可拉取的 .py 脚本 URL */
    fun pyScriptUrls(obj: JsonObject): List<String> = pySites(obj).map { it.url }
}
