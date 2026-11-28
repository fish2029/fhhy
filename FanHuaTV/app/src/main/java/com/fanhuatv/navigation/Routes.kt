package com.fanhuatv.navigation

/** 全 App 路由表。四个主 Tab 与二级页面严格分离。 */
object Routes {
    // 主 Tab
    const val HOME = "home"
    const val CATEGORY = "category"
    const val LIVE = "live"
    const val MINE = "mine"

    // 二级页面
    const val SEARCH = "search"
    const val DETAIL = "detail/{vodId}"
    // vodId 必须编码：网盘源的 id 形如 "voddetail/xxx"，带斜杠会把路由路径切碎，
    // 导航匹配不到 destination 直接抛 IllegalArgumentException（网盘分类点影片必崩）
    fun detail(vodId: String) = "detail/" + android.net.Uri.encode(vodId)

    const val PLAYER = "player"
    const val SETTINGS = "settings"
    const val CONFIG = "config"
    const val PY_SCRIPT = "py_script"
    const val HISTORY = "history"
    const val FAVORITE = "favorite"
    const val POSTER_WALL = "poster_wall"
    const val ABOUT = "about"
    const val SECURITY = "security"
    const val CRASH_LOG = "crash_log"

    val TAB_ROUTES = listOf(HOME, CATEGORY, HISTORY, LIVE, MINE)
}
