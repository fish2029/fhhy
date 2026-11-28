package com.fanhuatv.jar

import android.content.Context
import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * 5266 Go 代理引擎（pvideo）启动器。
 *
 * 为什么宿主自己要拉起它：
 * 1. jar 内 `Init.startGoProxy()` 只在特定 targetSdk 条件下起线程，我们的壳 targetSdk=35，
 *    这条线根本不会走；
 * 2. 网盘（Pan/Danmu/ALLLive）全部请求 `http://127.0.0.1:5266`，没人监听时表现就是
 *    「配置中心 Go 离线」+ 网盘播放失败。
 * 所以这里按官方同款流程（下载 pvideo-<abi>.zip → 解压 → 校验文件 MD5 → 赋执行权限 →
 * `-port 5266`）在宿主侧主动拉起，且不设 targetSdk 门槛。
 *
 * 全部在独立守护线程里做，不阻塞搜索/播放等主链路。
 */
class GoProxyLauncher(
    private val context: Context,
    private val client: OkHttpClient
) {

    private val started = AtomicBoolean(false)

    /** 进程内只尝试一次；已尝试过（无论成败）直接返回，避免每站都拉一遍 */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            runCatching { start() }
                .onFailure { Log.w(TAG, "Go 引擎启动失败：${it.message}", it) }
        }, "fanhua-go-proxy").apply { isDaemon = true }.start()
    }

    /** 当前设备的引擎名：与 jar 内 Init 的 ABI 判定保持一致 */
    private val engineName: String by lazy {
        val abis = Build.SUPPORTED_ABIS.joinToString(",")
        when {
            abis.contains("x86_64") -> "pvideo-x86_64"
            abis.contains("x86") -> "pvideo-x86"
            abis.contains("arm64") -> "pvideo-arm64-v8a"
            else -> "pvideo-armeabi-v7a"
        }
    }

    /** 官方引擎包下载地址与解压后文件的期望 MD5（来自 fanhua.jar 内 Init 硬编码表） */
    private val engineUrl: String get() = "https://oss-v1.wangmeipo.cn/236/$engineName.zip"

    private val expectMd5: String
        get() = when (engineName) {
            "pvideo-x86_64" -> "c9bd60fa41d6868c2d3034db0a4a582e"
            "pvideo-x86" -> "ccadeedafb0cd3ad431bd0ede6ed5a04"
            "pvideo-armeabi-v7a" -> "37349b69934c6dc61489cb520b929794"
            else -> "ee2bcd5c1ff654903fd67c74f3561ef9"  // arm64-v8a
        }

    private fun start() {
        // 已经在线（jar 自己的 startGoProxy 或上次启动成功）：直接跳过，
        // 避免重复下载/启动与 jar 侧抢 5266 端口
        if (ping()) {
            Log.i(TAG, "🐹 Go 代理已在运行（127.0.0.1:5266），跳过启动")
            return
        }
        val dir = File(File(context.filesDir, "Pizazz"), "")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, engineName)

        if (!target.exists() || md5(target) != expectMd5) {
            Log.i(TAG, "Go 引擎缺失或校验不过，开始下载：$engineUrl")
            val zip = File(dir, "$engineName.zip")
            download(zip)
            unzip(zip, target)
            zip.delete()
            val got = md5(target)
            if (got != expectMd5) {
                Log.e(TAG, "Go 引擎校验失败：得到 $got 期望 ${expectMd5}，放弃启动")
                target.delete()
                return
            }
            Log.i(TAG, "Go 引擎下载并校验通过：${target.length()} 字节")
        } else {
            Log.i(TAG, "Go 引擎本地命中：${target.absolutePath}")
        }

        chmod755(target)
        // 清掉可能残留的旧进程（杀不掉也不影响后续启动）
        runCatching { Runtime.getRuntime().exec(arrayOf("pkill", "-f", engineName)).waitFor() }

        // 两种执行方式都试一遍：部分系统禁止直接 exec 应用私有目录里的二进制，
        // 用 /system/bin/linker 显式加载可绕过（官方 Init$2 同款思路）
        exec(target)
        if (!awaitReady(10)) {
            Log.w(TAG, "直接执行未见响应，改走 linker64/linker 加载")
            execViaLinker(target)
            awaitReady(30)
        }
    }

    private fun download(out: File) {
        val request = Request.Builder().url(engineUrl).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("空响应")
            FileOutputStream(out).use { body.byteStream().copyTo(it) }
        }
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("下载为空")
    }

    /** 官方 zip 内只有一个同名文件，直接取第一个条目 */
    private fun unzip(zip: File, target: File) {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            val entry = zis.nextEntry ?: throw IllegalStateException("zip 内无条目")
            FileOutputStream(target).use { zis.copyTo(it) }
            zis.closeEntry()
        }
    }

    /** 启动常驻进程；是否能用由随后的 ping 轮询判定 */
    private fun exec(target: File) {
        runCatching {
            Runtime.getRuntime().exec(arrayOf(target.absolutePath, "-port", "5266"))
            Unit
        }.onFailure { Log.w(TAG, "直接执行失败：${it.message}") }
    }

    private fun execViaLinker(target: File) {
        runCatching {
            val linker = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"
            Runtime.getRuntime().exec(arrayOf(linker, target.absolutePath, "-port", "5266"))
            Unit
        }.onFailure { Log.w(TAG, "linker 方式执行失败：${it.message}") }
    }

    private fun chmod755(f: File) {
        runCatching { f.setExecutable(true) }
        runCatching { Runtime.getRuntime().exec(arrayOf("chmod", "755", f.absolutePath)).waitFor() }
    }

    /** 轮询 5266 就绪，每次间隔 500ms，最多 attempts 次 */
    private fun awaitReady(attempts: Int): Boolean {
        repeat(attempts) {
            if (ping()) {
                Log.i(TAG, "🐹 Go 代理已在线（127.0.0.1:5266）")
                return true
            }
            Thread.sleep(500)
        }
        Log.w(TAG, "🐹 Go 代理 ${attempts / 2} 秒内未响应 /api/ping")
        return false
    }

    private fun ping(): Boolean = runCatching {
        val req = Request.Builder().url("http://127.0.0.1:5266/api/ping").get().build()
        client.newCall(req).execute().use { resp ->
            resp.isSuccessful && (resp.body?.string()?.contains("ok") == true)
        }
    }.getOrDefault(false)

    private fun md5(f: File): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        f.inputStream().use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    companion object {
        private const val TAG = "FanHuaTV"
    }
}
