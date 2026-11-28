package com.fanhuatv.core.model

import com.google.gson.annotations.SerializedName

/**
 * 远程配置根结构 —— 壳子与内容分离的核心契约。
 * 字段全部给默认值 + @SerializedName，兼容不同站点字段名差异。
 */
data class AppConfig(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("sites") val sites: List<SiteConfig> = emptyList(),
    @SerializedName("categories") val categories: List<CategoryConfig> = emptyList(),
    @SerializedName("banners") val banners: List<BannerConfig> = emptyList(),
    @SerializedName("homeItems") val homeItems: List<VodItem> = emptyList(),
    @SerializedName("liveSources") val liveSources: List<LiveSource> = emptyList(),
    @SerializedName("spiderUrl") val spiderUrl: String = "",   // TVBox 订阅的 spider jar 地址
    // TVBox 订阅的 jx 解析接口：parse=1/jx=1 的片源优先走这些接口拿真实地址
    @SerializedName("parses") val parses: List<ParseConfig> = emptyList(),
    // 哪些播放线路（flag）必须走哪个解析接口，优先级高于 parses 全局列表
    @SerializedName("flags") val flags: List<FlagConfig> = emptyList(),
    // 广告域名黑名单：命中的站点整条过滤
    @SerializedName("ads") val ads: List<String> = emptyList()
)

/** TVBox parses[]：type 0=JSON 接口 1=JSONP 接口 2=网页播放页（走嗅探） */
data class ParseConfig(
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: Int = 0,
    @SerializedName("url") val url: String = ""
)

/** TVBox flags[]：指定线路强制走指定解析接口；exclusions 是该线路下不走解析的地址片段 */
data class FlagConfig(
    @SerializedName("flag") val flag: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("exclusions") val exclusions: List<String> = emptyList()
)

data class SiteConfig(
    @SerializedName("key") val key: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("api") val api: String = "",
    @SerializedName("searchable") val searchable: Boolean = true,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("source") val source: String = "json", // json | py
    @SerializedName("scriptId") val scriptId: String = "",   // PY 脚本来源时关联脚本 ID
    // TVBox 订阅里的字段：type 0=xml 1=json 3=spider；ext 是 spider 的 init 扩展参数
    @SerializedName("type") val type: Int = 0,
    @SerializedName("ext") val ext: String = "",
    // TVBox 约定：index=1 表示这是首页分类栏的站点
    @SerializedName("index") val index: Int = 0,
    // TVBox sites[].headers：该站点接口请求要带的请求头（防盗链 Referer/UA 等）
    @SerializedName("headers") val headers: Map<String, String> = emptyMap()
)

data class CategoryConfig(
    @SerializedName("key") val key: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("siteKey") val siteKey: String = ""
)

data class BannerConfig(
    @SerializedName("title") val title: String = "",
    @SerializedName("image") val image: String = "",
    @SerializedName("cover") val cover: String = "",
    @SerializedName("targetId") val targetId: String = "",
    @SerializedName("siteKey") val siteKey: String = "",
    @SerializedName("rating") val rating: String = ""
) {
    val coverUrl: String get() = image.ifBlank { cover }
}

/** 影片卡片（3×3 网格 / 搜索结果 / 详情共用） */
data class VodItem(
    @SerializedName("id") val id: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("cover") val cover: String = "",
    @SerializedName("pic") val pic: String = "",
    @SerializedName("rating") val rating: String = "",
    @SerializedName("score") val score: String = "",
    @SerializedName("year") val year: String = "",
    @SerializedName("category") val category: String = "",
    @SerializedName("siteKey") val siteKey: String = "",
    /** 站点中文名（订阅里的 sites[].name）：展示层优先用它，避免露出 csp_xxx/key */
    @SerializedName("siteName") val siteName: String = "",
    @SerializedName("source") val source: String = "json",
    @SerializedName("scriptId") val scriptId: String = "",
    @SerializedName("detailUrl") val detailUrl: String = "",
    @SerializedName("playUrl") val playUrl: String = "",
    /** TVBox vod_play_from：playerContent(flag, id, flags) 需要的播放源标识 */
    @SerializedName("playFrom") val playFrom: String = "",
    /** TVBox vod action：配置中心等条目的点击动作（扫码/输入/清除等）。
     *  非空时点击执行动作而非进详情播放，与 TVBox 语义一致 */
    @SerializedName("action") val action: String = "",
    @SerializedName("episodes") val episodes: List<Episode> = emptyList(),

    @SerializedName("desc") val desc: String = "",

    @SerializedName("director") val director: String = "",   // 导演（播放页信息区展示）

    @SerializedName("actor") val actor: String = ""          // 演员（播放页信息区展示）
) {
    /** 展示用属性：主字段为空时回退备选字段（data class 内定义，跨包免 import） */
    val displayTitle: String get() = title.ifBlank { name }
    val coverUrl: String get() = cover.ifBlank { pic }
    val displayRating: String get() = rating.ifBlank { score }
}

/** 单集 */
data class Episode(
    @SerializedName("name") val name: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("playUrl") val playUrl: String = "",
    /** 所属播放源（线路）名：jar 详情的 vod_play_from 与 vod_play_url 按 $$$ 一一对应，
     *  一部片子常有几条线路，有的线路整条失效，必须能按集切线路 */
    @SerializedName("playFrom") val playFrom: String = ""
) {
    /** 可播放地址：url 为空时回退 playUrl */
    val playableUrl: String get() = url.ifBlank { playUrl }
}

/** 直播源 */
data class LiveSource(
    @SerializedName("key") val key: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("format") val format: String = "m3u", // m3u | txt
    @SerializedName("template") val template: String = "",
    @SerializedName("enabled") val enabled: Boolean = true
)

data class LiveChannel(
    val name: String = "",
    val url: String = "",
    val group: String = "默认",
    val logo: String = ""
)

/** 配置源（可添加多个，自由切换线路） */
data class ConfigSource(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true
)

/** PY 脚本元数据 */
data class ScriptMeta(
    val id: String = "",
    val name: String = "",
    val version: String = "1.0",
    val siteName: String = "",
    val author: String = "",
    val path: String = "",
    val enabled: Boolean = true,
    val content: String = "",
    val importedAt: Long = System.currentTimeMillis()
)

/**
 * 展示用计算属性：主字段为空时自动回退到备选字段
 * （不同站点的影片字段名差异大，展示层统一走这几个入口）
 */
val VodItem.displayTitle: String
    get() = title.ifBlank { name }

val VodItem.coverUrl: String
    get() = cover.ifBlank { pic }

val VodItem.displayRating: String
    get() = rating.ifBlank { score }

/** 单集可播放地址：url 为空时回退 playUrl */
val Episode.playableUrl: String
    get() = url.ifBlank { playUrl }