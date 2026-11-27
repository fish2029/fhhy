package com.fanhuatv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fanhuatv.core.CrashGuard
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class FanHuaApp : Application(), ImageLoaderFactory {

    companion object {
        /** 豆瓣图床兜底节点：路径通用，实测 img1/img2/img9 可用性最好 */
        private val DOUBAN_FALLBACK_HOSTS = listOf("img2.doubanio.com", "img1.doubanio.com", "img9.doubanio.com")
    }
    override fun onCreate() {
        super.onCreate()
        // 全局兜底：未捕获异常写日志并温和恢复，不再直接闪退
        CrashGuard.install(this)
        // Chaquopy Python 运行时提前初始化，供 PY 脚本模块使用
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    // 全局图片加载器：
    // - 豆瓣图统一走图床代理接口（DoubanBanners 拼好的 libraryx.cn 地址）；
    // - 代理连不上或报错时退回豆瓣直连：直连必须带 Referer（缺失会 418，任意域名均可），
    //   且 img1~img9 节点路径通用、部分节点在部分网络下不通，连不上自动换节点重试；
    // - 其他站点请求原样放行
    private val imageHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            if (host.endsWith("libraryx.cn")) {
                val origin = request.url.queryParameter("url")
                if (origin.isNullOrBlank()) return@addInterceptor chain.proceed(request)
                return@addInterceptor try {
                    val resp = chain.proceed(request)
                    if (resp.isSuccessful) resp
                    else {
                        resp.close()
                        proceedDouban(chain, request.newBuilder().url(origin).build())
                    }
                } catch (e: IOException) {
                    proceedDouban(chain, request.newBuilder().url(origin).build())
                }
            }
            if (host.endsWith("doubanio.com")) {
                return@addInterceptor proceedDouban(chain, request)
            }
            chain.proceed(request)
        }
        .build()

    /** 豆瓣直连：补 Referer，节点连不上自动换节点重试 */
    private fun proceedDouban(chain: Interceptor.Chain, request: Request): Response {
        val hosts = (listOf(request.url.host) + DOUBAN_FALLBACK_HOSTS).distinct()
        var last: IOException? = null
        for (host in hosts) {
            val req = request.newBuilder()
                .url(request.url.newBuilder().host(host).build())
                .header("Referer", "https://m.douban.com/")
                .build()
            try {
                return chain.proceed(req)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("douban image unreachable")
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient(imageHttp).build()
}
