package com.fanhuatv.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.LiveChannel
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.storage.SettingsDataStore
import com.fanhuatv.core.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LiveViewModel @Inject constructor(
    private val repository: MainRepository,
    private val settings: SettingsDataStore
) : ViewModel() {

    val liveUrl = settings.liveUrl

    private val _channels = MutableStateFlow<Resource<List<LiveChannel>>>(Resource.Loading)
    val channels: StateFlow<Resource<List<LiveChannel>>> = _channels.asStateFlow()

    fun loadChannels(config: AppConfig? = null) {
        viewModelScope.launch {
            // 优先订阅配置里的 lives 字段（TVBox 约定），没配置再回退手动设置的直播源
            val fromConfig = config?.liveSources?.firstOrNull { it.enabled && it.url.isNotBlank() }
            val url = fromConfig?.url ?: liveUrl.first()
            if (url.isBlank()) {
                _channels.value = Resource.Error("未配置直播源")
                return@launch
            }
            _channels.value = Resource.Loading
            // format 传 auto：订阅里 txt/m3u 都有，按内容自动识别，别写死
            _channels.value = repository.loadLive(url, fromConfig?.format?.ifBlank { "auto" } ?: "auto")
        }
    }
}
