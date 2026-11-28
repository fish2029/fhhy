package com.fanhuatv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.fanhuatv.core.util.UrlRules
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 播放器引擎封装：单例持有 ExoPlayer。
 *
 * 关键设计——请求头自然匹配，不写死：
 * 1. 请求头完全来自数据源本身：蜘蛛/脚本通过 PlayHeaderStore 给什么就用什么，
 *    没给就裸发（ExoPlayer 默认 UA）。绝不自己编造浏览器 UA / Referer——
 *    编错一个头比少一个头更容易被 CDN 拒绝。
 * 2. 播放前探测真实响应内容（Content-Type / 前 512 字节），按内容选解析器：
 *    HLS 文本用 HlsMediaSource，其他交给 progressive extractors 自动嗅探。
 *    探测和播放用同一套请求头，保证结果一致。
 * 3. 失败重试也来自自然匹配：
 *    - 网络类错误 → 剔除全部自定义头裸发重试一次（蜘蛛的头被 CDN 拒绝时自救）；
 *    - 解析类错误 → 换解析器重试一次（探测误判时自救）。
 *    重试仍失败才把错误交给界面提示。
 */
@Singleton
class Media3Engine @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        /** 数据源没给 UA 时的兜底：蜘蛛生态通用 UA，比 ExoPlayer 默认 UA 更不容易被 CDN 拒 */
        private const val DEFAULT_UA = "okhttp-okgo/jeasonlzy"

        /** 补 UA：站点给的头原样保留，只在没有 User-Agent 时垫一个通用值 */
        private fun withDefaultUa(headers: Map<String, String>): Map<String, String> =
            if (headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) headers
            else headers + mapOf("User-Agent" to DEFAULT_UA)
    }

    var player: ExoPlayer? = null
        private set

    /** 最近播放地址 + 重试上下文（解析失败换解析器 / 网络错误裸发各重试一次） */
    private var lastUrl: String = ""
    private var lastKind: String = "progressive"
    private var hlsFallbackTried: Boolean = false
    private var networkFallbackTried: Boolean = false

    /** 探测专用轻量客户端（与播放同请求头，保证探测结果与实际请求一致） */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun get(): ExoPlayer = player ?: create().also { player = it }

    private fun create(): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        playWhenReady = true
        repeatMode = Player.REPEAT_MODE_OFF
        addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val parsingError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                val networkError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                // 403 等 HTTP 拒绝：CDN 可能只拒特定 UA 指纹，换默认 UA 裸发自救一次
                val badHttpStatus = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                when {
                    // 网络类错误 / 403：蜘蛛给的头可能被 CDN 拒绝，剔除全部自定义头裸发重试一次
                    (networkError || badHttpStatus) && !networkFallbackTried && lastUrl.isNotBlank() -> {
                        networkFallbackTried = true
                        rebuild(lastUrl, lastKind, headers = emptyMap())
                    }
                    // 解析类错误：探测可能误判，换解析器重试一次
                    parsingError && !hlsFallbackTried && lastUrl.isNotBlank() -> {
                        hlsFallbackTried = true
                        val retryKind = if (lastKind == "hls") "progressive" else "hls"
                        lastKind = retryKind
                        rebuild(lastUrl, retryKind, headers = PlayHeaderStore.get(lastUrl).orEmpty())
                    }
                }
            }
        })
    }

    /** 用指定解析器和请求头重建媒体源并重播（重试路径专用）；失败不抛出，静默放弃重试 */
    private fun rebuild(url: String, kind: String, headers: Map<String, String>) {
        runCatching {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(withDefaultUa(headers))
            val factory = DefaultDataSource.Factory(context, httpFactory)
            val src: MediaSource = if (kind == "hls") {
                HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(url))
            } else {
                DefaultMediaSourceFactory(factory).createMediaSource(MediaItem.fromUri(url))
            }
            val exo = get()
            exo.setMediaSource(src)
            exo.prepare()
            exo.playWhenReady = true
        }
    }

    /**
     * 播放。探测内容类型后选择正确解析器。
     * @return null 表示已开始播放；非 null 为错误信息（调用方展示给用户）
     */
    suspend fun play(url: String, speed: Float = 1f): String? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext "播放地址为空"
        // 请求头自然匹配：蜘蛛/脚本给什么用什么；没给 UA 时垫一个通用值
        val headers = withDefaultUa(PlayHeaderStore.get(url).orEmpty())
        // 万能正则拦下不带 :// 的裸串：那种地址交给 DefaultDataSource 会被当本地文件
        // 打开，报 ERROR_CODE_IO_FILE_NOT_FOUND（网盘故障同款假象）；带协议头的都放行。
        // 这里给出可读提示，顺带把原始形态打进 logcat 便于定位
        val trimmed = url.trim()
        // 万能正则：只要带 scheme:// 就丢给播放器（http/https/file/content/quark/thunder…都行），
        // 只有不带 :// 的裸串才拒绝——那种地址交给播放器只会报 IO_FILE_NOT_FOUND
        if (!UrlRules.isUniversalUrl(trimmed)) {
            android.util.Log.w("FanHuaTV", "Media3Engine 拒绝无协议头地址：url=${trimmed.take(160)}")
            return@withContext "该源返回的地址无法识别（${trimmed.take(30)}），请换线路（片源）或换站点"
        }
        android.util.Log.i("FanHuaTV", "Media3Engine.play：url=${trimmed.take(200)} headers=${headers.keys}")

        val kind = probeContentType(url, headers)
        val effective = when (kind) {
            "html" -> return@withContext "该源返回网页内容（防盗链或需网页解析），暂无法播放"
            "hls" -> "hls"
            "error" -> if (url.lowercase().contains(".m3u8")) "hls" else "progressive"
            else -> "progressive"
        }
        // 记录本次播放上下文，供 onPlayerError 的重试使用；换新地址时重置重试标记
        lastUrl = url
        lastKind = effective
        hlsFallbackTried = false
        networkFallbackTried = false

        // 站点可能返回带空格/中文/纯文本的怪地址，fromUri 会抛异常直接闪退，
        // 这里整体兜底：失败转成错误提示交给界面展示
        val buildResult = runCatching {
            withContext(Dispatchers.Main) {
                val exo = get()
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(20_000)
                if (headers.isNotEmpty()) httpFactory.setDefaultRequestProperties(withDefaultUa(headers))
                val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
                val mediaSource: MediaSource = if (effective == "hls") {
                    HlsMediaSource.Factory(dataSourceFactory).createMediaSource(MediaItem.fromUri(url))
                } else {
                    DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(url))
                }
                exo.setMediaSource(mediaSource)
                exo.setPlaybackSpeed(speed.coerceIn(0.25f, 4.0f))
                exo.prepare()
                exo.playWhenReady = true
            }
        }
        buildResult.exceptionOrNull()?.let { e ->
            return@withContext "该源地址无法播放（${e.message ?: "格式不支持"}），请换站点或换一集"
        }
        null
    }

    /**
     * 探测响应内容类型：
     * - hls        ：Content-Type 含 mpegurl，或响应体以 EXTM3U 开头
     * - progressive：video 类型、octet-stream 等
     * - html       ：防盗链/嗅探页，无法当视频播
     * - error      ：探测失败（超时等），交给 ExoPlayer 按默认流程报错
     */
    private fun probeContentType(url: String, headers: Map<String, String>): String = try {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        probeClient.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use "error"
            val ct = resp.header("Content-Type")?.lowercase() ?: ""
            if (ct.contains("html")) return@use "html"
            if (ct.contains("mpegurl")) return@use "hls"
            if (ct.contains("video") || ct.contains("octet-stream")) return@use "progressive"
            val head = resp.body?.byteStream()?.use { input ->
                val buf = ByteArray(512)
                val n = input.read(buf)
                if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
            }.orEmpty()
            if (head.trimStart().startsWith("#EXTM3U")) "hls" else "progressive"
        }
    } catch (e: Exception) {
        "error"
    }

    fun setSpeed(speed: Float) {
        get().setPlaybackSpeed(speed.coerceIn(0.25f, 4.0f))
    }

    fun release() {
        player?.release()
        player = null
    }
}
