package com.fanhuatv.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.storage.PlaybackSettings
import com.fanhuatv.core.storage.SettingsDataStore
import com.fanhuatv.core.util.Resource
import com.fanhuatv.core.util.UrlRules
import com.fanhuatv.player.LivePlayBus
import com.fanhuatv.player.Media3Engine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val engine: Media3Engine,
    private val settingsStore: SettingsDataStore,
    private val repository: MainRepository
) : ViewModel() {

    /** 选集解析中标记：UI 显示"解析中"提示，避免用户以为点了没反应 */
    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    /** 选集解析失败信息：解析失败必须给用户明确原因，而不是无声无息 */
    private val _resolveError = MutableStateFlow("")
    val resolveError: StateFlow<String> = _resolveError.asStateFlow()

    /** 最近一次真正交给播放器的地址（解析后）；刷新按钮用它，切集后刷新不会回到第 1 集 */
    var lastPlayedUrl: String = ""
        private set

    fun clearResolveError() { _resolveError.value = "" }

    val settings: StateFlow<PlaybackSettings> = settingsStore.playback
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackSettings())

    /** 界面设置：悬浮小窗开关、弹幕字号/速度/透明度/屏蔽词 */
    val ui: StateFlow<com.fanhuatv.core.storage.UiSettings> = settingsStore.ui
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.fanhuatv.core.storage.UiSettings())

    /** 设置倍速：立即生效；rememberSpeed 开启时记忆到设置，下次播放自动应用 */
    fun applySpeed(speed: Float) {
        engine.setSpeed(speed)
        if (settings.value.rememberSpeed) {
            viewModelScope.launch {
                settingsStore.setPlayback(settings.value.copy(speed = speed))
            }
        }
    }

    /** 画面比例：fit=适应 / fill=铺满裁切 / stretch=拉伸 */
    fun applyScaleMode(mode: String) {
        viewModelScope.launch {
            settingsStore.setPlayback(settings.value.copy(scaleMode = mode))
        }
    }

    /** 跳过片头开关：开启后每次播放自动跳过前 90 秒 */
    fun applySkipIntro(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setPlayback(settings.value.copy(skipIntro = enabled))
        }
    }

    /** 自动连播开关：播完自动下一集，不弹任何菜单 */
    fun applyAutoNext(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setPlayback(settings.value.copy(autoNext = enabled))
        }
    }

    /** 上下滑动切换：从 pendingItem 的剧集列表取上/下一集 */
    fun switchEpisode(next: Boolean) {
        val item = LivePlayBus.pendingItem ?: return
        if (item.episodes.isEmpty()) return
        val currentUrl = LivePlayBus.pendingUrl
        val currentIndex = item.episodes.indexOfFirst { it.playableUrl == currentUrl }
        val target = if (next) currentIndex + 1 else currentIndex - 1
        val episode = item.episodes.getOrNull(target) ?: return
        val url = episode.playableUrl
        LivePlayBus.play(url, "${item.displayTitle} ${episode.name}", item)
        playEpisodeResolved(item, url, settings.value.speed, episode.playFrom)
    }

    /** 调用播放器前的统一校验：必须带 scheme://（UrlRules）。
     *  合法返回 null；不合法返回可读提示并打日志，由 UI 展示、不再碰播放器。 */
    private fun validatePlayUrl(url: String): String? {
        if (UrlRules.isUniversalUrl(url)) return null
        android.util.Log.w("FanHuaTV", "播放前校验拦截无协议头地址：url=${url.trim().take(160)}")
        return "该源返回的地址无法识别（${url.trim().take(30)}），请换线路（片源）或换站点"
    }

    /** 播放器内直接播放某集地址（手势切集/选集面板共用）：先过 UrlRules 再交给引擎 */
    fun playEpisode(url: String, speed: Float) {
        viewModelScope.launch {
            val invalid = validatePlayUrl(url)
            if (invalid != null) {
                _resolveError.value = invalid
                return@launch
            }
            // 探测/播放中的错误由 PlayerScreen 的 Player.Listener 捕获展示
            engine.play(url, speed)
        }
    }

    /** 详情页/历史/直播跳进来的直接播放入口：同样先校验 UrlRules，返回错误串供 UI 展示 */
    fun playDirect(url: String, speed: Float): String? {
        val invalid = validatePlayUrl(url)
        if (invalid != null) {
            _resolveError.value = invalid
            return invalid
        }
        viewModelScope.launch { engine.play(url, speed) }
        return null
    }

    /**
     * 选集播放：py/jar 站点的剧集地址是播放页或复合 id（"名称|集数"、"x@y@z"），
     * 直接喂给 ExoPlayer 只会报 ERROR_CODE_IO_FILE_NOT_FOUND。
     * 必须先走 resolvePlayUrl 解析成真实视频流；json 直链走快速通道直接播。
     */
    fun playEpisodeResolved(item: VodItem?, rawUrl: String, speed: Float, playFlag: String = "") {
        viewModelScope.launch {
            // 切集/刷新都算新的一次解析：先清空旧的弹幕/海报/歌词，解析成功后再填回
            LivePlayBus.clearMediaExtras()
            val target = item ?: LivePlayBus.pendingItem
            // 直链快速通道：无需解析（json 站点/已解析地址）。
            // 必须是带 :// 的真地址才走快速通道；[文件名].mp4[...] 这类裸串进解析链路统一拦截
            if (target == null || (isDirectUrl(rawUrl) && UrlRules.isUniversalUrl(rawUrl))) {
                _resolveError.value = ""
                playResolved(rawUrl, speed)
                return@launch
            }
            _resolving.value = true
            _resolveError.value = ""
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    withTimeoutOrNull(35_000) { repository.resolvePlayUrl(target, rawUrl, playFlag) }
                }.getOrNull()
            }
            _resolving.value = false
            when (result) {
                is Resource.Success -> playResolved(result.data, speed)
                else -> {
                    // 解析失败：真直链才兜底，网页地址喂播放器只会黑屏/报错。
                    // 必须带协议头才算直链——网盘文件名（如 xxx.mp4[385MB]__71）也含 .mp4，
                    // 拿它兜底只会盖掉蜘蛛的真实报错，变成莫名其妙的「地址无法识别」
                    val fallback = rawUrl.takeIf { isDirectUrl(it) && com.fanhuatv.core.util.UrlRules.isUniversalUrl(it) }
                    if (fallback != null) {
                        playResolved(fallback, speed)
                    } else {
                        _resolveError.value = (result as? Resource.Error)?.message?.takeIf { it.isNotBlank() }
                            ?: if (result == null) "解析超时，请重试或换一个站点"
                            else "未取到播放地址，该源可能需要网页解析"
                    }
                }
            }
        }
    }

    private suspend fun playResolved(url: String, speed: Float) {
        if (url.isBlank()) {
            _resolveError.value = "播放地址为空"
            return
        }
        // 调用播放器前必须先用 UrlRules 校验：解析结果也可能是裸串（如网盘复合 id），
        // 不带 :// 的直接拦下，绝不喂给 ExoPlayer
        validatePlayUrl(url)?.let {
            _resolveError.value = it
            return
        }
        lastPlayedUrl = url
        android.util.Log.i("FanHuaTV", "playResolved 最终地址：url=${url.take(200)}")
        engine.play(url, speed)
    }

    /**
     * 播放进度落库：暂停/退出播放页时调用。
     * 直播（无 item）不记；进度接近片尾（>95%）视为看完，下次从头播。
     */
    fun saveProgress(item: VodItem?, playUrl: String, positionMs: Long, durationMs: Long, episode: String, episodeId: String = "") {
        if (item == null || item.id.isBlank()) return
        if (durationMs <= 0 || positionMs <= 0) return
        viewModelScope.launch {
            val nearEnd = positionMs * 100 / durationMs >= 95
            runCatching {
                repository.saveHistory(
                    item, playUrl,
                    progress = if (nearEnd) 0L else positionMs,
                    duration = durationMs,
                    episode = episode,
                    episodeId = episodeId
                )
            }
        }
    }

    /** 简易直链判断：与 DetailViewModel/PyRuntime 口径一致 */
    private fun isDirectUrl(url: String): Boolean {
        val l = url.lowercase()
        return l.contains(".m3u8") || l.contains(".mp4") || l.contains(".m3u") ||
                l.contains(".flv") || l.contains(".mkv") || l.contains(".ts")
    }
}
