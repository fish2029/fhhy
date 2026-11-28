package com.fanhuatv.jar

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Jar 爬虫包加载器。
 *
 * 职责：
 * 1. 从远程 URL 下载 jar 包（内含 classes.dex）到应用私有目录
 * 2. 用 DexClassLoader 动态加载 jar，使其中的 CSP 爬虫类可被实例化
 * 3. 通过反射按类名创建 Spider 实例，缓存 ClassLoader 避免重复加载
 *
 * 类名映射规则：api "csp_XXX" -> com.github.catvod.spider.XXX
 * （与 TVBox 官方约定一致）
 */
class JarLoader(
    private val context: Context,
    private val client: OkHttpClient
) {

    private val lock = Any()
    private val classLoaders = ConcurrentHashMap<String, ClassLoader>()
    private val jarFiles = ConcurrentHashMap<String, File>()

    /** 本次会话已校验过远端的 jar 地址：每个地址每次启动只校验一次，不重复下载 */
    private val revalidated = ConcurrentHashMap.newKeySet<String>()

    /** 最近一次成功实例化 Spider 的 ClassLoader，供本地代理转发 jar 内 Proxy 调用（弹幕等） */
    var activeLoader: ClassLoader? = null
        private set

    /** 下载互斥锁：全站搜索时多个 jar 站点并发触发同一个 jar 的下载，
     *  不加锁会多个协程同时写同一个文件，写坏后 DexClassLoader 加载失败，全部 jar 站点失效 */
    private val downloadLock = Any()

    /** 校验专用轻量客户端：短超时，离线/慢网时不拖累首次进站 */
    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 下载 jar 到本地，返回本地文件路径。已下载的直接复用。 */
    suspend fun loadJar(jarUrl: String): File? {
        if (jarUrl.isBlank()) return null
        jarFiles[jarUrl]?.takeIf { it.exists() && it.length() > 0 }?.let { return it }

        val jarDir = File(context.filesDir, "jars")
        if (!jarDir.exists()) jarDir.mkdirs()
        val file = File(jarDir, "spider_" + md5(jarUrl) + ".jar")
        if (file.exists() && file.length() > 0) {
            jarFiles[jarUrl] = file
            // 缓存命中也要校验一次远端：订阅 jar 更新后旧缓存永远不换，
            // 手机上就一直跑旧 jar（咕噜修了也不生效的病根）。每个地址每次启动只校验一次，
            // 校验失败（离线/超时）静默用缓存，不卡站点
            if (revalidated.add(jarUrl)) revalidate(jarUrl, file)
            return file
        }

        return try {
            Log.i("FanHuaTV", "下载 jar 包：$jarUrl")
            // 下载前恢复目录可写（之前可能已设为只读）
            makeDirWritable(jarDir)
            // 串行化下载：先下到临时文件再原子改名，避免并发写坏同一个目标文件
            synchronized(downloadLock) {
                if (file.exists() && file.length() > 0) {
                    jarFiles[jarUrl] = file
                    return file
                }
                val tmp = File(jarDir, file.name + ".tmp")
                val request = Request.Builder().url(jarUrl).get().build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("FanHuaTV", "jar 下载失败 HTTP ${resp.code}")
                        return null
                    }
                    FileOutputStream(tmp).use { output ->
                        resp.body?.byteStream()?.copyTo(output)
                    }
                }
                if (tmp.exists() && tmp.length() > 0) {
                    if (file.exists()) file.delete()
                    if (!tmp.renameTo(file)) {
                        Log.e("FanHuaTV", "jar 临时文件改名失败：${tmp.name}")
                        tmp.delete()
                        return null
                    }
                    jarFiles[jarUrl] = file
                    // 关键：Android API 24+ 禁止从可写目录加载 dex，
                    // 必须把 jar 文件和所在目录都设为只读，DexClassLoader 才放行
                    makeJarReadOnly(file, jarDir)
                    Log.i("FanHuaTV", "jar 下载完成并设为只读：${file.length()} 字节")
                    file
                } else null
            }
        } catch (e: Exception) {
            Log.e("FanHuaTV", "jar 下载异常", e)
            null
        }
    }

    /**
     * 缓存校验：远端 jar 与本地缓存不一致时换新。
     * 只在缓存命中的首次调用走这里（同步、一次、10 秒超时）：
     * 下到新 jar 原子替换缓存文件并释放旧 ClassLoader，下次实例化 Spider 就是新 jar；
     * 内容一致或下载失败（离线/超时）都保留缓存，行为与旧版一致。
     */
    private fun revalidate(jarUrl: String, cached: File) {
        try {
            val tmp = File(cached.parentFile, cached.name + ".recheck")
            val request = Request.Builder().url(jarUrl).get().build()
            checkClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                FileOutputStream(tmp).use { output ->
                    resp.body?.byteStream()?.copyTo(output)
                }
            }
            if (!tmp.exists() || tmp.length() == 0L) {
                tmp.delete()
                return
            }
            val remoteMd5 = md5File(tmp)
            val localMd5 = md5File(cached)
            if (remoteMd5 == localMd5) {
                tmp.delete()
                Log.i("FanHuaTV", "jar 校验一致，继续用缓存：${cached.name} md5=$localMd5")
                return
            }
            // 远端有更新：替换缓存文件，旧 ClassLoader 作废，下次加载就是新 jar
            synchronized(downloadLock) {
                if (cached.exists()) cached.delete()
                if (!tmp.renameTo(cached)) {
                    Log.e("FanHuaTV", "jar 更新改名失败：${cached.name}")
                    tmp.delete()
                    return
                }
                makeJarReadOnly(cached, cached.parentFile)
                classLoaders.remove(jarUrl)
                Log.i("FanHuaTV", "jar 有更新，已换新：${cached.name} md5=$remoteMd5")
            }
        } catch (e: Exception) {
            Log.w("FanHuaTV", "jar 校验跳过，继续用缓存：${e.message}")
        }
    }

    private fun md5File(f: File): String {
        val input = f.inputStream().use { stream ->
            val digest = MessageDigest.getInstance("MD5")
            val buf = ByteArray(8192)
            while (true) {
                val n = stream.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
            digest.digest()
        }
        return input.joinToString("") { "%02x".format(it) }
    }

    /**
     * 设置 jar 文件和所在目录为只读。
     * Android API 24+ 的 DexClassLoader 会检查 dex/jar 源文件的父目录：
     * 目录可写（canWrite() == true）直接抛 "Writable dex file is not allowed"。
     * 应用对私有目录有完全所有权，chmod 自有目录无需任何特殊权限。
     */
    private fun makeJarReadOnly(jarFile: File, jarDir: File) {
        try {
            jarFile.setReadOnly()
            Runtime.getRuntime().exec(arrayOf("chmod", "555", jarDir.absolutePath)).waitFor()
            Log.i("FanHuaTV", "jar 已设为只读，目录权限已改为 555")
        } catch (e: Exception) {
            Log.w("FanHuaTV", "设置 jar 只读失败: ${e.message}")
        }
    }

    /** 恢复目录可写，用于 jar 更新时重新下载 */
    private fun makeDirWritable(jarDir: File) {
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "755", jarDir.absolutePath)).waitFor()
        } catch (e: Exception) {
            Log.w("FanHuaTV", "恢复目录可写失败: ${e.message}")
        }
    }

    /**
     * 获取指定 jar 的 ClassLoader（带缓存）。
     * parent 用应用类加载器，使爬虫类能解析 Spider 基类、okhttp3、org.json 等。
     */
    fun getClassLoader(jarUrl: String, jarFile: File): ClassLoader? {
        classLoaders[jarUrl]?.let { return it }
        synchronized(lock) {
            classLoaders[jarUrl]?.let { return it }
            val dexDir = context.getDir("jar_dex", Context.MODE_PRIVATE)
            // 与 TVBox JarLoader 一致：com.github.catvod.spider.* 必须由 jar 自己提供，
            // 不能落回宿主的 ClassLoader，否则 jar 内 Init/Proxy 类会被宿主空实现顶掉。
            val parent = object : ClassLoader(context.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("com.github.catvod.spider.")) {
                        throw ClassNotFoundException(name)
                    }
                    return super.loadClass(name, resolve)
                }
            }
            val loader = dalvik.system.DexClassLoader(
                jarFile.absolutePath,
                dexDir.absolutePath,
                dexDir.absolutePath,
                parent
            )
            invokeJarInit(loader)
            classLoaders[jarUrl] = loader
            Log.i("FanHuaTV", "DexClassLoader 已创建，jar=$jarUrl")
            return loader
        }
    }

    /**
     * 按站点 api 加载并实例化 Spider。
     * @param jarUrl   jar 包下载地址（config 的 spider 字段）
     * @param apiStr   站点 api，形如 "csp_AppRJ"
     * @return Spider 实例，失败返回 null
     */
    suspend fun createSpider(jarUrl: String, apiStr: String): Spider? {
        if (!apiStr.startsWith("csp_")) return null
        val className = apiStr.removePrefix("csp_")

        val jarFile = loadJar(jarUrl) ?: return null
        val classLoader = getClassLoader(jarUrl, jarFile) ?: return null

        return try {
            val fullName = "com.github.catvod.spider.$className"
            val clazz = classLoader.loadClass(fullName)
            val spider = clazz.getDeclaredConstructor().newInstance() as Spider
            activeLoader = classLoader
            Log.i("FanHuaTV", "Spider 实例化成功：$fullName")
            spider
        } catch (e: ClassNotFoundException) {
            Log.e("FanHuaTV", "Spider 类不存在：$className（jar 内无此类）", e)
            null
        } catch (e: Exception) {
            Log.e("FanHuaTV", "Spider 实例化失败：$className", e)
            null
        }
    }

    /**
     * TVBox 在加载 jar 后会调用 com.github.catvod.spider.Init.init(Context)。
     * 之前漏掉这一步，部分蜘蛛的网络客户端/缓存目录没初始化，搜索和播放会直接失败。
     */
    private fun invokeJarInit(loader: ClassLoader) {
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Init")
            val method: Method = clazz.getMethod("init", Context::class.java)
            method.invoke(null, context)
            Log.i("FanHuaTV", "jar Init.init(context) 调用成功")
        }.onFailure { e ->
            Log.w("FanHuaTV", "jar Init.init(context) 跳过：${e.message}")
        }
    }

    /** 释放指定 jar 的 ClassLoader（jar 更新时调用），同时恢复目录可写 */
    fun release(jarUrl: String) {
        classLoaders.remove(jarUrl)
        jarFiles.remove(jarUrl)
        val jarDir = File(context.filesDir, "jars")
        if (jarDir.exists()) makeDirWritable(jarDir)
    }

    companion object {
        private fun md5(s: String): String {
            return try {
                val md = MessageDigest.getInstance("MD5")
                val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                s.hashCode().toString(16)
            }
        }
    }
}
