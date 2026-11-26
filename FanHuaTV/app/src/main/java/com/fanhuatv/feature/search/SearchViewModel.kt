package com.fanhuatv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.storage.SettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 搜索 ViewModel：实时聚合模式。
 * 输入剧名 → 并发请求各站点 → 每个站点返回立即追加到结果列表（实时出结果）→
 * 全部完成后停止。有则展示，无则忽略。
 *
 * 搜索跑在独立作用域：个别站点脚本挂死时底层协程可能迟迟不结束，
 * UI 状态由看门狗兜底（最多 60 秒强制收尾），界面永远不会卡在"正在搜索"。
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MainRepository,
    private val settings: SettingsDataStore
) : ViewModel() {

    /** 搜索工作作用域：与 viewModelScope 解耦，挂死的站点调用不拖住 UI 状态复位 */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var searchGen = 0

    val history = repository.searchHistory

    val displayMode: StateFlow<String> = settings.ui
        .map { it.searchDisplay }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "thumb")

    /** 聚合结果：各站点返回实时追加 */
    private val _items = MutableStateFlow<List<VodItem>>(emptyList())
    val items: StateFlow<List<VodItem>> = _items.asStateFlow()

    /** 是否搜索进行中 */
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    /** 状态提示：搜索中 / 未找到等 */
    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var searchJob: Job? = null

    /** 本次搜索已覆盖的站点：单站补搜用，避免重复请求同一站点 */
    private val searchedSites = mutableSetOf<String>()

    fun search(keyword: String, siteKey: String) {
        if (keyword.isBlank()) return
        searchJob?.cancel()
        searchGen++
        val gen = searchGen
        searchedSites.clear()
        _items.value = emptyList()
        _searching.value = true
        _statusText.value = "正在并发请求各站点…"
        // 看门狗：无论底层协程是否被挂死站点堵住，最多 100 秒强制收尾 UI 状态
        viewModelScope.launch {
            delay(100_000)
            if (gen == searchGen && _searching.value) {
                _searching.value = false
                _statusText.value = if (_items.value.isEmpty()) "部分站点无响应，已停止等待" else ""
            }
        }
        searchJob = searchScope.launch {
            runCatching {
                repository.search(keyword, siteKey) { batch ->
                    // 实时聚合：站点返回一批立即追加；过期搜索的结果直接丢弃
                    if (gen == searchGen) {
                        batch.forEach { searchedSites.add(it.siteKey) }
                        _items.value = _items.value + batch
                        _statusText.value = "已找到 ${_items.value.size} 条，仍在搜索…"
                    }
                }
            }
            if (gen == searchGen) {
                _searching.value = false
                _statusText.value = if (_items.value.isEmpty()) "未找到相关内容" else ""
            }
        }
    }

    /**
     * 单站补搜：结果页点左栏某站点但没有该站结果时触发。
     * 结果追加到已有列表（不清空），同一站点不重复请求；
     * 该站点确实搜不到时保持无结果，界面静默。
     */
    fun searchSingleSite(keyword: String, siteKey: String) {
        if (keyword.isBlank() || siteKey.isBlank()) return
        if (!searchedSites.add(siteKey)) return
        val gen = searchGen
        _searching.value = true
        searchScope.launch {
            runCatching {
                repository.search(keyword, siteKey) { batch ->
                    if (gen == searchGen) {
                        _items.value = _items.value + batch
                        _statusText.value = "已找到 ${_items.value.size} 条，仍在搜索…"
                    }
                }
            }
            // 单站补搜结束：若主搜索也已收尾且没有别的补搜在跑，恢复收尾状态
            if (gen == searchGen) {
                _searching.value = false
                _statusText.value = ""
            }
        }
    }

    fun toggleDisplayMode() {
        viewModelScope.launch {
            val current = settings.ui.first()
            settings.setUi(current.copy(
                searchDisplay = if (current.searchDisplay == "thumb") "text" else "thumb"
            ))
        }
    }
}
