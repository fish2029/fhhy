package com.fanhuatv.player

import java.util.concurrent.ConcurrentHashMap

/**
 * 播放请求头中转站。
 *
 * jar/PY spider 的 playerContent 会返回 header 字段（JSON 字符串，含 Referer/UA/Cookie 等），
 * 指示该播放地址需要的请求头。解析出地址时把 header 存到这里，Media3Engine.play
 * 播放时按 URL 取出应用——探测与播放使用同一套请求头，保证防盗链源可正常访问。
 *
 * 键为播放地址；同地址重复播放自动覆盖；App 退出随之释放（进程内存，不落盘）。
 */
object PlayHeaderStore {

    private val map = ConcurrentHashMap<String, Map<String, String>>()

    fun put(url: String, headers: Map<String, String>) {
        if (url.isBlank() || headers.isEmpty()) return
        map[url] = headers
        // 防止长期膨胀：超过 32 条清掉最早的
        if (map.size > 32) {
            map.keys.take(map.size - 32).forEach { map.remove(it) }
        }
    }

    fun get(url: String): Map<String, String>? = map[url]

    fun clear(url: String) {
        map.remove(url)
    }
}
