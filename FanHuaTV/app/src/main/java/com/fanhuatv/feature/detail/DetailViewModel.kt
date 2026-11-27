package com.fanhuatv.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: MainRepository
) : ViewModel() {

    private val _detail = MutableStateFlow<VodItem?>(null)
    val detail: StateFlow<VodItem?> = _detail.asStateFlow()

    /** 同一条目的详情请求只发一次：详情页进来自动拉一次、自动播放又拉一次，
     *  两次并发打同一只蜘蛛，冷启动时握手互相踩踏，详情必挂（咕噜全挂的病根）。
     *  同一条目复用同一个 Deferred（含已完成的结果），换源点别的条目才发新请求。 */
    private var detailDeferred: kotlinx.coroutines.Deferred<Resource<VodItem>>? = null
    private var detailDeferredKey: VodItem? = null
    private var detailDeferredOk = false

    private fun fetchDetail(item: VodItem): kotlinx.coroutines.Deferred<Resource<VodItem>> {
        detailDeferred?.let { d ->
            // 同一条目复用同一次请求；失败过的允许重拉，只有成功结果才直接复用
            if (detailDeferredKey === item && (d.isActive || detailDeferredOk)) return d
        }
        detailDeferredKey = item
        detailDeferredOk = false
        val d = viewModelScope.async {
            repository.loadDetail(item).also { detailDeferredOk = it is Resource.Success }
        }
        detailDeferred = d
        return d
    }

    /** 从 PY 脚本 supplement 剧集列表；失败时给出可见提示，不再静默吞掉 */
    fun loadDetail(item: VodItem) {
        _detail.value = item
        if (item.episodes.isNotEmpty()) return
        viewModelScope.launch {
            when (val r = fetchDetail(item).await()) {
                is Resource.Success -> _detail.value = r.data
                is Resource.Error -> _notice.value = r.message.ifBlank { "该站点详情获取失败" }
                else -> Unit
            }
        }
    }

    suspend fun isFavorite(vodId: String): Boolean = repository.isFavorite(vodId)

    fun toggleFavorite(item: VodItem) {
        viewModelScope.launch { repository.toggleFavorite(item) }
    }

    private val _notice = MutableStateFlow("")
    val notice: StateFlow<String> = _notice.asStateFlow()

    /** 换源候选：当前详情无可播内容（无剧集无地址，典型是豆瓣源）时全站搜同名片子 */
    private val _altSources = MutableStateFlow<List<VodItem>>(emptyList())
    val altSources: StateFlow<List<VodItem>> = _altSources.asStateFlow()
    private val _altLoading = MutableStateFlow(false)
    val altLoading: StateFlow<Boolean> = _altLoading.asStateFlow()

    /** 全站搜同名片子供换源：结果实时追加，按站点去重，只留片名完全一致的 */
    fun searchAltSources(title: String) {
        val t = title.trim()
        if (t.isBlank() || _altLoading.value) return
        _altLoading.value = true
        viewModelScope.launch {
            val found = mutableListOf<VodItem>()
            val job = launch {
                repository.search(t, "", recordHistory = false) { list ->
                    synchronized(found) {
                        list.forEach { v ->
                            if (v.title.trim() == t && found.none { it.siteKey == v.siteKey }) found.add(v)
                        }
                        _altSources.value = found.toList()
                    }
                }
            }
            // 换源不用等满整轮搜索：15 秒足够大部分站点回报
            withTimeoutOrNull(15_000) { job.join() }
            _altLoading.value = false
        }
    }

    /** 简易直链判断：网页地址（.html/嗅探型）喂给播放器只会黑屏，不能当兜底 */
    private fun looksDirect(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".m3u") ||
                l.contains(".flv") || l.contains(".mkv") || l.contains(".ts")
    }

    /** 解析真实播放地址：PY 脚本优先，否则用原始地址。
     *  关键：resolvePlay 内部是同步 Python 调用 + 网络请求，必须放到 IO 线程，
     *  否则压在主线程会让整个界面卡住、选集按钮点不动。解析失败也要给 onReady 一个
     *  可用地址（原始 url），保证点了必有反应，而不是静默无跳转。 */
    fun play(item: VodItem, episodeUrl: String, playFlag: String = "", onReady: (String) -> Unit) {
        viewModelScope.launch {
            // 新播放请求开始：先清掉上一部片源的弹幕/海报/歌词（解析成功后仓库会填回）
            com.fanhuatv.player.LivePlayBus.clearMediaExtras()
            _notice.value = "正在解析播放地址…"
            val fallback = episodeUrl.ifBlank { item.playUrl }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeoutOrNull(50_000) { repository.resolvePlayUrl(item, episodeUrl, playFlag) }
                }.getOrNull()
            }
            val url = when (result) {
                is Resource.Success -> result.data
                // 解析失败：只有带协议头的真直链才兜底，网页地址喂播放器会黑屏/解容器失败。
                // 网盘文件名（xxx.mp4[大小]__编号）也含 .mp4，拿它兜底会把蜘蛛的真实报错
                // 盖成莫名其妙的「地址无法识别」——必须带 :// 才算数
                else -> fallback.takeIf { looksDirect(it) && com.fanhuatv.core.util.UrlRules.isUniversalUrl(it) }.orEmpty()
            }
            if (url.isBlank()) {
                _notice.value = (result as? Resource.Error)?.message?.takeIf { it.isNotBlank() }
                    ?: if (result == null) "解析超时，请换一个站点或稍后重试"
                    else "未取到播放地址，该源可能需要网页解析"
                return@launch
            }
            _notice.value = ""
            onReady(url)
        }
    }

    /** 点剧名自动播放：先补剧集，再解析第一集地址，全部在 IO 线程跑，不卡界面。
     *  只走条目自己站点的详情和解析，点哪个站就只走哪个站；
     *  播不出来立刻报错，绝不偷偷换站。 */
    fun autoPlay(item: VodItem, onReady: (VodItem, String) -> Unit) {
        viewModelScope.launch {
            // 同上：请求入口清空，避免上一部的海报/歌词留给这一部
            com.fanhuatv.player.LivePlayBus.clearMediaExtras()
            _notice.value = "正在解析播放地址…"
            val (detail, firstEp) = withContext(Dispatchers.IO) {
                val d = if (item.episodes.isEmpty()) {
                    runCatching { withTimeoutOrNull(12_000) { fetchDetail(item).await() } }
                        .getOrNull()
                        .let { (it as? Resource.Success)?.data ?: item }
                } else item
                d to d.episodes.firstOrNull()
            }
            _detail.value = detail
            val result = withContext(Dispatchers.IO) {
                runCatching { withTimeoutOrNull(50_000) { repository.resolvePlayUrl(detail, firstEp?.playableUrl.orEmpty(), firstEp?.playFrom.orEmpty()) } }
                    .getOrNull()
            }
            val resolved = (result as? Resource.Success)?.data.orEmpty()
            // 兜底同样必须带协议头：网盘文件名含 .mp4 不算直链，别拿它盖住真实报错
            val finalUrl = resolved.ifBlank {
                firstEp?.playableUrl.orEmpty().takeIf { looksDirect(it) && com.fanhuatv.core.util.UrlRules.isUniversalUrl(it) }.orEmpty()
                    .ifBlank { detail.playUrl.takeIf { looksDirect(it) && com.fanhuatv.core.util.UrlRules.isUniversalUrl(it) }.orEmpty() }
            }
            if (finalUrl.isNotBlank()) {
                _notice.value = ""
                onReady(detail, finalUrl)
                return@launch
            }
            // 源站没播出来：直接报具体错误，不换站
            _notice.value = (result as? Resource.Error)?.message?.takeIf { it.isNotBlank() }
                ?: "该源没有片源，请手动换源"
        }
    }

    /** 无剧集无地址时点「立即播放」：只提示手动换源，不自动换站 */
    fun notifyManualSwitch() {
        _notice.value = "该源没有片源，请用下方「换源」手动切站点"
        viewModelScope.launch {
            delay(3000)
            if (_notice.value == "该源没有片源，请用下方「换源」手动切站点") _notice.value = ""
        }
    }
}
