package com.fanhuatv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fanhuatv.core.CrashGuard
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient

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

    // 全局图片加载器：豆瓣图链必须带豆瓣 Referer 才返回 200（否则 418 拒绝），
    // 这里统一给 doubanio.com 的图片请求补来源头，其他站点请求原样放行
    private val imageHttp = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            if (request.url.host.endsWith("doubanio.com")) {
                chain.proceed(request.newBuilder().header("Referer", "https://m.douban.com/").build())
            } else {
                chain.proceed(request)
            }
        }
        .build()

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient(imageHttp).build()
}
