package com.fanhuatv.core.model

/**
 * 智能分类：把站源按自身特征（key 前缀 / 名称）自动归到四个主分类。
 *
 * 判定优先级：
 * 1) 名称或 key 含「短剧 / 剧场」等 → 短剧
 * 2) 名称或 key 含「音乐 / 电台 / 听书」等 → 音乐
 * 3) PY 脚本来源（source=py、带 scriptId、key 以 py_ 开头）→ PY站点
 * 4) 其余（jar / csp_ / json 接口站点）→ JAR内站点
 */
object SmartSiteCategory {

    const val JAR = "JAR内站点"
    const val PY = "PY站点"
    const val MUSIC = "音乐"
    const val DRAMA = "短剧"

    /** 主分类固定顺序：JAR内站点强制排第一 */
    val ORDER: List<String> = listOf(JAR, PY, MUSIC, DRAMA)

    private val DRAMA_WORDS = listOf("短剧", "微短剧", "剧场", "迷你剧", "drama")
    private val MUSIC_WORDS = listOf("音乐", "电台", "听书", "music", "radio")

    /** 单个站点的分类结果 */
    fun of(site: SiteConfig): String {
        val key = site.key.lowercase()
        val name = site.name.lowercase()

        // 1) 内容特征最具体，优先按名称/关键词归类
        if (DRAMA_WORDS.any { name.contains(it) || key.contains(it) }) return DRAMA
        if (MUSIC_WORDS.any { name.contains(it) || key.contains(it) }) return MUSIC

        // 2) PY 脚本来源
        if (site.source.equals("py", ignoreCase = true)) return PY
        if (site.scriptId.isNotBlank()) return PY
        if (key == "py" || key.startsWith("py_")) return PY

        // 3) 其余一律视为 JAR 内站点
        return JAR
    }

    /** 按固定顺序分组；空分组不返回 */
    fun group(sites: List<SiteConfig>): List<Pair<String, List<SiteConfig>>> =
        ORDER.map { cat -> cat to sites.filter { of(it) == cat } }
            .filter { it.second.isNotEmpty() }
}
