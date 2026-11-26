package com.fanhuatv.core.model

/**
 * 内置默认「豆瓣热播」内容。
 *
 * 用途：未配置任何接口、或接口返回的 banners/homeItems 为空时，首页依然有轮播图和 3×3 卡片。
 *
 * 图片来源说明：豆瓣图片有防盗链（直连返回 418），这里统一走图片中转代理 img.612526.xyz，
 * 该代理会自动带 Referer: https://movie.douban.com/ 并做 7 天边缘缓存，已在真机网络验证可用。
 * 数据为豆瓣新片榜真实片名/海报/评分（随 App 版本内置，不会自动更新）。
 */
object DefaultConfig {

    /** 豆瓣图片中转代理：自动补 Referer 破解防盗链 */
    private const val PROXY = "https://img.612526.xyz/?url="

    /** 把豆瓣原图地址编码成代理地址（encodeURIComponent，Kotlin 手写避免额外依赖） */
    private fun douban(pid: String): String {
        val origin = "https://img3.doubanio.com/view/photo/s_ratio_poster/public/$pid"
        return PROXY + urlEncode(origin)
    }

    private fun urlEncode(s: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            when {
                (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code)
                        || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code -> append(c.toChar())
                else -> { append('%'); append(hex[c shr 4]); append(hex[c and 0x0F]) }
            }
        }
    }

    /** 豆瓣新片榜：片名 / 海报ID / 评分 */
    private val movies = listOf(
        Triple("可能的爱", "p2935488630.jpg", "8.6"),
        Triple("黑球", "p2936994837.jpg", "8.7"),
        Triple("另一个妈妈", "p2935858295.jpg", "5.8"),
        Triple("突如其来", "p2931445409.jpg", "7.7"),
        Triple("八仙！", "p2934074566.jpg", "8.2"),
        Triple("懦夫", "p2932617088.jpg", "7.5"),
        Triple("黄金时段", "p2934707795.jpg", "6.2"),
        Triple("一切如你所见", "p2936729175.jpg", "8.7"),
        Triple("逃出布迪秀", "p2934286660.jpg", "7.3"),
        Triple("哥斯拉-0.0", "p2933958732.jpg", "6.7")
    )

    private val images = movies.map { douban(it.second) }

    val banners: List<BannerConfig> = movies.take(5).mapIndexed { index, m ->
        BannerConfig(
            title = m.first,
            image = images[index],
            targetId = "default_$index",
            rating = m.third
        )
    }

    val homeItems: List<VodItem> = movies.take(9).mapIndexed { index, m ->
        VodItem(
            id = "default_$index",
            title = m.first,
            cover = images[index],
            rating = m.third,
            category = "豆瓣热播",
            source = "builtin",
            desc = "豆瓣热播精选 · ${m.first}。此内容为壳子内置展示数据，配置接口后将自动替换为你的片源。"
        )
    }

    val config: AppConfig = AppConfig(
        version = 1,
        banners = banners,
        homeItems = homeItems,
        sites = emptyList(),
        categories = emptyList()
    )

    /** 远程配置与默认内容合并：远程缺 banner/homeItems 时用内置豆瓣热播兜底 */
    fun merged(remote: AppConfig?): AppConfig {
        if (remote == null) return config
        return remote.copy(
            banners = remote.banners.ifEmpty { banners },
            homeItems = remote.homeItems.ifEmpty { homeItems }
        )
    }
}
