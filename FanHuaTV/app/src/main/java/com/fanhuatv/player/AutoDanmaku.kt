package com.fanhuatv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地 5266 弹幕引擎（pvideo）通用匹配：所有源（py 源/网盘/站内站点）统一走这里。
 * 接口：GET /danmu/auto?name=<剧名>&episode=<集数>&format=xml → 直接返回 B 站格式弹幕 XML。
 * 与 jar 内 Danmu.getDanmuFrom5266 同款判定：响应里没有 <d ...>...</d> 就当没匹配到。
 * 引擎由 GoProxyLauncher 在后台拉起，这里只负责等它就绪后取数；取不到静默返回空列表，不影响播放。
 */
object AutoDanmaku {

    /** 引擎未就绪时最多等这么久（毫秒）：正常几秒内在线，等不到就放弃本次 */
    private const val WAIT_TOTAL_MS = 3000L

    /**
     * 按剧名 + 集数拉弹幕。
     * @param title 剧名（自动去清晰度/来源后缀等噪音）
     * @param episode 集数（从 1 开始；电影/单集传 1）
     */
    suspend fun fetch(title: String, episode: Int): List<Pair<Long, String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val name = BilibiliDanmaku.cleanTitle(title)
                if (name.isBlank()) return@runCatching emptyList()
                if (!awaitPing()) return@runCatching emptyList()
                val url = "http://127.0.0.1:5266/danmu/auto?name=" +
                    java.net.URLEncoder.encode(name, "UTF-8") +
                    "&episode=$episode&format=xml"
                val xml = httpText(url)
                if (xml.isNullOrBlank() || xml.indexOf("<d") < 0 || xml.indexOf("</d>") < 0) {
                    return@runCatching emptyList()
                }
                BilibiliDanmaku.parseDanmakuXml(xml)
            }.getOrDefault(emptyList())
        }

    /** 轮询 /api/ping，引擎就绪立即返回；超时返回 false（本次静默无弹幕） */
    private fun awaitPing(): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_TOTAL_MS
        while (System.currentTimeMillis() < deadline) {
            val body = httpText("http://127.0.0.1:5266/api/ping") ?: ""
            if (body.contains("ok")) return true
            Thread.sleep(400)
        }
        return false
    }

    private fun httpText(url: String): String? = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode != 200) return@runCatching null
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
