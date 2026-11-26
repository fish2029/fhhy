package com.fanhuatv.jar

import android.content.Context
import android.util.Log
import com.fanhuatv.core.model.CategoryConfig
import com.fanhuatv.core.model.Episode
import com.fanhuatv.core.model.SiteConfig
import com.fanhuatv.core.model.VodItem
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.HashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 对应 TVBox 的 Result：播放地址 + 请求头 + 是否需要 parse 嗅探 */
data class JarPlayResult(
    val url: String,
    val headers: Map<String, String>?,
    val needParse: Boolean,
    val danmaku: String = "",
    /** 歌手海报（jar 的 artwork 字段，音乐/MV 类源才有） */
    val artwork: String = "",
    /** 歌词：jar 的 lyric/lyrics/subs 里名为 lyric/lrc/歌词 的条目地址（或 LRC 原文） */
    val lyric: String = ""
)

/**
 * Jar 爬虫运行时引擎。
 *
 * 管理 jar 包内 Spider 实例的创建、初始化、调用与销毁，负责把爬虫返回的
 * MacCMS 格式 JSON 解析成 App 的模型（CategoryConfig / VodItem / Episode）。
 *
 * 接入 MainRepository 后，所有 csp_ jar 站点（除 csp_Douban 走 dbapi 外）
 * 的首页、分类、搜索、详情、播放全部走此引擎，不再走"需要 jar 引擎"的空兜底。
 */
@Singleton
class JarRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient
) {

    private val jarLoader = JarLoader(context, client)

    /** 5266 Go 代理引擎（pvideo）：网盘/弹幕依赖 127.0.0.1:5266，见 [GoProxyLauncher] */
    private val goProxy = GoProxyLauncher(context, client)

    /** 当前配置的 jar 包地址（config 的 spider 字段） */
    private var spiderUrl: String = ""

    /** 当前配置源地址，用于解析 ext 中的相对路径 */
    private var sourceUrl: String = ""

    /** spider 实例缓存：api + ext -> Spider（同一个 api 不同 ext 是不同站点，不能共用） */
    private val spiders = ConcurrentHashMap<String, Spider>()

    /**
     * 设置当前配置的 jar 地址和源地址。
     * 在 MainRepository 加载配置后调用，供 JarLoader 下载 jar、解析相对 ext。
     */
    fun setConfig(spiderUrl: String, sourceUrl: String) {
        this.spiderUrl = spiderUrl
        this.sourceUrl = sourceUrl
    }

    /** 释放所有 spider 实例（配置刷新时调用） */
    fun destroyAll() {
        goProxy.ensureStarted()
        spiders.forEach { (_, spider) ->
            runCatching { spider.destroy() }
        }
        spiders.clear()
        categoryCache.clear()
        jarLoader.release(spiderUrl)
    }

    /** 分类结果 TTL 缓存：来回切分类标签时 90 秒内直接命中，不再等蜘蛛重跑网络请求。
     *  只缓存非空列表；超容量淘汰最早条目，配置刷新（destroyAll）时整体失效 */
    private val categoryCache = ConcurrentHashMap<String, Pair<Long, List<VodItem>>>()

    /** 预下载 jar 到本地缓存（重复调用直接命中缓存），供配置加载后提前预热 */
    suspend fun preloadJar(jarUrl: String) {
        if (jarUrl.isBlank()) return
        jarLoader.loadJar(jarUrl)
    }

    /** 后台拉 5266 Go 引擎（进程内一次）；配置加载完就调，用户进到网盘/弹幕时已在线 */
    fun ensureGoProxy() {
        goProxy.ensureStarted()
    }

    /** 同一个 api + ext 才允许复用实例：csp_App99 用 ext 区分剧圈/半日两个站点 */
    private fun spiderKey(site: SiteConfig): String = site.api + "|" + site.ext

    /**
     * 获取或创建并初始化 Spider 实例。
     * 注意：这个方法可能包含同步网络请求，绝不允许在硬超时之外直接调用，
     * 只能由 [callSite] 放进 spiderExecutor 里执行。
     */
    private suspend fun prepareSpider(site: SiteConfig): Spider? {
        spiders[spiderKey(site)]?.let { return it }
        if (spiderUrl.isBlank()) {
            Log.w("FanHuaTV", "未配置 spider jar 地址，无法加载 jar 站点「${site.name}」")
            return null
        }

        // 全局限流：咕噜等加密站点的 init/首次调用要与服务端做 ECDH 握手，而服务端按 IP
        // 限流（实测同 IP 24 路并发握手几乎全部 429，12 路约 1/4 被拒）。全站搜索若同时
        // 初始化几十个站点必被限流 → 握手全部失败 → 分类/搜索永远为空。这里把
        // 「创建 + 握手」并发压到 3：宁可慢几秒，也不能把服务端限流打出来。
        val waitAt = android.os.SystemClock.elapsedRealtime()
        initSlots.acquire()
        try {
            val spider = jarLoader.createSpider(spiderUrl, site.api) ?: return null
            // 登记到本地代理：请求带 siteKey 时按站点精确路由到本 jar，避免多站点互相覆盖
            LocalProxy.registerLoader(site.key, jarLoader.activeLoader)
            LocalProxy.activeLoader = jarLoader.activeLoader

            // TVBox 约定：spider.siteKey 必须是配置里的站点 key；initApi 也要给到。
            spider.siteKey = site.key
            runCatching { spider.initApi(SpiderApi()) }
            // jar 内 Proxy 启动时会从 9978 一路探测到 10000，靠 GET /proxy?do=ck 返回 "ok"
            // 认端口；我们直接把本地代理端口写进 jar 的 Proxy，省掉 22 次探测，
            // 也避免宿主代理没起时它误认成 0 端口（弹幕/网盘代理全指向 127.0.0.1:0）。
            injectProxyPort(jarLoader.activeLoader)
            // 解析 ext：相对路径需下载后传内容，http 原样传，JSON 对象已是字符串
            val extStr = resolveExt(site.ext)
            spider.init(context, extStr)
            spiders[spiderKey(site)] = spider
            // 部分蜘蛛 init 只是起了个后台线程去做握手/注册（如咕噜的 ECDH 握手 + boot）。
            // 不等它完成就调 searchContent 时，蜘蛛自己又会同步握手一次，两边并发写同一份
            // 会话状态，后续请求必然带错密钥。这里轮询到就绪再返回，首搜即可成功。
            val bootAt = android.os.SystemClock.elapsedRealtime()
            val ready = awaitBooted(spider, 12_000)
            Log.i(
                "FanHuaTV",
                "jar 站点「${site.name}」(${site.api}) 初始化完成，就绪=$ready，" +
                        "排队 ${bootAt - waitAt}ms，等待 ${android.os.SystemClock.elapsedRealtime() - bootAt}ms"
            )
            return spider
        } finally {
            initSlots.release()
        }
    }

    /** 把本地代理端口写进 jar 内的 com.github.catvod.spider.Proxy（静态 int 字段 a）。
     *  jar 内 Proxy.a() 会自己探测 9978-10000，这里提前钉死，幂等且不影响宿主。 */
    private fun injectProxyPort(loader: ClassLoader?) {
        if (loader == null) return
        runCatching {
            val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
            val f = clazz.getDeclaredField("a")
            f.isAccessible = true
            f.setInt(null, LocalProxy.PORT)
            Log.i("FanHuaTV", "jar 内 Proxy 端口已注入 ${LocalProxy.PORT}")
        }.onFailure {
            // 有的 jar 用 set(int) 方法而不是字段，退而求其次
            runCatching {
                val clazz = loader.loadClass("com.github.catvod.spider.Proxy")
                val m = clazz.getMethod("set", Int::class.javaPrimitiveType)
                m.invoke(null, LocalProxy.PORT)
                Log.i("FanHuaTV", "jar 内 Proxy.set(${LocalProxy.PORT}) 调用成功")
            }.onFailure { e ->
                Log.w("FanHuaTV", "jar 内 Proxy 端口注入跳过：${e.message}")
            }
        }
    }

    /**
     * 等待蜘蛛就绪：轮询 spider 自身或其成员对象上的 `isBooted()`（咕噜把状态放在
     * 内部 GuluCrypto 里）。找不到这样的方法时立刻返回 true——不能因为探测不到
     * 就把所有站点都拖 20 秒。
     */
    private fun awaitBooted(spider: Spider, timeoutMs: Long): Boolean {
        if (!hasBootedProbe(spider)) return true   // 普通蜘蛛没有就绪概念，不等待
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (booted(spider) == true) return true
            Thread.sleep(200)
        }
        return booted(spider) == true
    }

    /** 该蜘蛛（或其成员对象）是否暴露 isBooted()Z——决定要不要等 */
    private fun hasBootedProbe(spider: Spider): Boolean {
        if (spider.javaClass.methods.any { it.name == "isBooted" }) return true
        return spider.javaClass.declaredFields.any { f ->
            runCatching {
                f.isAccessible = true
                val t = f.get(spider) ?: return@runCatching false
                t.javaClass.methods.any { it.name == "isBooted" }
            }.getOrDefault(false)
        }
    }

    /** 调用 isBooted()Z；探测不到或调用异常时返回 null */
    private fun booted(spider: Spider): Boolean? {
        runCatching {
            spider.javaClass.methods.firstOrNull { it.name == "isBooted" }?.let {
                return it.invoke(spider) as? Boolean
            }
        }
        for (field in spider.javaClass.declaredFields) {
            runCatching {
                field.isAccessible = true
                val target = field.get(spider) ?: return@runCatching
                val m = target.javaClass.methods.firstOrNull { it.name == "isBooted" }
                    ?: return@runCatching
                val v = m.invoke(target) as? Boolean
                if (v != null) return v
            }
        }
        return null
    }

    /**
     * 解析 ext 字符串：
     * - 相对路径（./开头）→ 拼接源地址下载，传文件内容
     * - http 开头 → 原样传入（部分爬虫直接用 URL 作 host）
     * - 其他 → 原样传入（JSON 字符串或纯文本）
     */
    private suspend fun resolveExt(ext: String): String {
        if (ext.isBlank()) return ""
        return when {
            ext.startsWith("./") -> {
                // 相对路径：拼接到配置源地址的目录
                val base = sourceUrl.substringBeforeLast('/') + "/"
                val fullUrl = base + ext.removePrefix("./")
                downloadText(fullUrl)
            }
            ext.startsWith("http") -> ext
            else -> ext
        }
    }

    /** 下载文本文件，返回内容；失败返回空串 */
    private suspend fun downloadText(url: String): String {
        return try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        } catch (e: Exception) {
            Log.w("FanHuaTV", "ext 下载失败：$url ${e.message}")
            ""
        }
    }

    // ───────────────────────── 数据方法 ─────────────────────────

    /**
     * 动作路由只认配置中心类蜘蛛（csp_Config）：
     * - csp_Config：条目 vod_id 本身就是动作码（300=设夸克Cookie、400=清除、
     *   newquark=个人中心、recovery=重置等，jar 内 detailContent 就是 p(vod_id)）。
     *   jar 的 action 字段依赖宿主 Spider 是否声明 action 方法（C0421I.f 反射），
     *   个别机型/初始化时序下可能为空串，这里直接用 vod_id 补齐，保证点击必走动作。
     * - 其他站点：强制清空 action。普通影片条目万一在 JSON 里带了 action 字段，
     *   会被点击路由误判成"执行动作不进详情"，表现为片子点不动（热播被误伤的教训）。
     */
    private fun normalizeConfigActions(site: SiteConfig, list: List<VodItem>): List<VodItem> {
        return when (site.api) {
            "csp_Config" -> list.map {
                if (it.action.isBlank() && it.id.isNotBlank()) it.copy(action = it.id) else it
            }
            else -> list.map { if (it.action.isBlank()) it else it.copy(action = "") }
        }
    }

    /** jar spider 调用线程池：CachedThreadPool——挂死调用不占调度位，新调用永不排队。
     *  spider 方法是阻塞 Java 调用，协程超时取消不了它，必须 Future.get 硬超时。 */
    private val spiderExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "jar-spider").apply { isDaemon = false }
    }

    /**
     * 每个 Spider 实例的串行锁。
     * jar 内的加密蜘蛛（咕噜等）把会话密钥/状态存在实例字段里，init 时还会另起线程
     * 做握手 + boot；如果同一实例被并发调用（全站搜索时多个站点同时压上来），
     * 握手线程与业务调用会互相覆盖会话状态，结果是请求带着错配的密钥发出，
     * 服务端直接拒掉——分类能出来但搜索永远为空。这里保证同一实例的调用严格串行。
     */
    private val spiderLocks = ConcurrentHashMap<String, Any>()

    /**
     * 蜘蛛初始化全局限流（阻塞式信号量）：同一时刻最多 3 个站点在创建/握手。
     * 见 [prepareSpider] 注释——超限会被服务端 429 拒绝，表现是搜索整体为空。
     */
    private val initSlots = java.util.concurrent.Semaphore(3)

    /**
     * 把「实例创建 + init + 业务调用」整体放进同一个硬超时里。
     * 之前 init 在超时之外，某个蜘蛛 init 里同步请求卡住时，播放页会永远停在解析中。
     */
    private suspend fun <T> callSite(site: SiteConfig, timeoutMs: Long, block: (Spider) -> T): T? =

        withContext(Dispatchers.IO) {
            // jar 内弹幕等代理功能依赖本地 /proxy 服务，首次用 jar 引擎时拉起
            LocalProxy.ensureStarted()
            // 同理拉 5266 Go 引擎（网盘解析/弹幕），否则配置中心一直显示「Go 离线」
            goProxy.ensureStarted()
            val future = spiderExecutor.submit(java.util.concurrent.Callable {
                // 不带调度器的 runBlocking：prepareSpider 内部全是阻塞调用，
                // 直接跑在当前 spiderExecutor 线程上。之前指定 Dispatchers.IO 会
                // 再占一个 IO 线程，全站搜索时外层 future.get + 内层 runBlocking
                // 双重占用，容易把 IO 线程池耗光拖慢整轮搜索。
                val lock = spiderLocks.getOrPut(spiderKey(site)) { Any() }
                synchronized(lock) {
                    val spider = kotlinx.coroutines.runBlocking { prepareSpider(site) }
                        ?: return@Callable null
                    block(spider)
                }
            })
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(false)   // 不中断线程（DexClassLoader 环境中断不安全），丢弃结果
                Log.w("FanHuaTV", "jar spider「${site.api}」调用超时（>${timeoutMs}ms）")
                null
            } catch (e: Exception) {
                Log.w("FanHuaTV", "jar spider「${site.api}」调用失败：${e.message}")
                null
            }
        }

    /** 首页内容：返回分类 + 影片列表 */
    suspend fun homeContent(site: SiteConfig): Pair<List<CategoryConfig>, List<VodItem>>? {
        return try {
            val json = callSite(site, 25_000) { it.homeContent(false) } ?: return null
            parseCategories(json) to normalizeConfigActions(site, parseList(json, site.name))
        } catch (e: Exception) {
            Log.e("FanHuaTV", "jar homeContent 失败：${site.name}", e)
            null
        }
    }

    /** 分类内容：返回影片列表 */
    suspend fun categoryContent(site: SiteConfig, typeId: String, page: String): List<VodItem> {
        val key = spiderKey(site) + "|" + typeId + "|" + page
        categoryCache[key]?.let { (at, v) ->
            if (System.currentTimeMillis() - at < 90_000) return v
            categoryCache.remove(key)
        }
        return try {
            val json = callSite(site, 25_000) {
                it.categoryContent(typeId, page, true, HashMap())
            } ?: return emptyList()
            val list = normalizeConfigActions(site, parseList(json, site.name))
            if (list.isNotEmpty()) {
                if (categoryCache.size >= 48) categoryCache.remove(categoryCache.keys.firstOrNull())
                categoryCache[key] = System.currentTimeMillis() to list
            }
            list
        } catch (e: Exception) {
            Log.e("FanHuaTV", "jar categoryContent 失败：${site.name}", e)
            emptyList()
        }
    }

    /**
     * 搜索内容：单站 15s 硬超时。
     * TVBox 的 30s 是「每个 Future 的超时」，但它不会在 30s 时掐断整轮搜索；
     * 我们之前又把全站总超时设成 30s，导致 94 个站点只跑到前 24 个就被取消。
     * 单站独立超时，才能既快又覆盖全部站点。
     * 15s：咕噜等带 ECDH 握手 + boot 认证的蜘蛛，首次搜索多两个网络往返，
     * 12s 在慢网络下会被掐掉，表现为该站永远搜不到东西。
     */
    suspend fun searchContent(site: SiteConfig, keyword: String): List<VodItem> {
        val at = android.os.SystemClock.elapsedRealtime()
        return try {
            val json = callSite(site, 60_000) { it.searchContent(keyword, false) }
            if (json == null) {
                Log.w("FanHuaTV", "jar 搜索「$keyword」站点「${site.name}」(${site.api})：无返回（超时/初始化失败），耗时 ${android.os.SystemClock.elapsedRealtime() - at}ms")
                return emptyList()
            }
            val list = normalizeConfigActions(site, parseList(json, site.name))
            // 搜索为空是最难定位的故障：把原始返回长度和站点信息打全，一次截图即可判断
            // 是服务端没结果、还是调用超时被我们提前丢弃
            Log.i("FanHuaTV", "jar 搜索「$keyword」站点「${site.name}」(${site.api})：原始 ${json.length} 字节，解析 ${list.size} 条，耗时 ${android.os.SystemClock.elapsedRealtime() - at}ms")
            list
        } catch (e: Exception) {
            Log.e("FanHuaTV", "jar searchContent 失败：${site.name}", e)
            emptyList()
        }
    }

    /** 详情内容：返回带剧集的 VodItem */
    suspend fun detailContent(site: SiteConfig, ids: List<String>): VodItem? {
        val at = android.os.SystemClock.elapsedRealtime()
        return try {
            val json = callSite(site, 25_000) { it.detailContent(ids) }
            if (json == null) {
                Log.w("FanHuaTV", "jar 详情站点「${site.name}」(${site.api}) ids=$ids：无返回（超时/初始化失败），耗时 ${android.os.SystemClock.elapsedRealtime() - at}ms")
                return null
            }
            // siteKey 必须是配置里的站点 key（中文），不能是 api（csp_Xxx）：
            // 展示层按 key 匹配 sites[].name，用 api 会露岀「csp_Gulu」这种内部名
            val detail = parseDetail(json, site.key, site.name)
            Log.i("FanHuaTV", "jar 详情站点「${site.name}」(${site.api}) ids=$ids：原始 ${json.length} 字节，解析 ${detail?.episodes?.size ?: -1} 集，playUrl=${detail?.playUrl?.length ?: 0} 字符，耗时 ${android.os.SystemClock.elapsedRealtime() - at}ms")
            detail
        } catch (e: Exception) {
            Log.e("FanHuaTV", "jar detailContent 失败：${site.name}", e)
            null
        }
    }

    /**
     * 执行 jar 条目自带的 action（TVBox 语义：点击带 action 的条目不进详情，
     * 直接调 Spider.action，由蜘蛛自己弹扫码/输入框或清文件）。
     * Config 蜘蛛的 action 内部 Init.execute 异步跑，返回空串属正常。
     */
    suspend fun runAction(site: SiteConfig, action: String) {
        if (action.isBlank()) return
        callSite(site, 10_000) { it.action(action) }
    }

    /** 播放内容：地址 + 请求头 + 是否需要走 TVBox 的 parse 嗅探（蜘蛛常要抓播放页，给足 15 秒） */
    suspend fun playerContent(
        site: SiteConfig,
        parseFlag: String,
        id: String,
        flags: List<String>
    ): JarPlayResult? {
        val at = android.os.SystemClock.elapsedRealtime()
        return try {
            val json = callSite(site, 40_000) { it.playerContent(parseFlag, id, flags) }
            if (json == null) {
                Log.w("FanHuaTV", "jar 播放站点「${site.name}」(${site.api}) flag=$parseFlag id=${id.take(60)}：无返回（超时/初始化失败），耗时 ${android.os.SystemClock.elapsedRealtime() - at}ms")
                return null
            }
            val parsed = parsePlayUrl(json)
            Log.i("FanHuaTV", "jar 播放站点「${site.name}」(${site.api}) flag=$parseFlag id=${id.take(60)}：原始 ${json.length} 字节，url=${parsed?.url?.take(80) ?: "空"} needParse=${parsed?.needParse}，耗时 ${android.os.SystemClock.elapsedRealtime() - at}ms")
            parsed
        } catch (e: Exception) {
            Log.e("FanHuaTV", "jar playerContent 失败：${site.name}", e)
            null
        }
    }

    /** 判断 URL 是否视频格式 */
    suspend fun isVideoFormat(site: SiteConfig, url: String): Boolean {
        return callSite(site, 5_000) { it.isVideoFormat(url) } ?: false
    }

    // ───────────────────────── JSON 解析 ─────────────────────────

    /** 从 JSONObject 按候选键名取字符串，取第一个非空值 */
    private fun jsonStr(o: JSONObject, vararg keys: String): String =
        keys.firstNotNullOfOrNull { k ->
            runCatching { o.optString(k) }.getOrDefault("").takeIf { it.isNotBlank() }
        }.orEmpty()

    /** 清洗 HTML：jar Spider 返回的简介常是带 <p style=...> 的富文本，去掉标签和实体再显示 */
    private fun stripHtml(s: String): String {
        if (s.isBlank()) return s
        return runCatching {
            s.replace(Regex("<[^>]*>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
        }.getOrDefault(s)
    }

    /** 解析影片列表：从 JSON 的 list 数组提取 VodItem。
     *  兼容两类返回：标准键 list；以及无 @SerializedName 的混淆键（Gulu 等 dex 混淆后
     *  Gson 按字段名输出）——顶层数组候选 list/b/a/h，条目字段候选见下方各键。 */
    private fun parseList(json: String, siteName: String = ""): List<VodItem> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val trimmed = json.trim()
            // 顶层数组形态：直接当条目数组解析
            if (trimmed.startsWith("[")) {
                return@runCatching parseVodArray(JSONArray(trimmed), "", siteName)
            }
            val obj = JSONObject(trimmed)
            // 顶层数组候选：list 标准键优先，b/a/h 为混淆键兜底
            val arr = listOf("list", "b", "a", "h").firstNotNullOfOrNull { k ->
                obj.optJSONArray(k)?.takeIf { it.length() > 0 }
            }
            val result = if (arr != null) parseVodArray(arr, "", siteName) else mutableListOf()
            result
        }.getOrDefault(emptyList())
    }

    /** 从条目数组提取 VodItem；字段取值标准键在前、混淆键兜底，不影响正常源 */
    private fun parseVodArray(arr: JSONArray, siteKey: String, siteName: String = ""): MutableList<VodItem> {
        val result = mutableListOf<VodItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = VodItem(
                id = jsonStr(o, "vod_id", "id", "b"),
                title = jsonStr(o, "vod_name", "name", "c"),
                cover = jsonStr(o, "vod_pic", "pic", "d"),
                rating = jsonStr(o, "vod_remarks", "remarks", "e"),
                year = jsonStr(o, "vod_year", "year"),
                category = jsonStr(o, "type_name", "type"),
                desc = stripHtml(jsonStr(o, "vod_content", "vod_blurb", "content", "a")),
                director = jsonStr(o, "vod_director", "director"),
                actor = stripHtml(jsonStr(o, "vod_actor", "actor")),
                source = "jar",
                siteKey = siteKey,
                siteName = siteName,
                playUrl = jsonStr(o, "vod_play_url", "l"),
                playFrom = jsonStr(o, "vod_play_from", "play_from", "flag", "k"),
                // TVBox 约定：带 action 的条目（配置中心等）点击执行动作，不进详情
                action = jsonStr(o, "action")
            )
            if (item.id.isNotBlank() && item.title.isNotBlank()) result.add(item)
        }
        return result
    }

    /** 解析分类列表：从 JSON 的 class 数组提取 CategoryConfig */
    private fun parseCategories(json: String): List<CategoryConfig> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val obj = JSONObject(json)
            // 标准键 class 优先；咕噜等混淆 jar 的分类数组在 "a"，元素字段为 a(type_id)/b(type_name)
            val arr = obj.optJSONArray("class") ?: obj.optJSONArray("a")
            val result = mutableListOf<CategoryConfig>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val cat = CategoryConfig(
                        key = jsonStr(o, "type_id", "id", "a"),
                        name = jsonStr(o, "type_name", "name", "b"),
                        siteKey = ""
                    )
                    if (cat.key.isNotBlank() && cat.name.isNotBlank()) result.add(cat)
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    /**
     * 解析详情：从 JSON 的 list[0] 提取 VodItem，含剧集列表。
     * vod_play_url 标准 MacCMS 格式：多源 $$$ 分隔、源内集间 # 分隔、每集 名称$地址。
     */
    private fun parseDetail(json: String, siteKey: String, siteName: String = ""): VodItem? {
        if (json.isBlank()) return null
        return runCatching {
            val obj = JSONObject(json)
            // 条目数组候选：list 标准键优先，b/a/h 为混淆键兜底
            val item = listOf("list", "b", "a", "h").firstNotNullOfOrNull { k ->
                obj.optJSONArray(k)?.optJSONObject(0)
            } ?: return null
            fun s(vararg keys: String): String = keys.firstNotNullOfOrNull { k ->
                runCatching { item.optString(k) }.getOrDefault("").takeIf { it.isNotBlank() }
            }.orEmpty()

            val episodes = parsePlayUrlToEpisodes(s("vod_play_url", "l"), s("vod_play_from", "play_from", "flag", "k"))

            VodItem(
                id = s("vod_id", "id", "b"),
                title = s("vod_name", "name", "c"),
                cover = s("vod_pic", "pic", "d"),
                rating = s("vod_remarks", "remarks", "e"),
                year = s("vod_year", "year"),
                category = s("type_name", "vod_class", "class"),
                desc = stripHtml(s("vod_content", "vod_blurb", "content", "a")),
                director = stripHtml(s("vod_director", "director")),
                actor = stripHtml(s("vod_actor", "actor")),
                source = "jar",
                siteKey = siteKey,
                siteName = siteName,
                // 保留原始 vod_play_url：playerContent 需要它拆出的复合 id（AppYQK 用 |，App99 用 @）
                playUrl = s("vod_play_url", "play_url", "l"),
                playFrom = s("vod_play_from", "play_from", "flag", "k"),
                episodes = episodes
            ).takeIf { it.id.isNotBlank() }
        }.getOrNull()
    }

    /** playerContent 返回结构：地址 + spider 指定的请求头 */
    /**
     * 解析播放 URL：与 TVBox Result 一致，保留 parse/jx 标记，
     * 由 PlaySourceResolver 做 Source.fetch + WebView 嗅探，而不是直接拒绝。
     */
    private fun parsePlayUrl(json: String): JarPlayResult? {
        if (json.isBlank()) return null
        // 部分 spider 的 playerContent 不返回 JSON，而是裸地址字符串（TVBox 惯例），按直链自然匹配
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return JarPlayResult(url = trimmed, headers = null, needParse = !isDirectVideoUrl(trimmed))
            }
            return null
        }
        return runCatching {
            val obj = JSONObject(trimmed)
            // url 字段三种形态（对齐 FongMi UrlAdapter 语义）：
            // 字符串直用；数组为 [名称,地址,名称,地址,...] 成对排列取第一对地址；
            // 对象为 {"values":[{"n":..,"v":..}],"position":0} 按 position 取。
            // g 为混淆键兜底：Gulu 等无注解 dex 里 Gson 按字段名输出，播放地址在 g
            val url = resolveUrlField(obj.opt("url"))
                ?: resolveUrlField(obj.opt("g"))
                ?: obj.optString("play_url").takeIf { it.isNotBlank() }
                ?: run {
                    // 蜘蛛自己的报错（o=混淆键，兼容 msg/errMsg/message/error/note）：
                    // 只记日志便于定位，不改播放行为——播不出来照常报错
                    val err = listOf("o", "msg", "errMsg", "message", "error", "note")
                        .firstNotNullOfOrNull { k -> obj.optString(k).takeIf { it.isNotBlank() } }
                    if (!err.isNullOrBlank()) Log.w("FanHuaTV", "jar playerContent 蜘蛛报错：$err")
                    null
                }
                ?: return@runCatching null
            // parse 标记：标准键 parse 优先，混淆键 i 兜底（int，1=二次解析/0=直链）
            val parse = if (obj.has("parse")) obj.optInt("parse", 0) else obj.optInt("i", 0)
            val jx = obj.optInt("jx", 0)
            // header 在 jar 里既可能是 JSON 字符串，也可能直接是 JSONObject
            // d 为混淆键兜底：是 Gson toJson 后的 JSON 字符串，parseHeaderJson 会二次解析
            val headers = when (val h = obj.opt("header") ?: obj.opt("d")) {
                is JSONObject -> headerMap(h)
                is String -> parseHeaderJson(h)
                else -> null
            }
            // 歌手海报：标准键 artwork；音乐类蜘蛛常用它带出封面图
            val artwork = jsonStr(obj, "artwork", "artist_pic", "singer_pic")
            // 歌词：三种给法都接——lyric 单串、lyrics 数组（取第一条）、
            // subs 数组里名字含 lyric/lrc/歌词 的条目（与电视版判定一致）
            var lyric = obj.optString("lyric").takeIf { it.isNotBlank() }.orEmpty()
            if (lyric.isBlank()) {
                lyric = firstItemUrl(obj.optJSONArray("lyrics"))
            }
            if (lyric.isBlank()) {
                lyric = lyricFromSubs(obj.optJSONArray("subs")) ?: lyric
            }
            JarPlayResult(
                url = url,
                headers = headers,
                needParse = parse == 1 || jx == 1 || !isDirectVideoUrl(url),
                danmaku = obj.optString("danmaku"),
                artwork = artwork,
                lyric = lyric
            )
        }.getOrNull()
    }

    /**
     * 解析 playerContent 返回的 url 字段，兼容字符串 / 数组 / 对象三种形态。
     * 数组按 FongMi 惯例为 [名称,地址,...] 成对排列，取第一对地址；
     * 对象按 {"values":[{"n":..,"v":..}],"position":N} 取 position 指向的地址。
     * 识别不出地址时返回 null，不伪造。
     */
    private fun resolveUrlField(raw: Any?): String? {
        when (raw) {
            null -> return null
            is String -> return raw.takeIf { it.isNotBlank() }
            is JSONArray -> {
                if (raw.length() == 0) return null
                // 成对排列：偶数位名称、奇数位地址；取第一个地址位
                var i = 0
                while (i + 1 < raw.length()) {
                    val v = raw.optString(i + 1)
                    if (v.isNotBlank()) return v
                    i += 2
                }
                // 单元素或非成对：唯一元素本身是地址才采用
                val single = raw.optString(0)
                return single.takeIf { raw.length() == 1 && it.startsWith("http") }
            }
            is JSONObject -> {
                val values = raw.optJSONArray("values") ?: return null
                if (values.length() == 0) return null
                val pos = raw.optInt("position", 0).coerceIn(0, values.length() - 1)
                val picked = values.optJSONObject(pos) ?: values.optJSONObject(0) ?: return null
                return picked.optString("v").takeIf { it.isNotBlank() }
                    ?: picked.optString("url").takeIf { it.isNotBlank() }
            }
            else -> return null
        }
    }

    /** lyrics[] 取第一条的地址：对象形态 {url/name} 或纯字符串都兼容 */
    private fun firstItemUrl(array: JSONArray?): String {
        if (array == null || array.length() == 0) return ""
        val first = array.optJSONObject(0) ?: return array.optString(0)
        return jsonStr(first, "url", "v", "address")
    }

    /** subs[] 里名字含 lyric/lrc/歌词 的条目是歌词，其余是字幕（这里只取歌词） */
    private fun lyricFromSubs(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            val lower = name.lowercase()
            if (lower.contains("lyric") || lower.contains("lrc") || name.contains("歌词")) {
                val url = jsonStr(obj, "url", "v", "address")
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    /** 解析 spider 返回的 header 字段（JSON 字符串或 JSON 对象）为请求头 Map */
    private fun parseHeaderJson(s: String): Map<String, String>? {
        if (s.isBlank()) return null
        return runCatching { headerMap(JSONObject(s)) }.getOrNull()
    }

    private fun headerMap(obj: JSONObject): Map<String, String>? {
        val map = mutableMapOf<String, String>()
        obj.keys().forEach { k -> map[k] = obj.optString(k) }
        return map.ifEmpty { null }
    }

    /** 判断 URL 是否为直接视频地址（m3u8/mp4/flv 等常见直链后缀） */
    private fun isDirectVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".m3u") ||
                lower.contains(".flv") || lower.contains(".mkv") || lower.contains(".ts")
    }

    /**
     * 把 vod_play_url 解析成 Episode 列表。
     * 标准 MacCMS 格式：多播放源之间用 $$$ 分隔；同一源内每集之间用 # 分隔；
     * 每集为 名称$地址。解析全部线路的剧集，每集带上自己的线路名（playFrom），
     * 详情页按线路分组展示——一部片子几条线路里常有整条失效的，只取第一条
     * 会把用户钉死在死线路上，永远够不着能播的线。
     */
    private fun parsePlayUrlToEpisodes(playUrl: String, playFrom: String = ""): List<Episode> {
        if (playUrl.isBlank()) return emptyList()
        return runCatching {
            val flags = playFrom.split("\$\$\$").map { it.trim() }
            val sources = playUrl.split("\$\$\$")
            val result = mutableListOf<Episode>()
            sources.forEachIndexed { si, source ->
                // 线路名与播放源按顺序对应；数量对不上时按位置命名，不瞎猜
                val flag = flags.getOrNull(si)?.takeIf { it.isNotBlank() && flags.size == sources.size }
                    ?: if (sources.size > 1) "线路${si + 1}" else ""
                source.split("#").mapNotNull { ep ->
                    val trimmed = ep.trim()
                    if (trimmed.isBlank()) return@mapNotNull null
                    // 每集格式：名称$地址；无 $ 时视为纯 URL
                    val idx = trimmed.indexOf('$')
                    if (idx >= 0) {
                        val name = trimmed.substring(0, idx).trim()
                        val url = trimmed.substring(idx + 1).trim()
                        if (url.isNotBlank()) Episode(name = name, url = url, playFrom = flag) else null
                    } else {
                        // 纯 URL，无集名
                        if (trimmed.startsWith("http")) Episode(name = "", url = trimmed, playFrom = flag) else null
                    }
                }.forEach { result.add(it) }
            }
            result
        }.getOrDefault(emptyList())
    }

    /** 简单的超时包装：在调用阻塞方法时用协程超时控制 */
    private suspend fun <T> withTimeoutSafe(timeoutMs: Long, block: () -> T): T? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { block() }
        }
}
