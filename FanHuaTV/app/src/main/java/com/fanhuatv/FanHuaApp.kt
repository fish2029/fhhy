package com.fanhuatv

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fanhuatv.core.CrashGuard
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FanHuaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 全局兜底：未捕获异常写日志并温和恢复，不再直接闪退
        CrashGuard.install(this)
        // Chaquopy Python 运行时提前初始化，供 PY 脚本模块使用
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}
