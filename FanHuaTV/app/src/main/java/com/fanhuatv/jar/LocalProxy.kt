package com.fanhuatv.jar

import android.util.Log
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 本地代理小服务器：监听 9978 端口，把 /proxy 请求转发给 jar 内的
 * com.github.catvod.spider.Proxy 静态方法（TVBox 约定）。
 *
 * 两类流量都从这里走：
 * - 弹幕/小数据：jar 生成 http://127.0.0.1:9978/proxy?do=appdanmu&...，一次性读完即可；
 * - 网盘视频流：网盘蜘蛛（夸克/UC/百度等）的播放地址是
 *   http://127.0.0.1:9978/proxy?do=pan&site=quark&shareId=..&fileId=..&fileToken=..，
 *   jar 内部按 Range 分块多线程拉流，必须把播放器的 Range 头原样传进去、
 *   把返回流分块转发——绝不能 readBytes() 全量读进内存（几 GB 的视频直接读死）。
 */
object LocalProxy {
    /** 本地代理端口：与 TVBox 约定一致，也是 jar 内 Proxy 探测的起点 */
    const val PORT = 9978
    private const val TAG = "LocalProxy"

    @Volatile
    private var started = false

    /** 最近一次成功加载的 jar ClassLoader，代理转发时用它找 jar 内 Proxy 类（兼底） */
    @Volatile
    var activeLoader: ClassLoader? = null

    /** 站点 key -> jar ClassLoader：多站点用不同 jar 时按请求里的 siteKey 精确路由，
     *  避免全局 activeLoader 被后加载的 jar 覆盖，导弹幕/网盘代理打到错误的蜘蛛上
     *  （对齐 TVBoxOS BaseLoader.proxy：params.siteKey → getSpider(siteKey).proxy） */
    private val loaders = java.util.concurrent.ConcurrentHashMap<String, ClassLoader>()

    fun registerLoader(siteKey: String, loader: ClassLoader?) {
        if (siteKey.isBlank() || loader == null) return
        loaders[siteKey] = loader
        activeLoader = loader
    }

    /** 幂等启动：绑定端口在调用线程完成，保证 jar 生成代理地址前服务已就绪 */
    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            runCatching {
                val server = ServerSocket(PORT)
                started = true
                Log.i(TAG, "本地代理已启动 :$PORT")
                Thread {
                    while (true) {
                        val socket = runCatching { server.accept() }.getOrNull() ?: continue
                        Thread { handle(socket) }.apply { isDaemon = true }.start()
                    }
                }.apply { isDaemon = true }.start()
            }.onFailure { Log.w(TAG, "本地代理启动失败：${it.message}") }
        }
    }

    private fun handle(socket: Socket) {
        var jarStream: InputStream? = null
        try {
            socket.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            // 请求行：GET /proxy?do=pan&site=quark&... HTTP/1.1
            val firstLine = reader.readLine() ?: return
            val query = firstLine.split(" ").getOrNull(1)?.substringAfter('?', "").orEmpty()
            val params = HashMap<String, String>()
            query.split('&').filter { it.contains('=') }.forEach {
                val i = it.indexOf('=')
                params[URLDecoder.decode(it.substring(0, i), "UTF-8")] =
                        URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            // 请求头并入参数（TVBox 约定）：网盘分块读取器靠 Range 头定位起播位置，
            // 不传 Range 的话 jar 默认从 0 开始拉全片，拖动进度条必然重新从头缓冲
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val k = line.substring(0, idx).trim()
                    val v = line.substring(idx + 1).trim()
                    if (k.isNotEmpty() && !params.containsKey(k)) params[k] = v
                }
                line = reader.readLine()
            }

            val result: Pair<Int, Pair<String, InputStream>> = when (params["do"]) {
                "ck" -> 200 to ("text/plain; charset=utf-8" to "ok".toByteArray(Charsets.UTF_8).inputStream())
                else -> serveByJar(params) ?: run {
                    Log.w(TAG, "代理转发失败：do=${params["do"]} site=${params["site"]}")
                    404 to ("text/plain; charset=utf-8" to ByteArray(0).inputStream())
                }
            }
            val (code, mimeAndBody) = result
            val (mime, body) = mimeAndBody
            jarStream = body
            val reason = when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                else -> "Not Found"
            }
            // 视频流不预读全量：边读边写分块转发，长度未知靠连接关闭收尾
            val out = socket.getOutputStream()
            val head = "HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray(Charsets.UTF_8))
            out.flush()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = jarStream.read(buf)
                if (n < 0) break
                if (n > 0) {
                    out.write(buf, 0, n)
                    out.flush()
                }
            }
            out.flush()
        } catch (e: Exception) {
            Log.w(TAG, "代理请求处理失败：${e.message}")
        } finally {
            runCatching { jarStream?.close() }
            runCatching { socket.close() }
        }
    }

    /** 反射调用 jar 内 Proxy.proxy(Map)，返回 [code, mime, 流]；jar 没有该类或调用抛异常时返回 null。
     *  优先按请求参数里的 siteKey 选对应站点的 jar（多 jar 共存时才不分错），没有则回退最近加载的。 */
    private fun serveByJar(params: Map<String, String>): Pair<Int, Pair<String, InputStream>>? {
        val loader = params["siteKey"]?.let { loaders[it] } ?: activeLoader ?: return null
        val result = runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
            val method = clazz.getMethod("proxy", Map::class.java)
            method.invoke(null, params) as? Array<Any>
        }.onFailure { Log.w(TAG, "jar 代理调用失败：${it.cause?.message ?: it.message}") }
            .getOrNull() ?: return null
        if (result.size < 3) return null
        val code = (result[0] as? Int) ?: 200
        val mime = (result[1] as? String) ?: "application/octet-stream"
        val stream = result[2] as? InputStream ?: return null
        return code to (mime to stream)
    }
}
