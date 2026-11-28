package com.fanhuatv.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.ConfigSource
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.storage.PlaybackSettings
import com.fanhuatv.core.storage.SettingsDataStore
import com.fanhuatv.core.storage.UiSettings
import com.fanhuatv.core.util.Resource
import com.fanhuatv.python.AiSourceRepair
import com.fanhuatv.python.PyRuntime
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 设置页全局 ViewModel：接口配置、直播源、播放/界面设置、PY 脚本、备份恢复、缓存。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore,
    val repository: MainRepository,
    val pyRuntime: PyRuntime
) : ViewModel() {

    val configSources: StateFlow<List<ConfigSource>> = settings.configSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val activeSourceId: StateFlow<String> = settings.activeSourceId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val liveUrl: StateFlow<String> = settings.liveUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val playback: StateFlow<PlaybackSettings> = settings.playback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackSettings())
    val ui: StateFlow<UiSettings> = settings.ui
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiSettings())
    val scriptPriority: StateFlow<Boolean> = settings.scriptPriority
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _testResult = MutableStateFlow<String>("")
    val testResult: StateFlow<String> = _testResult.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    fun consumeToast() { _toast.value = null }

    // ===== 点播接口多源管理 =====
    fun addSource(url: String, name: String = "") {
        if (url.isBlank()) { _toast.value = "地址不能为空"; return }
        viewModelScope.launch {
            val current = settings.configSources.first().toMutableList()
            val source = ConfigSource(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { "线路 ${current.size + 1}" },
                url = url.trim()
            )
            current.add(source)
            settings.setConfigSources(current)
            if (current.size == 1) settings.setActiveSource(source.id)
            _toast.value = "已添加接口"
        }
    }

    fun updateSource(source: ConfigSource) {
        viewModelScope.launch {
            val current = settings.configSources.first().map { if (it.id == source.id) source else it }
            settings.setConfigSources(current)
        }
    }

    fun removeSource(id: String) {
        viewModelScope.launch {
            val current = settings.configSources.first().filterNot { it.id == id }
            settings.setConfigSources(current)
            if (settings.activeSourceId.first() == id) {
                settings.setActiveSource(current.firstOrNull()?.id ?: "")
            }
        }
    }

    /**
     * 确认并应用接口：把输入框地址设为当前线路（已存在则复用），真正请求并解析一次，
     * 成功返回 true（调用方据此刷新首页），失败返回 false 并给出明确提示。
     * 输入框为空时，直接重新加载当前激活线路。
     */
    suspend fun confirmAndActivate(url: String): Boolean {
        val target = url.trim()
        if (target.isNotBlank()) {
            val current = settings.configSources.first().toMutableList()
            var source = current.firstOrNull { it.url == target }
            if (source == null) {
                source = ConfigSource(
                    id = UUID.randomUUID().toString(),
                    name = "线路 ${current.size + 1}",
                    url = target
                )
                current.add(source)
                settings.setConfigSources(current)
            }
            settings.setActiveSource(source.id)
        }
        val result = repository.loadConfig()
        _toast.value = when (result) {
            is Resource.Success -> "接口已生效：${result.data.sites.size} 站点 / ${result.data.categories.size} 分类 / ${result.data.homeItems.size} 影片"
            is Resource.Error -> "加载失败：${result.message}"
            else -> "正在加载"
        }
        return result is Resource.Success
    }

    fun setActiveSource(id: String) {
        viewModelScope.launch { settings.setActiveSource(id) }
    }

    fun testSource(source: ConfigSource) {
        viewModelScope.launch {
            when (val result = repository.testSource(source)) {
                is Resource.Success -> _testResult.value = result.data
                is Resource.Error -> _testResult.value = "失败：${result.message}"
                else -> {}
            }
        }
    }

    // ===== 直播源 =====
    fun setLive(url: String, template: String) {
        viewModelScope.launch {
            settings.setLive(url.trim(), template)
            _toast.value = "直播源已保存"
        }
    }

    // ===== 播放设置 =====
    fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) {
        viewModelScope.launch {
            settings.setPlayback(transform(settings.playback.first()))
        }
    }

    // ===== 界面设置 =====
    fun updateUi(transform: (UiSettings) -> UiSettings) {
        viewModelScope.launch {
            settings.setUi(transform(settings.ui.first()))
        }
    }

    fun setScriptPriority(enabled: Boolean) {
        viewModelScope.launch { settings.setScriptPriority(enabled) }
    }

    // ===== PY 脚本管理包装（协程） =====
    fun setScriptEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { pyRuntime.setEnabled(id, enabled) }
    }
    fun deleteScript(id: String) {
        viewModelScope.launch { pyRuntime.delete(id) }
    }
    fun reimportScript(id: String, content: String, onDone: () -> Unit) {
        viewModelScope.launch {
            pyRuntime.reimport(id, content)
            onDone()
        }
    }

    // ===== 源热更新 =====
    val hotUpdateUrl: StateFlow<String> = settings.hotUpdateUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val hotUpdateAuto: StateFlow<Boolean> = settings.hotUpdateAuto
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 保存热更新配置（地址 + 自动拉取开关） */
    fun saveHotUpdateConfig(url: String, auto: Boolean) {
        viewModelScope.launch {
            settings.setHotUpdate(url.trim(), auto)
            _toast.value = if (url.trim().isBlank()) "热更新配置已清空" else "热更新配置已保存"
        }
    }

    // ===== TMDB 配置（影视海报墙） =====
    val tmdbKey: StateFlow<String> = settings.tmdbKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun saveTmdbKey(key: String) {
        viewModelScope.launch {
            settings.setTmdbKey(key)
            _toast.value = if (key.isBlank()) "TMDB Key 已清空" else "TMDB Key 已保存"
        }
    }

    /** 立即拉取最新脚本并重新注册源；结果通过 toast 反馈 */
    fun runHotUpdate() {
        viewModelScope.launch {
            val url = settings.hotUpdateUrl.first()
            if (url.isBlank()) {
                _toast.value = "请先填写 PY 脚本地址"
                return@launch
            }
            _toast.value = "正在拉取最新脚本…"
            val result = pyRuntime.hotUpdate(url)
            _toast.value = when (result) {
                is Resource.Success -> "热更新完成：${result.data.name}"
                is Resource.Error -> "热更新失败：${result.message}"
                else -> "热更新失败"
            }
        }
    }

    // ===== AI 修源 =====
    val aiUrl: StateFlow<String> = settings.aiUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val aiKey: StateFlow<String> = settings.aiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val aiModel: StateFlow<String> = settings.aiModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    /** 保存 AI 接口配置（地址 / 密钥 / 模型名） */
    fun saveAiConfig(url: String, key: String, model: String) {
        viewModelScope.launch {
            settings.setAiConfig(url.trim(), key.trim(), model.trim())
            _toast.value = "AI 修源配置已保存"
        }
    }

    /**
     * 用 AI 修复指定脚本：取脚本内容 + 报错文本发给配置好的接口，
     * 结果通过 onResult(fixedScript, message) 回调；fixedScript 为空表示失败。
     */
    fun aiRepair(scriptId: String, errorText: String, onResult: (String, String) -> Unit) {
        viewModelScope.launch {
            val url = settings.aiUrl.first()
            val key = settings.aiKey.first()
            val model = settings.aiModel.first()
            if (url.isBlank() || key.isBlank()) {
                onResult("", "请先在「我的」页配置 AI 接口地址和密钥")
                return@launch
            }
            val script = pyRuntime.scriptContent(scriptId)
            if (script.isBlank()) {
                onResult("", "读取脚本内容失败")
                return@launch
            }
            val r = AiSourceRepair.repair(url, key, model, script, errorText)
            onResult(r.fixedScript ?: "", r.message)
        }
    }

    // ===== PY 脚本导入（三种方式，覆盖手机/电脑/TV Box 目录） =====

    /** Android 11+ 是否需要「所有文件访问权限」 */
    fun needsAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

    /** 跳转系统设置页开启所有文件访问（TV Box 读取本地目录必备） */
    fun allFilesAccessIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else null

    /** 方式一：SAF 文件选择器（带持久化权限，重启后仍可读） */
    fun importScriptFromUri(uri: Uri, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            // 持久化 URI 授权，避免部分电视盒子重启后失效
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val content = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (e: Exception) {
                _toast.value = "读取脚本失败：${e.message ?: "无权限访问该文件"}"; onDone?.invoke(); return@launch
            }
            if (content.isNullOrBlank()) {
                _toast.value = "读取脚本失败：文件为空"; onDone?.invoke(); return@launch
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".py") ?: "script"
            doImport(name, content)
            onDone?.invoke()
        }
    }

    /** 方式二：直接粘贴脚本内容（无需任何文件权限，最稳妥的兜底） */
    fun importScriptFromText(name: String, content: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (content.isBlank()) {
                _toast.value = "脚本内容不能为空"; onDone?.invoke(); return@launch
            }
            doImport(name.ifBlank { "pasted_script" }, content)
            onDone?.invoke()
        }
    }

    /** 方式三：按绝对路径读取（需「所有文件访问权限」，适合 TV Box / U 盘目录） */
    fun importScriptFromPath(path: String, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            if (path.isBlank()) {
                _toast.value = "路径不能为空"; onDone?.invoke(); return@launch
            }
            val content = try {
                java.io.File(path).takeIf { it.exists() && it.isFile }?.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                _toast.value = "读取失败：${e.message ?: "无权限"}"; onDone?.invoke(); return@launch
            }
            if (content.isNullOrBlank()) {
                _toast.value = "读取失败：文件不存在或无权限（请先在设置页开启所有文件访问）"
                onDone?.invoke(); return@launch
            }
            val name = java.io.File(path).nameWithoutExtension.ifBlank { "script" }
            doImport(name, content)
            onDone?.invoke()
        }
    }

    /** 批量导入：逐条走同一套解析/落盘/注册流程，返回成功条数 */
    suspend fun importScriptsBatch(items: List<Pair<String, String>>): Int {
        var ok = 0
        val errors = mutableListOf<String>()
        items.forEach { (name, content) ->
            when (val r = pyRuntime.importScript(name, content)) {
                is Resource.Success -> ok++
                is Resource.Error -> errors.add("$name：${r.message}")
                else -> Unit
            }
        }
        _toast.value = if (errors.isEmpty())
            "批量导入完成：成功 $ok / ${items.size}"
        else
            "成功 $ok / ${items.size}；失败：${errors.take(2).joinToString("；")}"
        return ok
    }

    private suspend fun doImport(name: String, content: String) {
        val result = pyRuntime.importScript(name, content)
        _toast.value = when (result) {
            is Resource.Success -> "已导入：${result.data.name}"
            is Resource.Error -> result.message
            else -> "导入失败"
        }
    }

    // ===== 缓存管理 =====
    fun clearCache() {
        viewModelScope.launch {
            val dirs = listOf(context.cacheDir, context.externalCacheDir)
            var count = 0
            dirs.forEach { dir ->
                dir?.listFiles()?.forEach { file ->
                    if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) count++
                }
            }
            _toast.value = "已清理 $count 个缓存文件"
        }
    }

    fun cacheSizeText(): String {
        val bytes = (context.cacheDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum())
        return if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "${bytes / (1024 * 1024)} MB"
    }

    // ===== 备份 / 恢复 =====
    fun exportConfig(uri: Uri) {
        viewModelScope.launch {
            val data = com.google.gson.JsonObject().apply {
                add("configSources", com.google.gson.Gson().toJsonTree(settings.configSources.first()))
                add("playback", com.google.gson.Gson().toJsonTree(settings.playback.first()))
                add("ui", com.google.gson.Gson().toJsonTree(settings.ui.first()))
                addProperty("liveUrl", settings.liveUrl.first())
            }
            try {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(data.toString().toByteArray(Charsets.UTF_8))
                }
                _toast.value = "配置已导出"
            } catch (e: Exception) { _toast.value = "导出失败：${e.message}" }
        }
    }

    fun importConfig(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                val obj = com.google.gson.JsonParser.parseString(text).asJsonObject
                val gson = com.google.gson.Gson()
                obj.get("configSources")?.let {
                    settings.setConfigSources(gson.fromJson(it, Array<ConfigSource>::class.java).toList())
                }
                obj.get("playback")?.let { settings.setPlayback(gson.fromJson(it, PlaybackSettings::class.java)) }
                obj.get("ui")?.let { settings.setUi(gson.fromJson(it, UiSettings::class.java)) }
                obj.get("liveUrl")?.let { settings.setLive(it.asString, "") }
                _toast.value = "配置已恢复"
            } catch (e: Exception) { _toast.value = "恢复失败：${e.message}" }
        }
    }
}
