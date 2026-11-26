package com.fanhuatv.core.util

/**
 * 万能地址判定：只要形如 scheme://... 就认，不管什么协议——
 * http/https/file/content 以及各站奇葩协议头（quark、thunder、intent、p2p……）
 * 一律丢给播放器自己识别；只有完全不带 :// 的裸串才跳过。
 */
object UrlRules {

    /** RFC 3986 协议头形态：字母开头，可含数字 + - .，后跟 ://，再往后不限内容 */
    private val ANY_SCHEME = Regex(
        """^[A-Za-z][A-Za-z0-9+.\-]*://.*$""",
        RegexOption.DOT_MATCHES_ALL
    )

    /** 带任意协议头即视为可播放地址 */
    fun isUniversalUrl(url: String): Boolean {
        val t = url.trim()
        return t.isNotEmpty() && ANY_SCHEME.matches(t)
    }
}
