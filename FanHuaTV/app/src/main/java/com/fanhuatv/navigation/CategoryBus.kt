package com.fanhuatv.navigation

/**
 * 首页分类栏 -> 分类页 的轻量传递通道。
 *
 * 分类是「某个脚本的 tid」，所以除了 tid 还要带上产生它的 scriptId，
 * 否则分类页不知道去问哪个脚本，tid 对不上就永远是空列表。
 */
object CategoryBus {
    var pendingTid: String = ""
    var pendingScriptId: String = ""
    var pendingName: String = ""

    fun select(tid: String, scriptId: String = "", name: String = "") {
        pendingTid = tid
        pendingScriptId = scriptId
        pendingName = name
    }

    /** 取一次并清空，避免下次进入分类页被旧值劫持 */
    fun consumeTid(): String {
        val t = pendingTid
        pendingTid = ""
        return t
    }

    fun consumeScriptId(): String {
        val s = pendingScriptId
        pendingScriptId = ""
        return s
    }

    /** 取一次分类名字并清空。名字比 tid 稳定，用它锁定用户点的那个 Tab。 */
    fun consumeName(): String {
        val n = pendingName
        pendingName = ""
        return n
    }

}
