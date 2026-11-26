package com.fanhuatv.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.fanhuatv.core.model.CategoryConfig
import com.fanhuatv.core.model.ScriptMeta
import com.fanhuatv.core.model.SiteConfig
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.storage.SettingsDataStore
import com.fanhuatv.core.storage.dao.SavedScriptDao
import com.fanhuatv.core.storage.entity.SavedScriptEntity
import com.fanhuatv.core.network.ApiService
import com.fanhuatv.core.util.Resource
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chaquopy Python 运行时 + PY 脚本管理核心。
 * 职责：初始化 Python 环境、导入/解析脚本、沙箱执行、注册为数据源。
 */
@Singleton
class PyRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scriptDao: SavedScriptDao,
    private val settings: SettingsDataStore,
    private val gson: Gson,
    private val api: ApiService
) {
    private val py: Python by lazy {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        Python.getInstance()
    }
    private val module by lazy { py.getModule("fanhua_runtime") }

    /** Python 调用线程池：CachedThreadPool——每个新调用立即获得新线程。
     *  之前用固定线程池：个别脚本 DNS/网络挂死（requests 的 timeout 不覆盖 DNS 解析），
     *  挂死线程永久占用池位，多次搜索后池被占满，表现为"搜索越来越慢"。
     *  CachedThreadPool 下挂死线程只占内存不占调度位，新调用永不排队。 */
    private val pyExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "python-runtime").apply { isDaemon = false }
    }

    val scripts: Flow<List<SavedScriptEntity>> = scriptDao.observeAll()

    private val scriptsDir: File
        get() = File(context.filesDir, "py_scripts").apply { mkdirs() }

    suspend fun dependencies(): List<String> {
        val json = callIo(5, "check_dependencies") ?: return emptyList()
        return runCatching {
            gson.fromJson(json, Array<String>::class.java).toList()
        }.getOrDefault(emptyList())
    }

    /** 导入脚本：解析元数据 → 落盘 → 入库 → 自动注册数据源
     * 支持两类：① 鱼壳/TVBox 的 class Spider（homeContent/searchContent/playerContent）
     *           ② 原生约定的 def config/search/play 顶级函数
     */
    suspend fun importScript(name: String, content: String, ext: String = ""): Resource<ScriptMeta> {
        // 鱼壳判定必须和 Python 端 is_fish_script 一致，覆盖大小写两套写法，
        // 否则小写脚本会被误判成"不符合约定"而拒收
        val isFish = content.contains("class Spider") ||
                content.contains("class spider") ||
                content.contains("def homeContent") ||
                content.contains("def homecontent") ||
                content.contains("def searchContent") ||
                content.contains("def searchcontent") ||
                content.contains("def playerContent") ||
                content.contains("def playercontent") ||
                content.contains("from base.spider") ||
                content.contains("from base import spider")
        val isNative = content.contains("def config") &&
                content.contains("def search") &&
                content.contains("def play")
        if (!isFish && !isNative) {
            return Resource.Error("脚本不符合约定：需要鱼壳 class Spider 或 def config/search/play 函数")
        }
        val typeTag = if (isFish) " [鱼壳]" else ""
        val id = name.replace(Regex("[^A-Za-z0-9_]"), "_") + "_" + System.currentTimeMillis()
        val file = File(scriptsDir, "$id.py")
        return try {
            // 元数据解析必须包在 try 内：Python 端抛异常时也要照常落盘入库，
            // 否则脚本文件写好了但 DB 没记录，列表永远不显示。
            val metaJson = runCatching {
                callIo(15, "inspect_script", content)
            }.getOrNull()
            val meta = metaJson?.let {
                runCatching { gson.fromJson(it, ScriptMeta::class.java) }.getOrNull()
            } ?: ScriptMeta()

            file.writeText(content, Charsets.UTF_8)
            // 元信息优先用脚本自带的，取不到一律用导入时的文件名兜底（最可靠）
            val displayName = when {
                meta.name.isNotBlank() && meta.name != "未命名脚本" -> meta.name
                else -> name
            }
            val siteName = meta.siteName.takeIf { it.isNotBlank() } ?: name
            val saved = SavedScriptEntity(
                id = id,
                name = displayName + typeTag,
                version = meta.version,
                siteName = siteName,
                author = meta.author,
                filePath = file.absolutePath
            )
            scriptDao.insert(saved)
            if (ext.isNotBlank()) settings.putPySite("", id, ext)
            Resource.Success(meta.copy(id = id, path = file.absolutePath, content = content))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "导入失败")
        }
    }

    /** 从 URL 拉取 .py 脚本并导入（TVBox 订阅 sites[].api 用）；ext 是站点扩展参数 */
    suspend fun importFromUrl(url: String, siteName: String = "", ext: String = ""): Resource<ScriptMeta> {
        val content = try {
            val resp = api.fetchRaw(url)
            if (!resp.isSuccessful) return Resource.Error("HTTP ${resp.code()}")
            resp.body()?.string().orEmpty()
        } catch (e: Exception) {
            return Resource.Error(e.message ?: "拉取脚本失败")
        }
        if (content.isBlank()) return Resource.Error("脚本内容为空")
        val name = siteName.ifBlank {
            url.substringAfterLast('/').removeSuffix(".py").ifBlank { "script" }
        }
        val result = importScript(name, content, ext)
        if (result is Resource.Success) {
            // 记录 url -> 脚本 ID，避免每次刷新重复下载；同时绑定 ext
            settings.putPySite(url, result.data.id, ext)
        }
        return result
    }

    suspend fun reimport(id: String, content: String): Resource<ScriptMeta> {
        val existing = scriptDao.find(id) ?: return Resource.Error("脚本不存在")
        return try {
            File(existing.filePath).writeText(content, Charsets.UTF_8)
            scriptDao.insert(existing.copy(importedAt = System.currentTimeMillis()))
            if (content.isNotBlank()) contentCache[id] = content else contentCache.remove(id)
            categoryCache.clear()
            Resource.Success(ScriptMeta(id = id, path = existing.filePath, content = content))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "重新导入失败")
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = scriptDao.setEnabled(id, enabled)
    suspend fun delete(id: String) {
        scriptDao.find(id)?.let { runCatching { File(it.filePath).delete() } }
        scriptDao.delete(id)
        contentCache.remove(id)
        categoryCache.clear()
    }

    /** 脚本内容内存缓存：分类/搜索/详情每次调用都要读盘 + 跨 JNI 传整个源码，
     *  脚本几十 KB 时来回切分类明显变慢；命中内存后只剩 JNI 一次传串 */
    private val contentCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 读取已启用脚本内容 */
    suspend fun scriptContent(id: String): String = withContext(Dispatchers.IO) {
        contentCache[id]?.let { return@withContext it }
        val content = scriptDao.find(id)?.let { runCatching { File(it.filePath).readText(Charsets.UTF_8) }.getOrNull() } ?: ""
        if (content.isNotBlank()) contentCache[id] = content
        content
    }

    /** 所有启用的脚本提供的站点（合并进 AppConfig） */
    suspend fun enabledScriptSites(): List<SiteConfig> {
        val list = scriptDao.observeAll().first().filter { it.enabled }
        return list.mapNotNull { entity ->
            val content = runCatching { File(entity.filePath).readText(Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            SiteConfig(
                key = "py_${entity.id}",
                name = entity.siteName.ifBlank { entity.name },
                api = "",
                source = "py",
                scriptId = entity.id
            )
        }
    }

    /** 首页数据：分类栏 + 推荐影片（鱼壳走 homeContent/homeVideoContent/categoryContent） */
    suspend fun home(scriptId: String): Resource<Pair<List<CategoryConfig>, List<VodItem>>> {
        val content = scriptContent(scriptId)
        if (content.isBlank()) return Resource.Error("脚本内容为空")
        val result = callIo(30, "home", content, paramJson(mapOf("ext" to settings.pyExtOf(scriptId))))
            ?: return Resource.Error(lastErrorMessage.ifBlank { "Python 调用失败" })
        return try {
            val obj = JsonParser.parseString(result).asJsonObject
            val err = safeStr(obj, "error")
            if (err.isNotBlank() && err != "None") {
                lastErrorMessage = err + traceTail(obj)
                return Resource.Error("脚本首页错误：$err")
            }
            val r = runCatching { obj.getAsJsonObject("result") }.getOrNull()
                ?: return Resource.Error("无首页数据")
            val classes = r.getAsJsonArray("classes")?.mapNotNull { el ->
                runCatching {
                    val o = el.asJsonObject
                    val key = safeStr(o, "type_id")
                    val name = safeStr(o, "type_name")
                    CategoryConfig(
                        key = key.ifBlank { name },   // class 没带 type_id 时用名字做 tid，避免所有分类 tid 都是空
                        name = name,
                        siteKey = "py_$scriptId"
                    )
                }.getOrNull()
            }.orEmpty()
            val videos = r.getAsJsonArray("videos")?.mapNotNull { el ->
                runCatching { gson.fromJson(el, VodItem::class.java) }.getOrNull()
            }.orEmpty()
            Resource.Success(classes to videos)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "首页数据解析失败")
        }
    }

    /** 分类结果 TTL 缓存：来回切分类标签时 90 秒内直接命中，不再等脚本重跑网络请求。
     *  只缓存非空 Success；超容量淘汰最早条目，脚本重导入/删除时整体失效 */
    private val categoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Resource<List<VodItem>>>>()

    /** 分类页影片列表 */
    suspend fun category(scriptId: String, tid: String, pg: String = "1"): Resource<List<VodItem>> {
        val key = "$scriptId|$tid|$pg"
        categoryCache[key]?.let { (at, v) ->
            if (System.currentTimeMillis() - at < 90_000) return v
            categoryCache.remove(key)
        }
        val content = scriptContent(scriptId)
        if (content.isBlank()) return Resource.Error("脚本内容为空")
        val param = paramJson(
            mapOf("tid" to tid, "pg" to pg, "ext" to settings.pyExtOf(scriptId))
        )
        val result = parseVodResult(callIo(25, "category", content, param))
        if (result is Resource.Success && result.data.isNotEmpty()) {
            if (categoryCache.size >= 32) categoryCache.remove(categoryCache.keys.firstOrNull())
            categoryCache[key] = System.currentTimeMillis() to result
        }
        return result
    }

    /** 用 gson 生成 JSON 参数，避免手拼字符串被引号/反斜杠打断 */
    private fun paramJson(map: Map<String, String>): String = gson.toJson(map)

    /** PY 脚本搜索 */
    suspend fun search(scriptId: String, keyword: String): Resource<List<VodItem>> {
        val content = scriptContent(scriptId)
        if (content.isBlank()) return Resource.Error("脚本内容为空")
        val param = paramJson(mapOf("keyword" to keyword, "ext" to settings.pyExtOf(scriptId)))
        // 搜索超时收紧到 15s：慢脚本早点放弃释放线程，不拖累整体出结果速度
        val result = callIo(15, "search", content, param)
        return parseVodResult(result)
    }

    /** PY 脚本详情：鱼壳走 detailContent 拿剧集列表 */
    suspend fun detail(scriptId: String, vodId: String): Resource<VodItem> {
        val content = scriptContent(scriptId)
        if (content.isBlank()) return Resource.Error("脚本内容为空")
        val param = paramJson(mapOf("id" to vodId, "ext" to settings.pyExtOf(scriptId)))
        val result = callIo(25, "detail", content, param)
            ?: return Resource.Error(lastErrorMessage.ifBlank { "Python 调用失败" })
        return try {
            val obj = JsonParser.parseString(result).asJsonObject
            val error = safeStr(obj, "error")
            if (error.isNotBlank() && error != "None") {
                lastErrorMessage = error + traceTail(obj)
                return Resource.Error("脚本错误：$error")
            }
            val el = obj.get("result") ?: return Resource.Error("无详情数据")
            val item = runCatching { gson.fromJson(el, VodItem::class.java) }.getOrNull()
                ?: return Resource.Error("详情解析失败")
            Resource.Success(item)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "详情加载失败")
        }
    }

    /** PY 脚本解析真实播放地址：parse=1/jx=1 的网页嗅探地址无能力处理，直接报错（避免黑屏） */
    suspend fun resolvePlay(scriptId: String, playUrl: String, title: String = ""): Resource<String> {
        val content = scriptContent(scriptId)
        if (content.isBlank()) return Resource.Error("脚本内容为空")
        val param = paramJson(mapOf("playUrl" to playUrl, "ext" to settings.pyExtOf(scriptId), "title" to title))
        val result = callIo(30, "parse_play", content, param)
        val obj = result?.let {
            runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull()
        } ?: return Resource.Error(lastErrorMessage.ifBlank { "脚本解析播放地址失败" })

        val err = safeStr(obj, "error")
        if (err.isNotBlank() && err != "None" && err != "no_url") {
            lastErrorMessage = err + traceTail(obj)
            return Resource.Error("脚本错误：$err")
        }
        val url = safeStr(obj, "result")
        if (url.isBlank()) return Resource.Error("脚本未返回播放地址")

        // parse=1/jx=1 的网页嗅探地址无能力处理：直接报错（避免黑屏），与原版行为一致
        val parse = runCatching { obj.get("parse")?.asInt ?: 0 }.getOrDefault(0)
        val jx = runCatching { obj.get("jx")?.asInt ?: 0 }.getOrDefault(0)
        val isDirect = looksDirectUrl(url)
        if ((parse == 1 || jx == 1) && !isDirect) {
            return Resource.Error("该源返回网页嗅探地址（parse=$parse），暂不支持直接播放")
        }
        if (!isDirect && (url.endsWith(".html") || url.endsWith(".htm"))) {
            return Resource.Error("该源返回网页地址，暂不支持直接播放")
        }
        // spider 指定的请求头（header 字段）：存入中转站供播放器按 URL 取用
        val headerStr = runCatching {
            val h = obj.get("header")
            when {
                h == null || h.isJsonNull -> ""
                h.isJsonObject -> h.toString()
                else -> h.asString
            }
        }.getOrDefault("")
        if (headerStr.isNotBlank()) {
            runCatching {
                val hObj = JsonParser.parseString(headerStr).asJsonObject
                val map = mutableMapOf<String, String>()
                hObj.entrySet().forEach { (k, v) ->
                    if (!v.isJsonNull) map[k] = v.asString
                }
                if (map.isNotEmpty()) com.fanhuatv.player.PlayHeaderStore.put(url, map)
            }
        }
        // 音乐类脚本的歌手海报/歌词：与 jar 源同路，写入播放总线供播放页渲染
        fun scriptField(vararg names: String): String = names.firstNotNullOfOrNull { n ->
            val v = obj.get(n)
            if (v == null || v.isJsonNull) null else runCatching { v.asString }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        } ?: ""
        scriptField("artwork", "cover").takeIf { it.isNotBlank() }?.let {
            com.fanhuatv.player.LivePlayBus.pendingArtwork = it
        }
        scriptField("lyric", "lyrics").takeIf { it.isNotBlank() }?.let {
            com.fanhuatv.player.LivePlayBus.pendingLyric = it
        }
        return Resource.Success(url)
    }

    /** 简易直链判断：m3u8/mp4/flv 等常见视频后缀，含音乐源音频直链 */
    private fun looksDirectUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".m3u") ||
                l.contains(".flv") || l.contains(".mkv") || l.contains(".ts") ||
                l.contains(".mp3") || l.contains(".flac") || l.contains(".m4a") ||
                l.contains(".aac") || l.contains(".wav") || l.contains(".ogg")
    }

    /** 获取脚本运行日志（含 Python traceback 尾部，能定位到具体行） */
    fun lastError(): String = lastErrorMessage

    /** 诊断单个脚本：类名 / 实现了哪些方法 / 首页能否跑通，失败带 traceback */
    suspend fun probe(scriptId: String): String {
        val content = scriptContent(scriptId)
        if (content.isBlank()) return "脚本内容为空或文件已丢失"
        val json = callIo(30, "probe", content)
            ?: return lastErrorMessage.ifBlank { "Python 调用失败" }
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            buildString {
                appendLine("类名：${safeStr(o, "class").ifBlank { "未识别" }}")
                appendLine("已实现方法：${o.getAsJsonArray("methods")?.joinToString(", ") { it.asString }.orEmpty().ifBlank { "无" }}")
                appendLine("init：${safeStr(o, "init")}")
                appendLine("首页：${safeStr(o, "home")}")
                val trace = safeStr(o, "trace")
                if (trace.isNotBlank()) {
                    appendLine()
                    appendLine("---- traceback ----")
                    append(trace.takeLast(1500))
                }
            }
        } catch (e: Exception) {
            json
        }
    }

    private var lastErrorMessage: String = ""

    /** 脚本返回的字段可能是 JSON null，直接 asString 会抛 UnsupportedOperationException: JsonNull */
    private fun safeStr(obj: JsonObject, key: String): String {
        val e = obj.get(key) ?: return ""
        if (e.isJsonNull) return ""
        if (e.isJsonPrimitive) return runCatching { e.asString }.getOrDefault("")
        return ""
    }

    /** 从脚本返回里取出 traceback 尾部（最后几行是出错位置），拼到错误信息后面 */
    private fun traceTail(obj: com.google.gson.JsonObject): String {
        val trace = runCatching { safeStr(obj, "trace") }.getOrDefault("")
        if (trace.isBlank()) return ""
        val lines = trace.trim().lines().filter { it.isNotBlank() }
        return "\n" + lines.takeLast(3).joinToString("\n")
    }

    private fun parseVodResult(json: String?): Resource<List<VodItem>> {
        if (json.isNullOrBlank()) return Resource.Error(lastErrorMessage.ifBlank { "脚本无返回" })
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val error = safeStr(obj, "error")
            if (error.isNotBlank() && error != "None") {
                lastErrorMessage = error + traceTail(obj)
                return Resource.Error("脚本错误：$error")
            }
            val arr = runCatching { obj.getAsJsonArray("result") }.getOrNull()
                ?: return Resource.Success(emptyList())
            val list = arr.mapNotNull { el ->
                runCatching { gson.fromJson(el, VodItem::class.java) }.getOrNull()
            }
            Resource.Success(list)
        } catch (e: Exception) {
            Resource.Error(e.message ?: "解析脚本返回失败")
        }
    }

    /**
     * 安全调用 Python 函数 —— 所有入参为 String，避免复杂对象跨语言传递。
     *
     * 关键设计：callAttr 是阻塞 JNI 调用，Kotlin 协程的 withTimeoutOrNull 取消不了它
     * （取消只对挂起点生效，纯阻塞代码不响应）。脚本里网络请求慢/挂死时，
     * UI 会永远卡在"解析中"。因此把调用放进单线程执行器，用 Future.get(timeout)
     * 做硬超时：超时立即返回 null 走错误分支，Python 线程后台跑完丢弃结果。
     *
     * 单线程执行器天然串行（Python 非线程安全），超时后队列自动继续，不会死锁。
     *
     * 注意：必须用 callAttr（调用模块里的函数），不能用 call ——
     * call 是"调用对象本身"，对模块调用会抛 TypeError: 'module' object is not callable，
     * 这会让整条 Python 链路静默失效。
     */

    /** 阻塞的 call 必须离开主线程：future.get 最长卡调用线程 timeoutSec 秒，
     *  若在主线程执行会连续冻结 UI（搜索 N 个 py 站点 = N×15s 卡死）。
     *  所有 suspend 入口统一走 IO。 */
    private suspend fun callIo(timeoutSec: Long, func: String, vararg args: String): String? =
        withContext(Dispatchers.IO) { call(timeoutSec, func, *args) }

    private fun call(timeoutSec: Long, func: String, vararg args: String): String? = try {
        val anyArgs: Array<Any?> = args.map { it as Any? }.toTypedArray()
        val future = pyExecutor.submit(Callable { module.callAttr(func, *anyArgs)?.toString() })
        try {
            future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            // 不中断正在跑的线程（Chaquopy 中断线程不安全），只丢弃结果放行 UI
            future.cancel(false)
            lastErrorMessage = "Python $func 调用超时（>${timeoutSec}s），脚本网络可能异常"
            android.util.Log.w("FanHuaTV", "Python $func 调用超时：>${timeoutSec}s")
            null
        }
    } catch (e: Throwable) {
        // Chaquopy 的 Python 异常是 Error 子类（PyException），catch Exception 抓不住，
        // 会让整个导入/首页链路崩溃。必须抓 Throwable。
        lastErrorMessage = e.message ?: "Python 调用异常"
        android.util.Log.w("FanHuaTV", "Python $func 调用失败：${e.message}")
        null
    }
}
