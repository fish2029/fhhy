package com.fanhuatv.feature.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.core.storage.MainRepository
import com.fanhuatv.core.util.Resource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: MainRepository
) : ViewModel() {

    private val _state = MutableStateFlow<Resource<List<VodItem>>>(Resource.Loading)
    val state: StateFlow<Resource<List<VodItem>>> = _state.asStateFlow()

    /** 切 Tab / 首次进入时按分类 id 拉取影片；scriptId 决定去问哪个脚本 */
    private var loadJob: Job? = null
    private var loadSeq = 0

    fun load(tid: String, scriptId: String = "") {
        // 切 Tab 会连续触发多次 load（进页面先第 0 个、预选中后又加载目标分类）。
        // 取消旧请求 + 只认最新序号的结果，双保险：慢返回的旧数据不能覆盖目标分类，
        // 否则表现为"点国产动漫，内容却是更新(第一行)那批"。
        loadJob?.cancel()
        val seq = ++loadSeq
        loadJob = viewModelScope.launch {
            _state.value = Resource.Loading
            val result = repository.loadCategory(tid, "1", scriptId)
            if (seq == loadSeq) _state.value = result
        }
    }
}
