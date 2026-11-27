package com.fanhuatv.core.storage

import android.content.Context
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.CategoryConfig
import com.fanhuatv.core.model.ConfigSource
import com.fanhuatv.core.model.LiveChannel
import com.fanhuatv.core.model.SiteConfig
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.network.ApiService
import com.fanhuatv.core.network.ConfigParser
import com.fanhuatv.core.network.FishShellAdapter
import com.fanhuatv.core.storage.dao.FavoriteDao
import com.fanhuatv.core.storage.dao.HistoryDao
import com.fanhuatv.core.storage.dao.SearchHistoryDao
import com.fanhuatv.core.storage.entity.FavoriteEntity
import com.fanhuatv.core.storage.entity.HistoryEntity
import com.fanhuatv.core.storage.entity.SearchHistoryEntity
import com.fanhuatv.core.util.Resource
import com.fanhuatv.core.util.UrlRules
import com.fanhuatv.jar.JarRuntime
import com.fanhuatv.player.PlaySourceResolver
import com.fanhuatv.python.PyRuntime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一数据出口。核心策略：
 * 1. 多源顺序尝试，任一成功即返回（防单接口失效）
 * 2. PY 脚本优先级高于手动 JSON（脚本注入的站点合并进配置）
 */
@Singleton
class MainRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: ApiService,
    private val parser: ConfigParser,
    private val settings: SettingsDataStore,
    private val pyRuntime: PyRuntime,
    private val jarRuntime: JarRuntime,
    private val historyDao: HistoryDao,
    private val favoriteDao: FavoriteDao,
    private val searchDao: SearchHistoryDao
) {

    val history: Flow<List<HistoryEntity>> get() = historyDao.observeAll(100)
    val favorites: Flow<List<FavoriteEntity>> get() = favoriteDao.observeAll()
    val searchHistory: Flow<List<SearchHistoryEntity>> get() = searchDao.observeAll()

    /** 最近一次首页编排的诊断信息，设置页/首页提示用 */
    var lastDiagnostic: String = ""
        private set

    /**
     * TVBox 订阅解析：把 sites[].api 指向的 .py 脚本下载并导入，绑定 scriptId。
     *
     * TVBox 的约定是「一个脚本 + 一个 ext = 一个站点」，所以 ext 必须一起带过去。
     * 每次最多导入 [maxPerLoad] 个，避免 100+ 站点一次全下载把启动拖死；
     * 已导入过的按 URL 记住，下次刷新会继续补齐剩下的。
     */
    private suspend fun syncPySites(raw: String, config: AppConfig): AppConfig =
        kotlinx.coroutines.coroutineScope {
            val obj = runCatching {
                com.google.gson.JsonParser.parseString(raw.trim()).asJsonObject
            }.getOrNull() ?: return@coroutineScope config
            val pySites = runCatching { FishShellAdapter.pySites(obj) }.getOrDefault(emptyList())
            if (pySites.isEmpty()) return@coroutineScope config

            val urlMap = settings.pyUrlMap.first().toMutableMap()
            // 未导入的并行下载：串行 10 个会把后台任务拖到几十秒，分类一直出不来
            val pending = config.sites.filter {
                it.source == "py" && it.api.isNotBlank() && !urlMap.containsKey(it.api)
            }.take(6)
            if (pending.isEmpty()) {
                return@coroutineScope config.copy(sites = config.sites.map { site ->
                    urlMap[site.api]?.let { site.copy(scriptId = it) } ?: site
                })
            }

            val results = pending.map { site ->
                async(Dispatchers.IO) {
                    val siteName = pySites.firstOrNull { it.url == site.api }?.name ?: site.name
                    val r = withTimeoutOrNull(15_000) {
                        pyRuntime.importFromUrl(site.api, siteName, site.ext)
                    }
                    site.api to (r as? Resource.Success)?.data?.id
                }
            }.awaitAll()

            val newlyImported = results.mapNotNull { (url, id) -> id?.let { url to it } }.toMap()
            android.util.Log.i("FanHuaTV", "本次从订阅导入 ${newlyImported.size} 个 PY 脚本")
            val finalMap = urlMap + newlyImported
            config.copy(sites = config.sites.map { site ->
                if (site.source == "py") finalMap[site.api]?.let { site.copy(scriptId = it) } ?: site
                else site
            })
        }

    /** 所有已启用 PY 脚本对应的站点（首页站源切换直接读这里，不依赖异步 enrich 结果） */
    suspend fun enabledPySites(): List<SiteConfig> = pyRuntime.enabledScriptSites()

    /** 上次成功取到首页数据的脚本 ID（首页站源切换用） */
    suspend fun currentBestScript(): String = settings.bestScriptId.first()

    /** 切换首页站源：只记住用户选中的那个站点标识（PY 脚本 id 或接口站点 key），空串=没手动选 */
    suspend fun setHomeSite(siteId: String) {
        settings.setHomeSiteId(siteId)
    }

    /** 当前选中的站源标识，供切换器高亮 */
    suspend fun currentHomeSite(): String = settings.homeSiteId.first()

    /** 最近一次配置原始响应，供后台补数据用（不在主线程路径上做重活） */
    private var lastRawConfig: String = ""
    private var lastSourceUrl: String = ""

    /** 配置里的全部站点：搜索必须按它生成目标，不能用缓存的 jar/json 列表截断 */
    private var allSites: List<SiteConfig> = emptyList()

    /** 当前配置中的 jar 站点列表，供分类/搜索/详情按 siteKey 查找 jar 爬虫 */
    private var jarSites: List<SiteConfig> = emptyList()

    /** 当前配置中的 JSON 接口站点列表（MacCMS ?ac= 接口），供全站搜索使用 */
    private var jsonSites: List<SiteConfig> = emptyList()

    /** 当前配置的 jx 解析接口与线路标记：parse=1/jx=1 的片源优先走接口解析 */
    private var currentParses: List<com.fanhuatv.core.model.ParseConfig> = emptyList()
    private var currentFlags: List<com.fanhuatv.core.model.FlagConfig> = emptyList()

    /** 广告黑名单过滤：api 命中 ads 域名的站点整条剔除（TVBox ads 字段） */
    private fun filterAdSites(config: AppConfig): AppConfig {
        if (config.ads.isEmpty()) return config
        val hit = config.sites.filter { site ->
            config.ads.any { ad -> ad.isNotBlank() && site.api.contains(ad, ignoreCase = true) }
        }
        if (hit.isEmpty()) return config
        lastDiagnostic = "广告黑名单过滤 ${hit.size} 个站点"
        android.util.Log.i("FanHuaTV", "$lastDiagnostic: ${hit.joinToString { it.key }}")
        return config.copy(sites = config.sites.filterNot { it in hit })
    }

    /**
     * 只做「拉取配置 + 解析」，必须秒回。
     * .py 脚本下载/探测这类慢活全部交给 [enrichHomeDataAsync] 在后台做，
     * 否则点一次确认就要等几十秒，首页一直转圈。
     */
    suspend fun loadConfig(): Resource<AppConfig> {
        val sources = settings.configSources.first().filter { it.enabled }
        val activeId = settings.activeSourceId.first()
        val ordered = sources.sortedByDescending { it.id == activeId }

        // 每次加载先清掉上次成功的地址：用户没填/删了接口时，绝不能拿旧地址去拉 dbapi
        lastRawConfig = ""
        lastSourceUrl = ""

        var lastError = "尚未配置接口地址"
        for (source in ordered) {
            var rawBody = ""
            val result = runCatching {
                val response = api.fetchRaw(source.url)
                rawBody = response.body()?.string().orEmpty()
                if (!response.isSuccessful) {
                    lastError = "HTTP ${response.code()}"
                    return@runCatching null
                }
                parser.parseConfig(rawBody).getOrThrow()
            }
            if (result.isSuccess && result.getOrNull() != null) {
                val parsed = filterAdSites(result.getOrNull()!!)
                lastRawConfig = rawBody
                lastSourceUrl = source.url
                allSites = parsed.sites
                currentParses = parsed.parses
                currentFlags = parsed.flags
                // 搜索/详情不能等后台 enrich 完成才有站点表
                jarSites = parsed.sites.filter { it.source == "jar" && it.enabled }
                jsonSites = parsed.sites.filter { it.source == "json" && it.enabled }
                return Resource.Success(parsed)
            }
            lastError = result.exceptionOrNull()?.message ?: lastError
        }
        return Resource.Error("全部接口加载失败：$lastError")
    }

    /**
     * 后台补首页数据：TVBox 订阅里的 .py 下载导入 + 脚本首页探测。
     * 有总超时兜底，且完全不挡 UI；失败就保持原配置，界面不会卡在 loading。
     */
    suspend fun enrichHomeDataAsync(config: AppConfig): AppConfig =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 把 jar 配置（spider 地址 + 源地址）传给 JarRuntime，供 JarLoader 下载 jar
            jarRuntime.setConfig(config.spiderUrl, lastSourceUrl)
            // 解析接口/线路标记随配置更新（换订阅立即生效）
            currentParses = config.parses
            currentFlags = config.flags
            // 缓存 jar 站点列表，分类/搜索/详情按 siteKey 查找
            jarSites = config.sites.filter { it.source == "jar" && it.enabled }
            // 缓存 JSON 接口站点列表，全站搜索并发使用
            jsonSites = config.sites.filter { it.source == "json" && it.enabled }
            // jar 站点存在时先预下载 jar 包（本地有缓存，重复调用秒回），
            // 避免首次点片时现场下载导致详情/播放超时
            if (jarSites.isNotEmpty()) {
                if (config.spiderUrl.isNotBlank()) {
                    runCatching { jarRuntime.preloadJar(config.spiderUrl) }
                }
                // 提前在后台拉 5266 Go 引擎：网盘/弹幕都依赖它，
                // 等用户点到网盘再拉会白等下载，配置中心也一直显示离线
                jarRuntime.ensureGoProxy()
            }
            // runCatching 必须有：后台任务抛未捕获异常会让 viewModelScope 直接杀进程
            val result = runCatching {
                withTimeoutOrNull(40_000) {
                    // 订阅里的 .py 站点批量导入（每次最多 6 个，已导入的按 URL 跳过）。
                    // 之前这个函数是死代码从未被调用，导致订阅 py 站点除了首页那个
                    // 全都没有真实脚本 ID，全站搜索时静默失败。
                    val synced = syncPySites(lastRawConfig, config)
                    val merged = mergeScriptSites(synced)
                    // 后台可能补进本地已导入的 PY 站点；同步给搜索目标表，
                    // 否则搜索仍按初次解析的站点列表走，新脚本永远不会参与搜索。
                    allSites = merged.sites
                    jarSites = merged.sites.filter { it.source == "jar" && it.enabled }
                    jsonSites = merged.sites.filter { it.source == "json" && it.enabled }
                    enrichHomeData(merged, lastSourceUrl).let { fillFromFirstCategory(it) }
                }
            }
            result.getOrNull() ?: config.also {
                lastDiagnostic = "后台获取首页数据失败：${result.exceptionOrNull()?.message ?: "超时"}"
                android.util.Log.w("FanHuaTV", lastDiagnostic, result.exceptionOrNull())
            }
        }

    /**
     * 鱼壳内置 spider（csp_Douban 等）的数据走服务端 dbapi，Kotlin 侧直接请求。
     * host 从当前配置源地址推导（https://www.612668.xyz/T4/config.json -> https://www.612668.xyz），
     * 分类名/影片全部实时来自 dbapi，不内置。
     */
    private suspend fun loadDbapiHome(config: AppConfig, sourceUrl: String, src: String): AppConfig? {
        // host 只从用户填写的接口地址推导（https://www.612668.xyz/T4/config.json -> https://www.612668.xyz），
        // 绝不内置写死域名；用户没填接口就没法推 host，直接返回 null 走别的源。
        val host = Regex("^(https?://[^/]+)").find(sourceUrl)?.groupValues?.get(1)
            ?: return null
        val base = "$host/dbapi/v"
        val got: Pair<List<com.fanhuatv.core.model.CategoryConfig>, List<com.fanhuatv.core.model.VodItem>>? = try {
            val resp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                api.fetchRaw("$base/home?src=$src")
            }
            if (!resp.isSuccessful) null
            else {
                val parsed = parser.parseDbapi(resp.body()?.string().orEmpty()).getOrNull()
                if (parsed == null || (parsed.first.isEmpty() && parsed.second.isEmpty())) null
                else parsed.first to parsed.second
            }
        } catch (e: Exception) {
            android.util.Log.w("FanHuaTV", "dbapi home $host 失败：${e.message}")
            null
        }
        if (got != null) {
            val (categories, videos) = got
            lastDiagnostic = "dbapi($host)：${categories.size} 分类 / ${videos.size} 影片"
            android.util.Log.i("FanHuaTV", lastDiagnostic)
            return config.copy(
                categories = categories,
                homeItems = videos.map { it.copy(siteKey = "dbapi", source = "dbapi") },
                banners = videos.take(5).map { v ->
                    com.fanhuatv.core.model.BannerConfig(
                        title = v.displayTitle,
                        image = v.coverUrl,
                        targetId = v.id,
                        rating = v.displayRating
                    )
                }
            )
        }
        return null
    }

    /**
     * 首页数据只来自「当前站点自身」：没手动选就用配置里的第一个站点。
     * .py 站点跑它自己的脚本，豆瓣站点走它自己的 dbapi，其它站点按各自 api 来，
     * 取不到就如实留空，绝不统一塞一份豆瓣数据。
     */
    private suspend fun enrichHomeData(config: AppConfig, sourceUrl: String): AppConfig {
        val selected = settings.homeSiteId.first()
        val scripts = pyRuntime.enabledScriptSites()
        val sites = config.sites.filter { it.enabled }

        // 没手动选就用配置里第一个站点；选了就只认那一个
        val site = if (selected.isBlank()) sites.firstOrNull()
        else sites.firstOrNull { it.key == selected || it.scriptId == selected || it.api == selected }

        // 选中的是本地导入、但不在接口配置里的脚本
        if (site == null && selected.isNotBlank()) {
            scripts.firstOrNull { it.scriptId == selected || it.key == selected }?.let {
                return pyHome(config, it)
            }
        }

        // 没配任何接口：只导了 PY 就自然用第一个脚本
        if (site == null) {
            val first = scripts.firstOrNull() ?: run {
                lastDiagnostic = "既没配置接口，也没导入 PY 脚本"
                return config.copy(categories = emptyList(), homeItems = emptyList())
            }
            return pyHome(config, first)
        }

        // ① 站点 api 是 .py：按需导入这一个脚本，只跑它
        if (site.api.startsWith("http") && site.api.contains(".py")) {
            val id = ensurePyScript(site)
            if (id != null) return pyHome(config, site.copy(scriptId = id, source = "py"))
            lastDiagnostic = "「${site.name}」脚本导入失败"
            return config.copy(categories = emptyList(), homeItems = emptyList())
        }

        // ② 站点 api 是标准 JSON/MacCMS 地址：用这个站点自己的地址取数据（带站点请求头）
        if (site.api.startsWith("http")) {
            val pair = fetchAcList(site.api, site.headers)
            if (pair != null) {
                lastDiagnostic = "「${site.name}」?ac=list：${pair.first.size} 分类 / ${pair.second.size} 影片"
                return config.copy(categories = pair.first, homeItems = pair.second)
            }
            lastDiagnostic = "「${site.name}」接口未返回数据"
            return config.copy(categories = emptyList(), homeItems = emptyList())
        }

        // ③ 豆瓣站点：它自己的数据就是服务端 dbapi，src 用站点 key，不写死
        if (site.api == "csp_Douban") {
            return loadDbapiHome(config, sourceUrl, site.key)
                ?: config.copy(categories = emptyList(), homeItems = emptyList())
        }

        // ④ jar 爬虫站点：通过 JarLoader 加载 jar，调用 Spider 获取首页数据
        if (site.source == "jar" || (site.api.startsWith("csp_") && site.api != "csp_Douban")) {
            val jarData = jarRuntime.homeContent(site)
            if (jarData != null) {
                val (cats, vids) = jarData
                lastDiagnostic = "「${site.name}」jar：${cats.size} 分类 / ${vids.size} 影片"
                android.util.Log.i("FanHuaTV", lastDiagnostic)
                return config.copy(
                    categories = cats.map { it.copy(siteKey = site.key) },
                    homeItems = vids.map {
                        it.copy(siteKey = site.key, source = "jar")
                    },
                    banners = config.banners.ifEmpty {
                        vids.take(5).map { v ->
                            com.fanhuatv.core.model.BannerConfig(
                                title = v.displayTitle,
                                image = v.coverUrl,
                                targetId = v.id,
                                rating = v.displayRating
                            )
                        }
                    }
                )
            }
            lastDiagnostic = "「${site.name}」(${site.api}) jar 爬虫获取数据失败"
            return config.copy(categories = emptyList(), homeItems = emptyList())
        }

        // 兜底：未识别的站点类型，如实留空
        lastDiagnostic = "「${site.name}」(${site.api}) 未识别的站点类型"
        return config.copy(categories = emptyList(), homeItems = emptyList())
    }

    /**
     * 首页推荐兜底：站点给了分类但没给推荐影片时（homeContent 只回 class 不回 list），
     * 取同一站点第一个分类的第一页当推荐——仍是站点自己的数据，不跨站点、不编造。
     */
    private suspend fun fillFromFirstCategory(config: AppConfig): AppConfig {
        if (config.homeItems.isNotEmpty() || config.categories.isEmpty()) return config
        val first = config.categories.first()
        val siteKey = first.siteKey
        val vids: List<VodItem> = when {
            siteKey.startsWith("py_") -> {
                val id = siteKey.removePrefix("py_")
                (withTimeoutOrNull(10_000) { pyRuntime.category(id, first.key, "1") }
                        as? Resource.Success)?.data.orEmpty()
                    .map { it.copy(siteKey = siteKey, source = "py", scriptId = id) }
            }
            else -> {
                val jarSite = jarSites.firstOrNull { it.key == siteKey }
                if (jarSite != null) jarRuntime.categoryContent(jarSite, first.key, "1")
                    .map { it.copy(siteKey = siteKey, source = "jar") }
                else emptyList()
            }
        }
        if (vids.isEmpty()) return config
        lastDiagnostic = "「${first.name}」分类首页填充推荐 ${vids.size} 部"
        android.util.Log.i("FanHuaTV", lastDiagnostic)
        return config.copy(
            homeItems = vids,
            banners = config.banners.ifEmpty {
                vids.take(5).map { v ->
                    com.fanhuatv.core.model.BannerConfig(
                        title = v.displayTitle,
                        image = v.coverUrl,
                        targetId = v.id,
                        rating = v.displayRating
                    )
                }
            }
        )
    }

    /** 站点 api 指向 .py：已导入就复用，没导入就按它自己的 URL/名称/ext 导入一次 */
    private suspend fun ensurePyScript(site: SiteConfig): String? {
        settings.pyUrlMap.first()[site.api]?.takeIf { it.isNotBlank() }?.let { return it }
        val r = withTimeoutOrNull(15_000) {
            pyRuntime.importFromUrl(site.api, site.name, site.ext)
        }
        return (r as? Resource.Success)?.data?.id
    }

    /** 用站点自己的 api 取 ?ac=list 分类/影片；带站点自己的请求头（防盗链） */
    private suspend fun fetchAcList(
        apiBase: String,
        headers: Map<String, String> = emptyMap()
    ): Pair<List<CategoryConfig>, List<VodItem>>? {
        val url = if (apiBase.contains("?")) "$apiBase&ac=list" else "$apiBase?ac=list"
        return try {
            val resp = withContext(Dispatchers.IO) {
                if (headers.isEmpty()) api.fetchRaw(url) else api.probeUrl(url, headers)
            }
            if (!resp.isSuccessful) null
            else parseMacCmsAny(resp.body()?.string().orEmpty())
                ?.takeIf { it.first.isNotEmpty() || it.second.isNotEmpty() }
        } catch (e: Exception) {
            android.util.Log.w("FanHuaTV", "ac=list 失败：${e.message}")
            null
        }
    }

    /** MacCMS 返回自动识别：XML（type 0 站点）走 XML 解析，其余按 JSON 解析 */
    private fun parseMacCmsAny(body: String): Pair<List<CategoryConfig>, List<VodItem>>? {
        val trimmed = body.trimStart()
        return if (trimmed.startsWith("<")) parser.parseMacCmsXml(trimmed).getOrNull()
        else runCatching { parser.parseMacCmsList(body).getOrThrow() }.getOrNull()
    }

    /** 单个 PY 脚本的首页数据落到 AppConfig；只用它，不混入别的内容 */
    private suspend fun pyHome(
        config: AppConfig,
        site: SiteConfig,
        data: Pair<List<CategoryConfig>, List<VodItem>>? = null
    ): AppConfig {
        val (classes, videos) = data ?: when (
            val r = withTimeoutOrNull(8_000) { pyRuntime.home(site.scriptId) }
        ) {
            is Resource.Success -> r.data
            else -> return config
        }
        val siteKey = "py_${site.scriptId}"
        lastDiagnostic = "PY 脚本「${site.name}」：${classes.size} 分类 / ${videos.size} 影片"
        android.util.Log.i("FanHuaTV", lastDiagnostic)
        return config.copy(
            categories = classes.map { it.copy(siteKey = siteKey) },
            homeItems = videos.map {
                it.copy(siteKey = siteKey, source = "py", scriptId = site.scriptId)
            },
            banners = config.banners.ifEmpty {
                videos.take(5).map { v ->
                    com.fanhuatv.core.model.BannerConfig(
                        title = v.displayTitle,
                        image = v.coverUrl,
                        targetId = v.id,
                        rating = v.displayRating
                    )
                }
            }
        )
    }

    /**
     * 分类页：按分类 id 拉取影片。
     * 分类是「某个脚本的 tid」，必须用产生该分类的脚本去查，否则 tid 对不上会永远空。
     */
    suspend fun loadCategory(tid: String, pg: String = "1", scriptId: String = ""): Resource<List<VodItem>> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // ⓪ dbapi 分类（csp_Douban 等）：只有「当前站点就是 dbapi」才直查服务端分类接口。
            // 不能按 tid 白名单判断：咕噜等 jar 站点的分类 id 同样是 movie/tv/variety/anime/short，
            // 一旦命中就被换成 dbapi（豆瓣）数据——用户在咕噜里点「电影」却看到豆瓣条目，
            // 点进去自然提示"该影片来自 dbapi 源"、永远放不出来。
            if (scriptId == "dbapi") {
                val schemeHost = Regex("^(https?://[^/]+)").find(lastSourceUrl)?.groupValues?.get(1)
                if (schemeHost != null) {
                    try {
                        val resp = api.fetchRaw("$schemeHost/dbapi/v/cat?c=$tid")
                        if (resp.isSuccessful) {
                            val (_, videos) = parser.parseDbapi(resp.body()?.string().orEmpty())
                                .getOrElse { emptyList<CategoryConfig>() to emptyList() }
                            if (videos.isNotEmpty()) {
                                return@withContext Resource.Success(videos.map {
                                    it.copy(siteKey = "dbapi", source = "dbapi")
                                })
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("FanHuaTV", "dbapi cat 失败：${e.message}")
                    }
                }
            }

            // ① jar 站点分类：siteKey 匹配 jar 站点，调用 Spider.categoryContent
            //    分类 tid 是 jar 站点私有的，必须用同一个站点查，不跨站点兜底
            if (scriptId.isNotBlank()) {
                val jarSite = jarSites.firstOrNull { it.key == scriptId }
                if (jarSite != null) {
                    val vids = jarRuntime.categoryContent(jarSite, tid, pg)
                    return@withContext if (vids.isNotEmpty()) {
                        Resource.Success(vids.map {
                            it.copy(siteKey = jarSite.key, source = "jar")
                        })
                    } else {
                        Resource.Success(emptyList())
                    }
                }
            }

            // ② 指定脚本（首页分类栏带过来的 siteKey）
            // 分类 tid 是「产生该分类的脚本」私有的。必须用同一个脚本查，
            // 绝不能拿别的脚本拿同一个 tid 去试——别的脚本忽略 tid 就返回它自己的
            // 首页第一批影片，串到当前分类（表现为点国产动漫却显示更新的内容）。
            if (scriptId.isNotBlank()) {
                val r = withTimeoutOrNull(15_000) { pyRuntime.category(scriptId, tid, pg) }
                return@withContext if (r is Resource.Success && r.data.isNotEmpty()) {
                    Resource.Success(r.data.map {
                        it.copy(siteKey = "py_$scriptId", source = "py", scriptId = scriptId)
                    })
                } else {
                    // 指定脚本查不到就是没有，返回空；不跨脚本兜底，避免串内容
                    Resource.Success(emptyList())
                }
            }

            // ② 没有指定脚本（例如从详情页进入）才遍历脚本找有数据的
            val scripts = pyRuntime.enabledScriptSites()
            if (scripts.isNotEmpty()) {
                val bestId = settings.bestScriptId.first()
                val orderedScripts = scripts.sortedByDescending { it.scriptId == bestId }
                var lastErr = ""
                for (site in orderedScripts.take(4)) {
                    val r = withTimeoutOrNull(10_000) { pyRuntime.category(site.scriptId, tid, pg) }
                    if (r == null) { lastErr = "超时"; continue }
                    when (r) {
                        is Resource.Success -> {
                            if (r.data.isNotEmpty()) {
                                return@withContext Resource.Success(r.data.map {
                                    it.copy(siteKey = "py_${site.scriptId}", source = "py", scriptId = site.scriptId)
                                })
                            }
                        }
                        is Resource.Error -> lastErr = r.message
                        else -> Unit
                    }
                }
                if (lastErr.isNotBlank()) android.util.Log.w("FanHuaTV", "脚本分类加载失败：$lastErr")
            }

            // 脚本没有分类数据 → 退到 MacCMS ?ac=videolist&t=tid&pg=pg
            val macApi = macCmsBase()
            if (macApi.isNotBlank()) {
                val sep = if (macApi.contains("?")) "&" else "?"
                val url = "$macApi${sep}ac=videolist&t=$tid&pg=$pg"
                try {
                    val resp = api.fetchRaw(url)
                    val body = resp.body()?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val (_, videos) = parser.parseMacCmsList(body).getOrElse {
                            return@withContext Resource.Success(emptyList())
                        }
                        if (videos.isNotEmpty()) {
                            return@withContext Resource.Success(videos.map { it.copy(siteKey = "maccms") })
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("FanHuaTV", "ac=videolist 失败：${e.message}")
                }
            }
            Resource.Success(emptyList())
        }

    /** 找出可用的 MacCMS 接口基地址（排除鱼壳 .py 脚本地址） */
    private suspend fun macCmsBase(): String {
        val sources = settings.configSources.first()
        val activeId = settings.activeSourceId.first()
        val url = sources.firstOrNull { it.id == activeId }?.url
            ?: sources.firstOrNull()?.url.orEmpty()
        return url.substringBefore("?").trimEnd('/')
    }

    /** 判断 URL 是否为直接可播放的媒体地址（含音乐源：mp3/flac 等音频直链） */
    private fun isDirectVideoUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".m3u") ||
                lower.contains(".flv") || lower.contains(".mkv") || lower.contains(".ts") ||
                lower.contains(".mp3") || lower.contains(".flac") || lower.contains(".m4a") ||
                lower.contains(".aac") || lower.contains(".wav") || lower.contains(".ogg")
    }

    /** 网盘线路按 TVBox 惯例以线路名通用识别（夸克/UC/迅雷/天翼/115/123/阿里/移动等），
     *  不写死任何站点：网盘线路蜘蛛返回的就是直链，不走解析 */
    private fun isPanFlag(flag: String): Boolean {
        if (flag.isBlank()) return false
        val lower = flag.lowercase()
        return listOf(
            "网盘", "云盘", "夸克", "quark", "uc云", "uc网盘", "迅雷", "thunder",
            "天翼", "阿里", "aliyun", "移动云", "和彩云", "沃家", "联通云"
        ).any { lower.contains(it) }
    }

    /** 仅用指定源刷新（设置页测试用） */
    suspend fun testSource(source: ConfigSource): Resource<String> {
        return try {
            val response = api.fetchRaw(source.url)
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful) return Resource.Error("HTTP ${response.code()}")
            val config = parser.parseConfig(body).getOrNull()
                ?: return Resource.Error("JSON 解析失败")
            Resource.Success("解析成功：${config.sites.size} 个站点 / ${config.categories.size} 个分类")
        } catch (e: Exception) {
            Resource.Error(e.message ?: "请求失败")
        }
    }

    private suspend fun mergeScriptSites(config: AppConfig): AppConfig {
        if (!settings.scriptPriority.first()) return config
        val scriptSites = pyRuntime.enabledScriptSites()
        if (scriptSites.isEmpty()) return config
        val merged = config.sites.toMutableList()
        scriptSites.forEach { site ->
            val idx = merged.indexOfFirst { it.key == site.key }
            if (idx >= 0) merged[idx] = site else merged.add(site)
        }
        return config.copy(sites = merged)
    }

    /**
     * 全站实时搜索：并发请求所有站点（jar / PY / JSON 接口），每站返回立即
     * 通过 onResult 回调追加（实时聚合）——有结果展示、无结果忽略。
     * siteKey 非空 = 只搜指定站点（三级匹配）。
     */
    suspend fun search(keyword: String, siteKey: String, recordHistory: Boolean = true, onResult: (List<VodItem>) -> Unit) {
        if (recordHistory) searchDao.insert(SearchHistoryEntity(keyword = keyword))

        data class Target(val key: String, val name: String, val source: String, val scriptId: String, val api: String, val headers: Map<String, String> = emptyMap())
        val targets = mutableListOf<Target>()

        // 目标必须来自配置里的全部站点，不能用 jarSites/jsonSites 这些后台缓存列表
        val configured = allSites.ifEmpty { jarSites + jsonSites }
        val pyMap = settings.pyUrlMap.first()
        fun targetOf(site: SiteConfig): Target? {
            if (!site.enabled || !site.searchable || site.api == "csp_Config") return null
            return when {
                site.source == "jar" || site.api.startsWith("csp_") ->
                    Target(site.key, site.name, "jar", "", site.api)
                site.source == "py" || site.api.contains(".py") -> {
                    // TVBox 订阅解析时 scriptId 先临时存 URL；真实脚本 ID 在 pyMap（URL->ID）里。
                    // URL 形态的 scriptId 拿去查脚本必然"内容为空"静默失败，必须先换回真实 ID。
                    val realId = pyMap[site.api].orEmpty().ifBlank {
                        site.scriptId.takeIf { it.isNotBlank() && !it.contains("://") }.orEmpty()
                    }
                    if (realId.isBlank()) null
                    else Target(site.key, site.name, "py", realId, "")
                }
                site.api.startsWith("http") ->
                    Target(site.key, site.name, "json", "", site.api, site.headers)
                else -> null
            }
        }

        if (siteKey.isNotBlank()) {
            configured.firstOrNull {
                it.key == siteKey || it.api == siteKey || it.name == siteKey
            }?.let { targetOf(it) }?.let { targets.add(it) }
        } else {
            configured.forEach { targetOf(it)?.let(targets::add) }
        }
        if (targets.isEmpty()) return

        // TVBox 是「每个站点 Future 独立 30s 超时」，不是在 30s 时掐断整轮搜索。
        // 单站已压到 12-15s；总上限 55s：慢站点早点放弃，配合 ViewModel 看门狗保证 UI 一定恢复。
        val seen = java.util.Collections.newSetFromMap(
            java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        )
        val emitted = java.util.concurrent.atomic.AtomicInteger(0)
        val attempted = java.util.concurrent.atomic.AtomicInteger(0)
        val succeeded = java.util.concurrent.atomic.AtomicInteger(0)
        android.util.Log.i("FanHuaTV", "搜索「$keyword」：目标站点 ${targets.size} 个")
        // 对应 TVBox Task.searchExecutor 的并发上限；但 jar 站点的握手在服务端按 IP 限流，
        // 放到 24 会一次性打出几十个 ECDH 握手 → 大量 429 → 全站搜索为空。压到 8 并靠
        // JarRuntime 的初始化信号量（3）把瞬时握手并发控制住。
        val searchSlots = Semaphore(8)
        // 整个并发块必须在 IO 线程池跑：调用方可能是 viewModelScope（主线程），
        // 协程里的阻塞调用（Python Future.get、jar JNI、OkHttp 同步请求）压在主线程
        // 会把界面整个堵死。这里统一切 IO，主线程只收 onResult 回调更新界面。
        withContext(Dispatchers.IO) {
        withTimeoutOrNull(90_000) {
            kotlinx.coroutines.coroutineScope {
                targets.map { t ->
                    async {
                        searchSlots.withPermit {
                        attempted.incrementAndGet()
                        // 单站隔离：每站独立超时 + 独立捕获异常，超时或报错只降级为
                        // 该站无结果，绝不阻断其它站点（静默降级，不弹错不打断全局）。
                        // 搜索只取元信息（片名/封面/ID），播放地址留到详情页再解析。
                        val vids: List<VodItem> = runCatching {
                            withTimeoutOrNull(75_000) {
                            when (t.source) {
                                "jar" -> {
                                    val site = configured.firstOrNull { it.key == t.key }
                                    if (site == null) emptyList()
                                    else {
                                        var list = runCatching { jarRuntime.searchContent(site, keyword) }
                                            .getOrDefault(emptyList())
                                        // 首轮为空多半是握手被服务端限流（429）。此时其它站点已跑完、
                                        // 并发降下来，隔 2.5s 重试一次（Gulu.d() 会重新握手）通常就出结果；
                                        // 不重试的话首次安装/清数据后的第一次搜索必然全空。
                                        if (list.isEmpty()) {
                                            delay(2_500)
                                            list = runCatching { jarRuntime.searchContent(site, keyword) }
                                                .getOrDefault(emptyList())
                                            if (list.isNotEmpty())
                                                android.util.Log.i("FanHuaTV", "站点「${site.name}」错峰重试命中 ${list.size} 条")
                                        }
                                        list.map { it.copy(siteKey = t.key, source = "jar") }
                                    }
                                }
                                "py" -> when (val r = runCatching { pyRuntime.search(t.scriptId, keyword) }.getOrNull()) {
                                    is Resource.Success -> r.data.map {
                                        it.copy(siteKey = t.key, source = "py", scriptId = t.scriptId)
                                    }
                                    else -> emptyList()
                                }
                                else -> searchJsonSite(t.api, keyword, t.headers).map { it.copy(siteKey = t.key, source = "json") }
                            }
                            } ?: emptyList()
                        }.getOrDefault(emptyList())
                        // 去重必须带站点：同一部剧在多个站点都要保留，否则筛选栏只剩两三个站点
                        val fresh = vids.filter { v ->
                            val k = "${t.source}|${t.key}|${v.id}|${v.title}"
                            v.title.isNotBlank() && seen.add(k)
                        }.take(20)
                        if (fresh.isNotEmpty()) {
                            succeeded.incrementAndGet()
                            // 全局配额要足够大：咕噜这类 jar 源搜索前要先做 ECDH 握手 + boot
                            // 认证（多两个网络往返），出结果天然靠后。配额太小会被先返回的
                            // 站点瓜分殆尽，慢站点的结果被静默丢掉（表现为搜索里永远没这站）
                            val room = 800 - emitted.get()
                            if (room > 0) {
                                val batch = fresh.take(room)
                                emitted.addAndGet(batch.size)
                                onResult(batch)
                            }
                        }
                        }
                    }
                }.awaitAll()
            }
        }
        }
        android.util.Log.i(
            "FanHuaTV",
            "搜索「$keyword」完成：已请求 ${attempted.get()}/${targets.size} 个站点，" +
                    "${succeeded.get()} 个站点有结果，共 ${emitted.get()} 条"
        )
    }

    /** JSON 类站点（MacCMS 接口）搜索：?ac=videolist&wd=关键词；XML 站点自动识别 */
    private suspend fun searchJsonSite(
        apiBase: String,
        keyword: String,
        headers: Map<String, String> = emptyMap()
    ): List<VodItem> {
        if (apiBase.isBlank() || !apiBase.startsWith("http")) return emptyList()
        val sep = if (apiBase.contains("?")) "&" else "?"
        val url = "$apiBase${sep}ac=videolist&wd=" + java.net.URLEncoder.encode(keyword, "UTF-8")
        return try {
            val resp = withContext(Dispatchers.IO) {
                if (headers.isEmpty()) api.fetchRaw(url) else api.probeUrl(url, headers)
            }
            if (!resp.isSuccessful) emptyList()
            else parseMacCmsAny(resp.body()?.string().orEmpty())?.second.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }
    /** 影片详情：从 jar Spider / PY 脚本 拉取剧集列表 */
    suspend fun loadDetail(item: VodItem): Resource<VodItem> {
        // jar 配置项（带 action，或配置中心类站点）：点击应执行动作而非进详情。
        // 万一还是进了详情页，这里也不调蜘蛛——detailContent 会再触发一次 action，弹窗双开
        if (item.source == "jar" && (item.action.isNotBlank() || matchJarSite(item)?.api == "csp_Config")) {
            return Resource.Error("这是配置项：返回列表点击它执行操作")
        }
        // jar 站点：用 JarRuntime 调用 Spider.detailContent
        val jarSite = matchJarSite(item)
        if (jarSite != null) {
            val detail = jarRuntime.detailContent(jarSite, listOf(item.id))
            // 补齐来源标识：siteKey 用配置里的中文 key、siteName 用中文站名，
            // 否则详情页会拿 csp_xxx（api）或空 key 去匹配站点表，显示内部名
            return if (detail != null) Resource.Success(
                detail.copy(siteKey = jarSite.key, siteName = jarSite.name, source = "jar")
            )
            else Resource.Error("jar 站点「${jarSite.name}」详情获取失败")
        }
        // jar 站点但没匹配到：jar 包可能还在加载。带诊断信息便于定位，
        // 绝不能掉进 PY 兜底——PY 脚本解析 jar 地址只会报"脚本未返回播放地址"
        if (item.source == "jar") {
            return Resource.Error(jarNotReadyDiag(item))
        }
        // 只有真正的 py 源才进脚本详情：json/来源丢失的片子错串进 py，
        // 会被盖上 py 标记，播放时错拿脚本解析报"parse=1 不支持"（呼噜就是这么坏的）
        val isPyItem = item.source == "py" || item.siteKey.startsWith("py_")
        if (!isPyItem) return Resource.Success(item)
        val scripts = pyRuntime.enabledScriptSites()
        if (scripts.isEmpty()) return Resource.Success(item)
        // 优先用结果自带的脚本 ID：搜索结果各站点并发返回，siteKey 是配置里的站点 key，
        // 而 enabledScriptSites 的 key 是 py_<脚本ID>，两者对不上时会错串到第一个脚本
        val target = scripts.firstOrNull { it.scriptId == item.scriptId && item.scriptId.isNotBlank() }
            ?: scripts.firstOrNull { it.key == item.siteKey }
            ?: scripts.first()
        // 脚本详情回来时 source/scriptId 已丢失（Gson 反序列化用默认值），必须补回，
        // 否则 resolvePlayUrl 认不出这是 py 源，会错拿第一个脚本去解析播放地址
        val detail = pyRuntime.detail(target.scriptId, item.id)
        return when (detail) {
            is Resource.Success -> Resource.Success(
                detail.data.copy(
                    source = "py",
                    scriptId = target.scriptId,
                    siteKey = item.siteKey.ifBlank { "py_${target.scriptId}" }
                )
            )
            else -> detail
        }
    }

    /** 三级匹配 jar 站点：key → api → name（部分配置里 key 与 api 混用） */
    private fun matchJarSite(item: VodItem): SiteConfig? {
        val k = item.siteKey
        if (k.isBlank()) return null
        return jarSites.firstOrNull { it.key == k }
            ?: jarSites.firstOrNull { it.api == k }
            ?: jarSites.firstOrNull { it.name == k }
    }

    /**
     * 执行 jar 条目自带的 action（配置中心等）：TVBox 语义里点击带 action 的条目
     * 不进详情，直接调 Spider.action，由蜘蛛弹扫码/输入框或清 Cookie。
     * 匹配不到站点返回 false，调用方给提示。
     */
    suspend fun runJarAction(item: VodItem): Boolean {
        val jarSite = matchJarSite(item) ?: return false
        // 配置中心类蜘蛛：action 字段缺失时用 vod_id 兜底（vod_id 本身就是动作码）
        val action = item.action.ifBlank {
            if (jarSite.api == "csp_Config") item.id else ""
        }
        if (action.isBlank()) return false
        jarRuntime.runAction(jarSite, action)
        return true
    }

    /** 万能判定：带 scheme:// 的地址全认（http/https/file/content/奇葩协议头都行），
     *  只有裸串（文件名/复合 id，无 ://）才丢弃——喂进播放器只会走 FileDataSource 报 IO_FILE_NOT_FOUND */
    private fun isNetworkUrl(url: String): Boolean = UrlRules.isUniversalUrl(url)

    /** jar 匹配失败的诊断信息：站点表规模 + item 的 key/source，一次定位 */
    private fun jarNotReadyDiag(item: VodItem): String {
        val k = item.siteKey.ifBlank { "空" }
        return "jar 站点尚未就绪（站点表 ${jarSites.size} 条，item key=$k，source=${item.source}），请稍后重试"
    }

    /** 探测播放地址是否真能连通；只取 1 字节，带 spider 返回的 Referer/UA */
    private suspend fun probeReachable(url: String, headers: Map<String, String>?): Boolean {
        if (!url.startsWith("http")) return false
        return try {
            val probeHeaders = headers?.toMutableMap() ?: mutableMapOf()
            probeHeaders["Range"] = "bytes=0-0"
            probeHeaders.putIfAbsent("User-Agent", "FanHuaTV/1.0")
            val resp = withTimeoutOrNull(5_000) { api.probeUrl(url, probeHeaders) }
            val ok = resp?.let { it.code() in 200..299 } == true
            resp?.body()?.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    suspend fun resolvePlayUrl(item: VodItem, episodeUrl: String, playFlag: String = ""): Resource<String> {
        val url = episodeUrl.ifBlank { item.playUrl }
        // 弹幕/海报/歌词地址随播放解析刷新：先清掉上一部片留下的，jar 源解析成功后再补
        com.fanhuatv.player.LivePlayBus.pendingDanmaku = ""
        com.fanhuatv.player.LivePlayBus.pendingArtwork = ""
        com.fanhuatv.player.LivePlayBus.pendingLyric = ""
        // 入口全量诊断：source/siteKey/api/id/集数地址/线路名一次打全，
        // 无论后面走哪个分支，logcat 都能还原当时到底拿到的什么数据
        android.util.Log.i("FanHuaTV", "resolvePlayUrl 入口：source=${item.source} siteKey=${item.siteKey} api=${matchJarSite(item)?.api ?: "-"} id=${item.id.take(60)} url=${url.take(80)} flag=${playFlag.ifBlank { "(空)" }} playUrl=${item.playUrl.take(200)}")

        // jar 站点：直接视频地址直接返回，否则调用 Spider.playerContent 解析。
        // 除了认 source 标记，还要按站点表兜一道：jar 站点的 item 一旦在某个环节
        // 丢了 source（反序列化默认值），会错掉进脚本引擎报"脚本错误"，这里按
        // siteKey 能匹配上 jar 站点表就强制走 jar 链路
        val jarSiteMatch = matchJarSite(item)
        // 只要站点表匹配得上就强制走 jar 链路：py 站点的 siteKey 是 py_<脚本ID>，
        // 不会撞 jar 站点表；jar 站点的 item 即使被错标成 py 也得回 jar 引擎（呼噜场景）
        if (item.source == "jar" || jarSiteMatch != null) {
            val jarSite = jarSiteMatch
                ?: return Resource.Error(jarNotReadyDiag(item))
            // 不同 jar 的 playerContent 需要的 id 格式不一样：
            // AppYQK 要 "名称|集数|..."、App99 要 "x@y@z@w"，有的又要完整 vod_play_url。
            // 移植 TVBox：playerContent 的 parse=1/jx=1 不再直接判死，
            // 交给 Source.fetch + WebView 嗅探提取真实视频流
            // vod_play_from 与 vod_play_url 一样是 $$$ 分隔的多源格式，
            // flag 必须取当前播放源自己的名字，整串传过去蜘蛛认不出
            val rawFirst = item.playUrl.substringBefore('#')
            // 优先用这一集自带的线路名（详情页切了线路）；没带时回退第一条线路
            val flagFirst = playFlag.ifBlank { item.playFrom.substringBefore("$$$").trim() }
            val flagList = item.playFrom.split("$$$").map { it.trim() }.filter { it.isNotBlank() }
            val resolved = mutableListOf<Pair<String, Map<String, String>?>>()
            var sniffCount = 0
            var sniffFailed = false
            // 蜘蛛返回了半截地址/token/本地路径等非网络地址：Media3 会走 FileDataSource
            // 打开失败并报 ERROR_CODE_IO_FILE_NOT_FOUND，这里提前拦下并把真实形态带进提示
            var badShape = false
            var lastRawUrl = ""

            /** 调一次蜘蛛 playerContent 并处理结果；拿到可播地址返回 true（已入 resolved） */
            suspend fun trySpider(id: String): Boolean {
                if (id.isBlank()) return false
                val parsed = jarRuntime.playerContent(jarSite, flagFirst, id, flagList)
                    ?: return false
                // jar 返回了弹幕/歌手海报/歌词：记到播放总线上，播放器页起播后拉取渲染
                parsed.danmaku.takeIf { it.isNotBlank() }?.let {
                    com.fanhuatv.player.LivePlayBus.pendingDanmaku = it
                }
                parsed.artwork.takeIf { it.isNotBlank() }?.let {
                    com.fanhuatv.player.LivePlayBus.pendingArtwork = it
                }
                parsed.lyric.takeIf { it.isNotBlank() }?.let {
                    com.fanhuatv.player.LivePlayBus.pendingLyric = it
                }
                val rawPlay = parsed.url.takeIf { it.isNotBlank() } ?: return false
                lastRawUrl = rawPlay
                // TVBox 惯例：playerContent 可能返回 名称$地址 形态，$ 后才是真实地址
                // （App99 类站点 type=0 线路原样带前缀返回，不剥掉播放器认不了）
                val playUrl = rawPlay.substringAfterLast("$").takeIf { it.startsWith("http") } ?: rawPlay
                if (resolved.any { it.first == playUrl }) return false
                // 交给播放器前只拦一种情况：不带 :// 的裸串。带协议头的全认
                // （http/https/file/content 以及各站奇葩协议头），丢给播放器自己识别；
                // 裸文件名/复合 id 交过去只会走 FileDataSource 报 IO_FILE_NOT_FOUND。
                if (!isNetworkUrl(playUrl)) {
                    android.util.Log.w("FanHuaTV", "jar 站点「${jarSite.name}」(${jarSite.api}) playerContent 返回无协议头地址，拒绝直接播放：flag=$flagFirst url=${playUrl.take(160)}")
                    badShape = true
                    return false
                }

                // parse=1/jx=1 的片源：先走配置里的 jx 解析接口拿真实地址（TVBox 标准链路），
                // 接口没配或没解出直链，再回退网页嗅探——两条路都试，不互相顶掉。
                // 注意：地址含 m3u8/m3u 字样（如 .../getM3u8?time=...）就是视频流本身，
                // 只是没有标准后缀——绝不能进嗅探，嗅探请求会污染一次性的时效 token，
                // 播放器拿分片直接被 302 踢走（热播站就是这么被误杀的）
                val looksLikeStream = isDirectVideoUrl(playUrl) ||
                        playUrl.contains("m3u8", ignoreCase = true) ||
                        playUrl.contains("m3u", ignoreCase = true)
                // 网盘线路（夸克/UC/迅雷/天翼/115/123/阿里等，按线路名通用识别）：
                // 蜘蛛 playerContent 返回的就是直链，不走 jx 接口也不进网页嗅探——
                // 嗅探只会污染一次性 token，把能直连播放的网盘线路误报成"线路失效"
                val isPanLine = isPanFlag(flagFirst)
                val needWebSniff = parsed.needParse && !looksLikeStream && !isPanLine
                if (needWebSniff) {
                    val jxHit = withTimeoutOrNull(10_000) {
                        com.fanhuatv.player.JxResolver.resolve(playUrl, flagFirst, currentParses, currentFlags)
                    }
                    if (jxHit != null && isDirectVideoUrl(jxHit.first)) {
                        resolved.add(jxHit.first to (jxHit.second ?: parsed.headers))
                        jxHit.second?.let { com.fanhuatv.player.PlayHeaderStore.put(jxHit.first, it) }
                        return true
                    }
                }
                val doSniff = needWebSniff && sniffCount < 2
                // 直链类地址（含 .m3u8 等）绝不能进 WebView——嗅探请求会污染
                // 一次性的解析会话/时间戳 token，导致播放器拿到的分片被 302 踢走
                if (doSniff) sniffCount++
                val fetched = withTimeoutOrNull(18_000) {
                    PlaySourceResolver.fetch(context, playUrl, parsed.headers, doSniff)
                }
                val finalUrl = fetched?.first ?: playUrl
                val finalHeaders = fetched?.second ?: parsed.headers
                // 嗅探没抓到真实流：不把网页原样塞给播放器，标记后统一报提示。
                // 但地址本身像视频流（含 m3u8 等特征）的例外——那就是最终地址，
                // 播放器自己能播，拒绝它等于把能放的片源掐死
                if (needWebSniff && finalUrl == playUrl && !looksLikeStream) {
                    sniffFailed = true
                    return false
                }

                resolved.add(finalUrl to finalHeaders)
                finalHeaders?.let { com.fanhuatv.player.PlayHeaderStore.put(finalUrl, it) }
                android.util.Log.i("FanHuaTV", "jar 站点「${jarSite.name}」(${jarSite.api}) 最终采用地址：flag=$flagFirst needSniff=$needWebSniff url=${finalUrl.take(160)}")
                // 解析完成：只要带协议头就算成功，原样交给播放器（自带内容探测与换头重试）
                return isNetworkUrl(finalUrl)
            }

            /** 从剧集复合 id（解析串|直链|UA|线路|集数）里拆直链；蜘蛛侧上游解析接口
             *  下线是 jar 站点集体失效的常态，id 里自带的直链不依赖任何上游解析 */
            fun extractDirectFromId(): Resource.Success<String>? {
                for (candidate in listOf(url, rawFirst)) {
                    val parts = candidate.split('|')
                    // 必须是 http(s) 开头的真直链才采用：网盘分享页/带 .mp4 字样的网页
                    // 也会命中 isDirectVideoUrl，直接播就是播放器报 IO_FILE_NOT_FOUND
                    val idx = parts.indexOfFirst { isNetworkUrl(it) && isDirectVideoUrl(it) }
                    if (idx < 0) continue
                    var directUrl = parts[idx]
                    // App99 类站点剧集 id 是 名称$地址@线路@片名@集数，@ 后面是参数不是地址；
                    // 截掉后仍是直链才采用，地址本身含 @（userinfo 形态）时前段不是直链，自然回退原串
                    directUrl.substringBefore("@").takeIf { isDirectVideoUrl(it) }?.let { directUrl = it }
                    // TVBox 惯例：id 可能带 名称$ 前缀，$ 后才是真实地址
                    directUrl.substringAfterLast("$").takeIf { it.startsWith("http") }?.let { directUrl = it }
                    // 紧邻直链的 Mozilla 开头片段按播放 UA 自然匹配（蜘蛛把 UA 拼在同一串里）
                    val ua = parts.getOrNull(idx + 1)?.trim()?.takeIf { it.startsWith("Mozilla") }
                    val headers = ua?.let { mapOf("User-Agent" to it) }
                    headers?.let { com.fanhuatv.player.PlayHeaderStore.put(directUrl, it) }
                    android.util.Log.i("FanHuaTV", "jar 站点「${jarSite.name}」剧集 id 自带直链，直接播放")
                    return Resource.Success(directUrl)
                }
                return null
            }

            // ① 直链优先：剧集复合 id（解析串|直链|UA|线路|集数）里自带直链的，
            //    直接拆出来播，不走蜘蛛的上游解析——别的壳子就是这么播通的
            extractDirectFromId()?.let { return it }
            // ② id 里没直链的站点：走蜘蛛 playerContent 解析，只试当前这一集自己的 id，
            //    不轮换候选、不兜底——播不出来就报错
            if (trySpider(url)) return Resource.Success(resolved.last().first)
            if (isNetworkUrl(url) && isDirectVideoUrl(url)) return Resource.Success(url)
            android.util.Log.w("FanHuaTV", "jar 站点「${jarSite.name}」(${jarSite.api}) 播放失败：episodeUrl=${url.take(80)} rawFirst=${rawFirst.take(80)} flag=$flagFirst needSniff=$sniffFailed badShape=$badShape lastRaw=${lastRawUrl.take(80)}")
            if (badShape) return Resource.Error("jar 站点「${jarSite.name}」返回的播放地址不是可识别的网络链接（内容：${lastRawUrl.take(60).ifBlank { "空" }}），请换线路（片源）或换站点")
            if (sniffFailed) return Resource.Error("jar 站点「${jarSite.name}」该线路嗅探不到真实播放地址（parse=1），线路可能已失效，请换线路（片源）或换站点")
            return Resource.Error("jar 站点「${jarSite.name}」无法解析播放地址（上游解析接口失联，剧集数据中也无可用直链）")
        }

        if (item.source == "py" && item.scriptId.isNotBlank()) {
            val pyRes = pyRuntime.resolvePlay(item.scriptId, url, item.displayTitle)
            val playUrl = (pyRes as? Resource.Success)?.data ?: return pyRes
            // py 脚本返回直链直接播；网页嗅探地址（parse=1/jx=1 或非直链）与 jar 链路同款：
            // ① 配置里的 jx 解析接口 ② WebView 嗅探，两条路都试，不行才报错
            if (isNetworkUrl(playUrl) && isDirectVideoUrl(playUrl)) return pyRes
            val flagFirst = playFlag.ifBlank { item.playFrom.substringBefore("$$$").trim() }
            val jxHit = withTimeoutOrNull(10_000) {
                com.fanhuatv.player.JxResolver.resolve(playUrl, flagFirst, currentParses, currentFlags)
            }
            if (jxHit != null && isDirectVideoUrl(jxHit.first)) {
                jxHit.second?.let { com.fanhuatv.player.PlayHeaderStore.put(jxHit.first, it) }
                return Resource.Success(jxHit.first)
            }
            val fetched = withTimeoutOrNull(18_000) {
                PlaySourceResolver.fetch(context, playUrl, null, true)
            }
            if (fetched != null && fetched.first != playUrl) {
                fetched.second?.let { com.fanhuatv.player.PlayHeaderStore.put(fetched.first, it) }
                return Resource.Success(fetched.first)
            }
            return Resource.Error("py 源「${item.scriptId}」该集返回网页地址，嗅探不到真实播放地址，请换线路（片源）或换站点")
        }
        // 通用自然匹配：带协议头（://）的地址原样交给播放器自动识别，不硬拿脚本兜底、
        // 不注入假链接、不伪造字段——播放器自带内容探测和换头重试。
        // 只拦裸文件名/网盘复合 id（无 ://），交过去只会喂进 FileDataSource 报
        // IO_FILE_NOT_FOUND，这里带 source/siteKey 拦下，便于一次截图定位是哪个源
        if (url.isNotBlank()) {
            if (!isNetworkUrl(url)) {
                android.util.Log.w("FanHuaTV", "未识别播放地址：source=${item.source} siteKey=${item.siteKey} id=${item.id.take(80)} url=${url.take(120)}")
                return Resource.Error(
                    "未识别的播放地址（source=${item.source.ifBlank { "未知" }} siteKey=${item.siteKey.ifBlank { "空" }}）：${url.take(50)}"
                )
            }
            return Resource.Success(url)
        }
        // 走到这里说明这条片源既没有 source 标记、也没匹配上任何 jar 站点表，
        // 也没有可播地址。带诊断信息一起报，避免只看到一句无头无尾的“未取到”
        android.util.Log.w("FanHuaTV", "播放失败（非 jar/py 源且无地址）：source=${item.source} siteKey=${item.siteKey} id=${item.id.take(60)} title=${item.title} playUrl=${item.playUrl.take(60)}")
        return Resource.Error("未取到播放地址（该影片来自「${item.source.ifBlank { "未知" }}」源，非咕噜等 jar 站点；站点表 ${jarSites.size} 条，key=${item.siteKey.ifBlank { "空" }}）。请从咕噜分类进入，或换站点/换一集")
    }

    suspend fun loadLive(url: String, format: String): Resource<List<LiveChannel>> {
        if (url.isBlank()) return Resource.Error("未配置直播源")
        return try {
            val response = api.fetchLiveText(url)
            val body = response.body()?.string().orEmpty()
            if (!response.isSuccessful) return Resource.Error("HTTP ${response.code()}")
            // auto：订阅 lives 里的源 txt/m3u 都有，按内容识别，别写死格式
            val fmt = if (format.equals("auto", true)) {
                if (body.trimStart().startsWith("#EXTM3U")) "m3u" else "txt"
            } else format
            Resource.Success(parser.parseLive(body, fmt))
        } catch (e: Exception) {
            Resource.Error(e.message ?: "直播源加载失败")
        }
    }

    suspend fun saveHistory(item: VodItem, playUrl: String, progress: Long, duration: Long, episode: String = "", episodeId: String = "") {
        val existing = historyDao.find(item.id)
        if (existing == null) {
            historyDao.insert(
                HistoryEntity(
                    vodId = item.id,
                    title = item.displayTitle,
                    cover = item.coverUrl,
                    siteKey = item.siteKey,
                    source = item.source,
                    scriptId = item.scriptId,
                    playUrl = playUrl,
                    progress = progress,
                    duration = duration,
                    episode = episode,
                    episodeId = episodeId
                )
            )
        } else {
            historyDao.updateProgress(item.id, progress, duration, episode, episodeId, System.currentTimeMillis())
        }
    }

    suspend fun toggleFavorite(item: VodItem) {
        val exists = favoriteDao.find(item.id)
        if (exists != null) favoriteDao.delete(item.id)
        else favoriteDao.insert(
            FavoriteEntity(
                vodId = item.id,
                title = item.displayTitle,
                cover = item.coverUrl,
                siteKey = item.siteKey,
                source = item.source,
                scriptId = item.scriptId
            )
        )
    }

    suspend fun isFavorite(vodId: String): Boolean = favoriteDao.find(vodId) != null

    suspend fun clearHistory() = historyDao.clear()
    suspend fun clearFavorites() = favoriteDao.clear()
    suspend fun clearSearchHistory() = searchDao.clear()
}
