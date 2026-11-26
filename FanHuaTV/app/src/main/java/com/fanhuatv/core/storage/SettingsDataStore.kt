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
    val themeMode: String = "system",       // system | dark | light
    val startPage: String = "home",         // home | live
    // 智能分类：开启后站源切换按特征自动归到 JAR内站点 / PY站点 / 音乐 / 短剧
    val smartCategory: Boolean = false
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
