package com.fanhuatv.player

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 移植自 TVBox 手机版 Source.fetch() + CustomWebView/Sniffer 的精简版。
 *
 * 处理 jar Spider 常返回的三类地址：
 * 1. video:xxx          直接去掉协议头
 * 2. http.../x.strm     取文件第一行真实地址
 * 3. parse=1 / jx=1     用 WebView 加载页面，拦截其中真正播放的视频请求
 */
object PlaySourceResolver {

    private val SNIFFER = Regex(
        """https?://[^\s]{12,}\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\?[^\s]*)?|https?://.*?video/tos[^\s]*|rtmp:[^\s]+""",
        RegexOption.IGNORE_CASE
    )

    private val JS_EXTRACT = """
        (function(){
          var v=document.querySelector('video');
          if(v && v.src) return v.src;
          var h=document.documentElement.outerHTML;
          var m=h.match(/https?:\/\/[^\s"']+\.(m3u8|mp4|mkv|flv)(\?[^\s"']*)?/i);
          return m ? m[0] : '';
        })()
    """.trimIndent()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 疑似媒体接口：命中关键词且排除广告统计（通用自动匹配，不写死站点） */
    private val MEDIA_API_KEYWORDS = listOf("play", "video", "media", "stream", "parse", "api")
    private val MEDIA_API_BLOCKLIST = listOf("ad/", "ad?", "/ad", "track", "stat", "pixel", "beacon", "log")

    /** 严格校验的 Content-Type 白名单：后缀不明显的流，靠 MIME 双重确认 */
    private val VALID_CONTENT_TYPES = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl",
        "video/mp4", "video/x-flv", "video/mp2t", "video/ogg"
    )

    private fun isMediaApi(url: String): Boolean {
        val lower = url.lowercase()
        if (MEDIA_API_BLOCKLIST.any { lower.contains(it) }) return false
        return MEDIA_API_KEYWORDS.any { lower.contains(it) }
    }

    suspend fun fetch(
        context: Context,
        rawUrl: String,
        headers: Map<String, String>?,
        needParse: Boolean
    ): Pair<String, Map<String, String>?>? = withContext(Dispatchers.IO) {
        var url = rawUrl.trim()
        var requestHeaders = headers.orEmpty()

        when {
            url.startsWith("video:", true) -> url = url.substring(6)
            url.contains(".strm", true) -> fetchStrm(url, requestHeaders)?.let {
                url = it.first
                requestHeaders = it.second
            }
        }

        if (url.isBlank()) return@withContext null
        if (isVideoFormat(url)) return@withContext url to requestHeaders
        if (!needParse || !url.startsWith("http")) return@withContext url to requestHeaders

        sniffWithWebView(context, url, requestHeaders)
    }

    private fun fetchStrm(url: String, headers: Map<String, String>): Pair<String, Map<String, String>>? {
        return try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            http.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                val first = body.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
                    ?: return null
                first to headers
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun sniffWithWebView(
        context: Context,
        url: String,
        headers: Map<String, String>
    ): Pair<String, Map<String, String>>? = withContext(Dispatchers.Main) {
        val result = CompletableDeferred<Pair<String, Map<String, String>>?>()
        var webView: WebView? = null
        var finished = false
        // 疑似媒体接口候选：没抓到带后缀直链时，做 Content-Type 严格校验二次确认
        val pendingApis = LinkedHashMap<String, Map<String, String>>()

        fun cleanup() {
            if (finished) return
            finished = true
            webView?.let { view ->
                view.stopLoading()
                view.webViewClient = WebViewClient()
                view.destroy()
            }
            webView = null
        }

        try {
            webView = WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mediaPlaybackRequiresUserGesture = false
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
                if (!ua.isNullOrBlank()) settings.userAgentString = ua
                headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value?.let { cookie ->
                    runCatching { CookieManager.getInstance().setCookie(url, cookie) }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): WebResourceResponse? {
                        val candidate = request?.url?.toString().orEmpty()
                        val reqHeaders = request?.requestHeaders.orEmpty()
                        if (isVideoFormat(candidate)) {
                            val found = candidate to reqHeaders
                            post { if (result.isActive) { result.complete(found); cleanup() } }
                        } else if (candidate.startsWith("http") && isMediaApi(candidate)) {
                            post {
                                if (pendingApis.size < 8) pendingApis.putIfAbsent(candidate, reqHeaders)
                            }
                        }
                        return null
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        evaluateJavascript(JS_EXTRACT) { value ->
                            val extracted = value?.trim()?.trim('"').orEmpty()
                            if (extracted.isNotBlank() && isVideoFormat(extracted) && result.isActive) {
                                result.complete(extracted to headers)
                                cleanup()
                            }
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?
                    ) {
                        @Suppress("MissingPermission")
                        handler?.proceed()
                    }
                }

                loadUrl(url, headers)
            }

            val direct = withTimeoutOrNull(12_000) { result.await() }
            if (direct != null) return@withContext withCookies(url, direct)

            // 没抓到带后缀直链：对疑似媒体接口逐个探测，Content-Type 严格校验，
            // 命中白名单才采信——不注入假链接、不伪造字段
            if (pendingApis.isNotEmpty()) {
                val probed = withContext(Dispatchers.IO) { probePendingApis(pendingApis) }
                if (probed != null) return@withContext withCookies(url, probed)
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            cleanup()
        }
    }

    /** 把 WebView 会话里的 Cookie 并进请求头（站点给过 Cookie 头就不覆盖） */
    private fun withCookies(pageUrl: String, found: Pair<String, Map<String, String>>): Pair<String, Map<String, String>> {
        return runCatching {
            val hasCookie = found.second.keys.any { it.equals("Cookie", true) }
            if (hasCookie) return found
            val cookie = CookieManager.getInstance().getCookie(pageUrl) ?: return found
            found.copy(second = found.second + mapOf("Cookie" to cookie))
        }.getOrDefault(found)
    }

    /** 对疑似媒体接口做轻量探测：Range 1 字节，Content-Type 命中白名单才算真实流 */
    private fun probePendingApis(
        candidates: LinkedHashMap<String, Map<String, String>>
    ): Pair<String, Map<String, String>>? {
        for ((candidate, reqHeaders) in candidates) {
            runCatching {
                val builder = Request.Builder().url(candidate)
                    .header("Range", "bytes=0-0")
                reqHeaders.forEach { (k, v) ->
                    if (!k.equals("Range", true) && !k.equals("Host", true)) builder.header(k, v)
                }
                http.newCall(builder.build()).execute().use { resp ->
                    val ct = resp.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (resp.isSuccessful && ct in VALID_CONTENT_TYPES) {
                        return candidate to reqHeaders
                    }
                }
            }
        }
        return null
    }

    fun isVideoFormat(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.contains("url=http") || url.contains("v=http") || url.endsWith(".html", true)) return false
        return SNIFFER.containsMatchIn(url)
    }
}
