package com.fanhuatv.player

import com.fanhuatv.core.model.VodItem

/** 页面间传递「立即播放」意图的轻量总线（详情页/直播页 → 播放器页）。 */
object LivePlayBus {
    var pendingUrl: String = ""
    var pendingTitle: String = ""
    var pendingItem: VodItem? = null

    /** 断点续播：从历史进入时带上次的播放位置（毫秒），播放器就绪后跳过去；用完即清零 */
    var pendingSeek: Long = 0L

    /** 本次播放的弹幕地址（jar 源的 danmaku 字段，本地代理可取）；无弹幕为空串 */
    var pendingDanmaku: String = ""

    /** 从搜索/脚本结果进入详情时暂存影片（详情页在 config.homeItems 里找不到时用） */
    var pendingDetail: VodItem? = null
    private var recent: LinkedHashMap<String, VodItem> = LinkedHashMap()

    /** 把列表里的影片登记进缓存，供详情页按 id/title 找回 */
    fun rememberItems(items: List<VodItem>) {
        items.forEach { recent[it.id.ifBlank { it.displayTitle }] = it }
    }

    /** 找回影片：优先最近一次点进详情的那份（带正确来源标记），
     *  再查缓存——不同站点影片号常撞号，缓存命中不一定是用户点的那家 */
    fun findItem(id: String): VodItem? = pendingDetail?.takeIf {
        it.id == id || it.displayTitle == id
    } ?: recent[id]

    fun play(url: String, title: String = "", item: VodItem? = null, seekTo: Long = 0L) {
        this.pendingUrl = url
        this.pendingTitle = title
        this.pendingItem = item
        this.pendingSeek = seekTo
    }

    fun consume(): Triple<String, String, VodItem?> {
        // 不清空：播放器里的切集/刷新/进度条都还要读 pendingItem/pendingUrl，
        // 清了之后切集永远无声无息没反应；数据留给下一次 play() 自然覆盖
        return Triple(pendingUrl, pendingTitle, pendingItem)
    }
}
