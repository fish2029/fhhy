package com.fanhuatv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fanhuatv.core.CrashGuard
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class FanHuaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // 全局兜底：未捕获异常写日志并温和恢复，不再直接闪退
        CrashGuard.install(this)
        // Chaquopy Python 运行时提前初始化，供 PY 脚本模块使用
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }

    // 全局图片加载器：豆瓣图链必须带浏览器 UA + 豆瓣 Referer 才返回 200
    // （空 UA 会 403，无 Referer 会 418）；且豆瓣图床 img1~img9 节点路径通用，
    // 但部分节点在部分网络下不通（实测 img4/img5 直接超时），连不上就自动换节点重试。
    // 其他站点请求原样放行
    private val imageHttp = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            if (!request.url.host.endsWith("doubanio.com")) {
                return@addInterceptor chain.proceed(request)
            }
            val hosts = listOf(
                request.url.host,
                "img2.doubanio.com",
                "img1.doubanio.com",
                "img9.doubanio.com"
            ).distinct()
            var last: IOException? = null
            for (host in hosts) {
                val req = request.newBuilder()
                    .url(request.url.newBuilder().host(host).build())
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    .header("Referer", "https://m.douban.com/")
                    .build()
                try {
                    return@addInterceptor chain.proceed(req)
                } catch (e: IOException) {
                    last = e
                }
            }
            throw last ?: IOException("douban image unreachable")
        }
        .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient(imageHttp).build()
}
