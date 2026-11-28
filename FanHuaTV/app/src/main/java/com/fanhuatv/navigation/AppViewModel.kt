package com.fanhuatv.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.DefaultConfig
import com.fanhuatv.core.model.SiteConfig
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.storage.SettingsDataStore
import com.fanhuatv.core.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 全局共享状态：当前配置只在启动时加载一次，四个 Tab 共用。 */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: MainRepository,
    private val settings: SettingsDataStore,
    private val pyRuntime: com.fanhuatv.python.PyRuntime
) : ViewModel() {

    private val _config = MutableStateFlow<AppConfig?>(null)
    val config: StateFlow<AppConfig?> = _config.asStateFlow()

    /** 影视海报墙用：按分类 id 分页拉影片，代理仓库的同名能力 */
    suspend fun loadCategoryPage(tid: String, pg: String, scriptId: String): Resource<List<com.fanhuatv.core.model.VodItem>> =
        repository.loadCategory(tid, pg, scriptId)

    /** 影视海报墙用：拿 TMDB 片名去全部订阅源里搜能播的版本；搜索结束后回调最优结果（片名完全匹配优先） */
    fun matchLocalSource(title: String, onDone: (com.fanhuatv.core.model.VodItem?) -> Unit) {
        viewModelScope.launch {
            var best: com.fanhuatv.core.model.VodItem? = null
            runCatching {
                repository.search(title, "", recordHistory = false) { batch ->
                    if (best == null) {
                        best = batch.firstOrNull { it.title == title || it.displayTitle == title }
                            ?: batch.firstOrNull()
                    } else if (best?.title != title && best?.displayTitle != title) {
                        // 已有近似结果时，等完全匹配的来了再替换
                        val exact = batch.firstOrNull { it.title == title || it.displayTitle == title }
                        if (exact != null) best = exact
                    }
                }
            }
            onDone(best)
        }
    }

    private val _status = MutableStateFlow<Resource<AppConfig>>(Resource.Loading)
    val status: StateFlow<Resource<AppConfig>> = _status.asStateFlow()

    /** 非致命提示：接口加载失败时展示，但不挡住首页内置内容 */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** 数据源诊断：告诉用户首页数据到底来自 PY 脚本 / JSON 接口 / 内置兜底 */
    private val _diagnostic = MutableStateFlow("尚未加载")
    val diagnostic: StateFlow<String> = _diagnostic.asStateFlow()

    /** 是否在用内置兜底内容（true 时首页应显式提示，避免"看起来没变化"） */
    private val _usingFallback = MutableStateFlow(false)
    val usingFallback: StateFlow<Boolean> = _usingFallback.asStateFlow()

    /** 当前用户选中的站源标识，空串=没手动选，按配置自然走 */
    private val _homeSiteId = MutableStateFlow("")
    val homeSiteId: StateFlow<String> = _homeSiteId.asStateFlow()

    /** 站源切换列表：直接读已导入的启用脚本，独立于异步 enrich 后的 config */
    private val _scriptSites = MutableStateFlow<List<SiteConfig>>(emptyList())
    val scriptSites: StateFlow<List<SiteConfig>> = _scriptSites.asStateFlow()

    /** 智能分类开关：开启后首页站源切换按 JAR内站点/PY站点/音乐/短剧 分组渲染 */
    val smartCategory: StateFlow<Boolean> = settings.ui
        .map { it.smartCategory }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 界面设置全集：底栏形态/自动收起、首页横幅/海报密度/继续观看等由各页面自行取用 */
    val uiSettings: StateFlow<com.fanhuatv.core.storage.UiSettings> = settings.ui
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.fanhuatv.core.storage.UiSettings())

    /** 继续观看：有进度且没看完的历史（最多取最近 20 部），首页一栏直接点回详情续播 */
    val continueWatching: StateFlow<List<com.fanhuatv.core.storage.entity.HistoryEntity>> = repository.history
        .map { list ->
            list.filter { it.progress > 0L && (it.duration <= 0L || it.progress < it.duration) }
                .take(20)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        load()
        scheduleAutoHotUpdate()
    }

    /** 启动自动热更新：开了开关且填了地址时，后台拉最新脚本，成功后重新加载配置注册源 */
    private fun scheduleAutoHotUpdate() {
        viewModelScope.launch {
            runCatching {
                val auto = settings.hotUpdateAuto.first()
                val url = settings.hotUpdateUrl.first()
                if (!auto || url.isBlank()) return@launch
                val result = pyRuntime.hotUpdate(url)
                if (result is Resource.Success) load()
            }
        }
    }

    /** 切换首页站源：记住用户点的那个站点并重新加载 */
    fun switchHomeSite(scriptId: String) {
        viewModelScope.launch {
            repository.setHomeSite(scriptId)
            _homeSiteId.value = scriptId
            _scriptSites.value = repository.enabledPySites()
            load()
        }
    }

    fun load() {
        viewModelScope.launch {
            _homeSiteId.value = repository.currentHomeSite()
            _status.value = Resource.Loading
            _notice.value = null
            _scriptSites.value = repository.enabledPySites()
            homeJob?.cancel()
            when (val result = repository.loadConfig()) {
                is Resource.Success -> {
                    val data = result.data
                    // 配置解析完立刻出界面，绝不让用户对着转圈等脚本下载
                    _config.value = data
                    _status.value = Resource.Success(data)
                    _usingFallback.value = true
                    _diagnostic.value = ""
                    // 有 jar 站点（配置中心）但缺「所有文件访问」权限时提示
                    checkStorageAccess(data)

                    // 脚本下载 / 首页探测放到独立后台任务，有总超时兜底
                    homeJob = launch {
                        val enriched = repository.enrichHomeDataAsync(data)
                        val hasRemote = enriched.categories.isNotEmpty() || enriched.homeItems.isNotEmpty()
                        // 接口/脚本没拿到数据时用内置豆瓣热播兜底，保证"为你推荐"始终有卡片
                        _config.value = if (hasRemote) enriched else DefaultConfig.merged(enriched)
                        _usingFallback.value = !hasRemote
                        _diagnostic.value = if (hasRemote) repository.lastDiagnostic
                            else "未取到脚本/接口数据，当前显示内置热播（${repository.lastDiagnostic}）"
                    }
                }
                is Resource.Error -> {
                    // 接口失败也先用内置内容秒开，再后台拉真实数据（dbapi / 已导入 PY 脚本）
                    val fallback = AppConfig()
                    _config.value = fallback
                    _status.value = Resource.Success(fallback)
                    _usingFallback.value = true
                    _notice.value = result.message
                    _diagnostic.value = "接口加载失败，正在尝试脚本/dbapi：${result.message}"

                    homeJob = launch {
                        val enriched = repository.enrichHomeDataAsync(fallback)
                        val hasRemote = enriched.categories.isNotEmpty() || enriched.homeItems.isNotEmpty()
                        _config.value = if (hasRemote) enriched else DefaultConfig.merged(enriched)
                        _usingFallback.value = !hasRemote
                        _diagnostic.value = if (hasRemote) repository.lastDiagnostic
                            else "未取到脚本/接口数据，当前显示内置热播（${repository.lastDiagnostic}）"
                    }
                }
                Resource.Loading -> Unit
            }
        }
    }

    private var homeJob: Job? = null

    fun consumeNotice() { _notice.value = null }

    // ===== jar 条目 action（配置中心等）：点击执行动作而非进详情 =====

    /** action 执行结果提示，界面收到的弹窗由蜘蛛自己负责 */
    private val _actionToast = MutableStateFlow("")
    val actionToast: StateFlow<String> = _actionToast.asStateFlow()

    fun runJarAction(item: com.fanhuatv.core.model.VodItem) {
        viewModelScope.launch {
            val ok = repository.runJarAction(item)
            _actionToast.value = if (ok) "已执行「${item.displayTitle}」，按弹窗提示操作"
            else "配置操作执行失败：站点未就绪，稍后再试"
        }
    }

    fun consumeActionToast() { _actionToast.value = "" }

    // ===== 所有文件访问权限：jar 配置中心读 /sdcard/TVBox 必备 =====

    /** 配置里有 jar 站点但未授予「所有文件访问」时提示一次，用户授权或忽略后不再弹 */
    private val _needStorageAccess = MutableStateFlow(false)
    val needStorageAccess: StateFlow<Boolean> = _needStorageAccess.asStateFlow()

    /** 配置加载后检查：有 jar 站点（配置中心靠它读本地网盘配置）且缺权限才提示 */
    fun checkStorageAccess(config: AppConfig?) {
        val hasJarSite = config?.sites?.any { it.source == "jar" && it.enabled } == true
        val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager()
        _needStorageAccess.value = hasJarSite && !granted
    }

    fun dismissStorageAccess() { _needStorageAccess.value = false }
}
