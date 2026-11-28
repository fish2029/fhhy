package com.fanhuatv.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fanhuatv.core.model.ConfigSource
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fanhua_settings")

/** 播放设置 */
data class PlaybackSettings(
    val kernel: String = "media3",          // media3 | ijk | system
    val decoder: String = "hard",           // hard | soft
    val surfaceType: String = "texture",    // texture | surface
    val autoNext: Boolean = true,
    val skipIntro: Boolean = false,
    val rememberSpeed: Boolean = true,
    val speed: Float = 1.0f,
    val scaleMode: String = "fit"
)

/** 界面设置 */
data class UiSettings(
    val homeRecommend: String = "douban",   // douban | site | history
    val searchDisplay: String = "thumb",    // thumb | text
    val historyLimit: Int = 50,
    val themeMode: String = "dark",         // system | dark | light（全局恒深色渲染）
    val startPage: String = "home",         // home | live
    // 智能分类：开启后站源切换按特征自动归到 JAR内站点 / PY站点 / 音乐 / 短剧
    val smartCategory: Boolean = false,
    // ===== 「我的-主题色彩 / 导航首页」新增项 =====
    // 可空是为了兼容旧 JSON：Gson 反序列化缺字段时填 null（绕过 Kotlin 默认值），
    // 调用方用 elvis 取默认值，避免旧存档读出 0/null 导致缩放为 0 崩溃
    val accent: String? = null,             // 强调色 key：purple | blue | cyan | pink | orange | green，null=默认紫
    val fontScale: Int? = null,             // 界面缩放百分比 90/100/110/120，null=100
    val bottomBarStyle: String? = null,     // 底栏形态：labeled=图标+文字 | icons=仅图标，null=labeled
    val bottomBarAutoHide: Boolean? = null, // 底栏自动收起（下滑隐藏/上滑出现），null=false
    val homeBanner: Boolean? = null,        // 首页大图横幅开关，null=true
    val posterColumns: Int? = null,         // 海报密度（列数）2/3/4，null=3
    val posterInfo: String? = null,         // 海报下方信息显示：all=全部显示（评分+标题）| title=仅标题，null=all
    val continueWatching: Boolean? = null,  // 首页「继续观看」一栏，null=true
    val buttonStyle: String? = null,        // 按钮样式：square=方角 | rounded=圆角 | capsule=胶囊，null=capsule
    val textColor: String? = null,          // 全局文字颜色：white | warm | gold | blue | purple，null=white
    val bgColor: String? = null,            // 全局背景颜色：default | graphite | navy | forest 等，null=default
    // 悬浮小窗：退出播放（返回键）自动进入画中画继续播放，null=false
    val autoPip: Boolean? = null,
    // ===== 弹幕设置（null=默认值，兼容旧存档） =====
    val danmakuOn: Boolean? = null,         // 弹幕开关，null=true（默认开）
    val danmakuSize: Int? = null,           // 字号 sp，null=14
    val danmakuSpeed: Int? = null,          // 跨屏秒数，null=9
    val danmakuAlpha: Int? = null,          // 不透明度百分比 20~100，null=100
    val danmakuBlock: String? = null        // 屏蔽词，逗号/顿号分隔，null=无
)

/** 所有设置项的 DataStore 仓库 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    private object Keys {
        val CONFIG_SOURCES = stringPreferencesKey("config_sources")
        val ACTIVE_SOURCE = stringPreferencesKey("active_source")
        val LIVE_URL = stringPreferencesKey("live_url")
        val LIVE_TEMPLATE = stringPreferencesKey("live_template")
        val PLAYBACK = stringPreferencesKey("playback")
        val UI = stringPreferencesKey("ui")
        val SCRIPT_PRIORITY = booleanPreferencesKey("script_priority")
        val BEST_SCRIPT = stringPreferencesKey("best_script_id")
        val HOME_SITE = stringPreferencesKey("home_site")   // 用户手动选中的站源标识，空=没选
        val PY_URL_MAP = stringPreferencesKey("py_url_map")   // .py 地址 -> 已导入脚本 ID
        val PY_EXT_MAP = stringPreferencesKey("py_ext_map")   // 脚本 ID -> TVBox ext 参数
        val HOTUPDATE_URL = stringPreferencesKey("hotupdate_url")   // 源热更新：PY 脚本远程地址
        val HOTUPDATE_AUTO = booleanPreferencesKey("hotupdate_auto") // 源热更新：启动自动拉取
        val AI_URL = stringPreferencesKey("ai_url")     // AI 修源：接口地址（OpenAI 兼容 chat/completions）
        val AI_KEY = stringPreferencesKey("ai_key")     // AI 修源：接口密钥
        val AI_MODEL = stringPreferencesKey("ai_model") // AI 修源：模型名
        val TMDB_KEY = stringPreferencesKey("tmdb_key") // 影视海报墙：TMDB API Key
        val CONFIG_CLEARED_V21 = booleanPreferencesKey("config_cleared_v21")
    }

    companion object {
        /** 出厂内置订阅：用户没配接口（或删光）时兜底，保证开箱即有片源 */
        private const val BUILTIN_SOURCE_ID = "builtin_t4"
        private const val BUILTIN_CONFIG_URL = "https://www.612668.xyz/T4/config.json"
        private val BUILTIN_SOURCES = listOf(
            ConfigSource(id = BUILTIN_SOURCE_ID, name = "繁华内置线路", url = BUILTIN_CONFIG_URL)
        )
    }

    init {
        // 一次性迁移（v21）：
        // 一次性迁移（v21）：清空旧版本覆盖安装遗留的接口地址——让"没填接口"真的是空白。只清一次，之后填的都保留。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                context.dataStore.edit { prefs ->
                    if (prefs[Keys.CONFIG_CLEARED_V21] != true) {
                        prefs.remove(Keys.CONFIG_SOURCES)
                        prefs.remove(Keys.ACTIVE_SOURCE)
                        prefs[Keys.CONFIG_CLEARED_V21] = true
                    }
                }
            }
        }
    }

    val configSources: Flow<List<ConfigSource>> = context.dataStore.data.map { prefs ->
        // v21 迁移完成前一律视为"未配置接口"，防止首次启动竞态读到残留地址拉出 dbapi 分类
        if (prefs[Keys.CONFIG_CLEARED_V21] != true) emptyList()
        else {
            val json = prefs[Keys.CONFIG_SOURCES]
            // 用户没配过接口（或删光了）时，回落到出厂内置订阅，开箱即有片源
            if (json.isNullOrBlank()) BUILTIN_SOURCES
            else runCatching {
                gson.fromJson<List<ConfigSource>>(json, object : TypeToken<List<ConfigSource>>() {}.type)
            }.getOrDefault(emptyList()).ifEmpty { BUILTIN_SOURCES }
        }
    }

    val activeSourceId: Flow<String> = context.dataStore.data.map {
        it[Keys.ACTIVE_SOURCE] ?: BUILTIN_SOURCE_ID
    }

    val liveUrl: Flow<String> = context.dataStore.data.map { it[Keys.LIVE_URL] ?: "" }

    val liveTemplate: Flow<String> = context.dataStore.data.map { it[Keys.LIVE_TEMPLATE] ?: "" }

    val playback: Flow<PlaybackSettings> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.PLAYBACK]
        if (json.isNullOrBlank()) PlaybackSettings()
        else runCatching { gson.fromJson(json, PlaybackSettings::class.java) }.getOrDefault(PlaybackSettings())
    }

    val ui: Flow<UiSettings> = context.dataStore.data.map { prefs ->
        val json = prefs[Keys.UI]
        if (json.isNullOrBlank()) UiSettings()
        else runCatching { gson.fromJson(json, UiSettings::class.java) }.getOrDefault(UiSettings())
    }

    val scriptPriority: Flow<Boolean> = context.dataStore.data.map { it[Keys.SCRIPT_PRIORITY] ?: true }

    /** 上次成功取到首页数据的脚本 ID：下次启动优先试它，避免逐个探测太慢 */
    val bestScriptId: Flow<String> = context.dataStore.data.map { it[Keys.BEST_SCRIPT] ?: "" }

    suspend fun setBestScript(id: String) {
        context.dataStore.edit { it[Keys.BEST_SCRIPT] = id }
    }

    /** 用户手动选中的首页站源（PY 脚本 id 或接口站点 key），空串=没选，按配置自然走 */
    val homeSiteId: Flow<String> = context.dataStore.data.map { it[Keys.HOME_SITE] ?: "" }

    suspend fun setHomeSiteId(id: String) {
        context.dataStore.edit { it[Keys.HOME_SITE] = id }
    }

    /** .py 地址 -> 已导入脚本 ID（避免每次刷新都重复下载同一个脚本） */
    val pyUrlMap: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.PY_URL_MAP].let { json ->
            if (json.isNullOrBlank()) emptyMap()
            else runCatching {
                gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
            }.getOrDefault(emptyMap())
        }
    }

    /** 脚本 ID -> TVBox ext（脚本 init 的扩展参数） */
    val pyExtMap: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[Keys.PY_EXT_MAP].let { json ->
            if (json.isNullOrBlank()) emptyMap()
            else runCatching {
                gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
            }.getOrDefault(emptyMap())
        }
    }

    suspend fun putPySite(url: String, scriptId: String, ext: String) {
        context.dataStore.edit { prefs ->
            if (url.isNotBlank()) {
                val urls = prefs[Keys.PY_URL_MAP].let { json ->
                    if (json.isNullOrBlank()) mutableMapOf()
                    else runCatching {
                        gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
                            .toMutableMap()
                    }.getOrDefault(mutableMapOf())
                }
                urls[url] = scriptId
                prefs[Keys.PY_URL_MAP] = gson.toJson(urls)
            }

            val exts = prefs[Keys.PY_EXT_MAP].let { json ->
                if (json.isNullOrBlank()) mutableMapOf()
                else runCatching {
                    gson.fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
                        .toMutableMap()
                }.getOrDefault(mutableMapOf())
            }
            exts[scriptId] = ext
            prefs[Keys.PY_EXT_MAP] = gson.toJson(exts)
        }
    }

    suspend fun pyExtOf(scriptId: String): String = pyExtMap.first()[scriptId].orEmpty()

    /** 源热更新：PY 脚本远程地址（网盘 / GitHub 直链） */
    val hotUpdateUrl: Flow<String> = context.dataStore.data.map { it[Keys.HOTUPDATE_URL] ?: "" }

    /** 源热更新：启动时自动拉取开关 */
    val hotUpdateAuto: Flow<Boolean> = context.dataStore.data.map { it[Keys.HOTUPDATE_AUTO] ?: false }

    suspend fun setHotUpdate(url: String, auto: Boolean) {
        context.dataStore.edit {
            it[Keys.HOTUPDATE_URL] = url
            it[Keys.HOTUPDATE_AUTO] = auto
        }
    }

    /** 影视海报墙：TMDB API Key */
    val tmdbKey: Flow<String> = context.dataStore.data.map { it[Keys.TMDB_KEY] ?: "" }

    suspend fun setTmdbKey(key: String) {
        context.dataStore.edit { it[Keys.TMDB_KEY] = key.trim() }
    }

    /** AI 修源：接口地址 / 密钥 / 模型名（OpenAI 兼容格式） */
    val aiUrl: Flow<String> = context.dataStore.data.map { it[Keys.AI_URL] ?: "" }
    val aiKey: Flow<String> = context.dataStore.data.map { it[Keys.AI_KEY] ?: "" }
    val aiModel: Flow<String> = context.dataStore.data.map { it[Keys.AI_MODEL] ?: "" }

    suspend fun setAiConfig(url: String, key: String, model: String) {
        context.dataStore.edit {
            it[Keys.AI_URL] = url.trim()
            it[Keys.AI_KEY] = key.trim()
            it[Keys.AI_MODEL] = model.trim()
        }
    }

    suspend fun setConfigSources(sources: List<ConfigSource>) {
        context.dataStore.edit { it[Keys.CONFIG_SOURCES] = gson.toJson(sources) }
    }

    suspend fun setActiveSource(id: String) {
        context.dataStore.edit { it[Keys.ACTIVE_SOURCE] = id }
    }

    suspend fun setLive(url: String, template: String) {
        context.dataStore.edit {
            it[Keys.LIVE_URL] = url
            it[Keys.LIVE_TEMPLATE] = template
        }
    }

    suspend fun setPlayback(settings: PlaybackSettings) {
        context.dataStore.edit { it[Keys.PLAYBACK] = gson.toJson(settings) }
    }

    suspend fun setUi(settings: UiSettings) {
        context.dataStore.edit { it[Keys.UI] = gson.toJson(settings) }
    }

    suspend fun setScriptPriority(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SCRIPT_PRIORITY] = enabled }
    }
}
