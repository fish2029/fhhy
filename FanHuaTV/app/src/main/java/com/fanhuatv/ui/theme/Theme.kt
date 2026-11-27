package com.fanhuatv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** 强调色方案：base=默认态，deep=选中态，pill=渐变胶囊（搜索栏/分类条同一套） */
data class AccentScheme(val base: Color, val deep: Color, val pill: List<Color>)

/** 可选强调色（「我的-主题色彩-强调色」） */
val ACCENT_SCHEMES = mapOf(
    "purple" to AccentScheme(Color(0xFF9D5CE0), Color(0xFF7A45E0), listOf(Color(0xFF7B5FE0), Color(0xFFB06DE6))),
    "blue" to AccentScheme(Color(0xFF4D7CF6), Color(0xFF3560D9), listOf(Color(0xFF4D7CF6), Color(0xFF86A9FF))),
    "cyan" to AccentScheme(Color(0xFF2BB3C0), Color(0xFF1E8E99), listOf(Color(0xFF2BB3C0), Color(0xFF74D9E2))),
    "pink" to AccentScheme(Color(0xFFE05CA8), Color(0xFFC0458C), listOf(Color(0xFFE05CA8), Color(0xFFF08CC8))),
    "orange" to AccentScheme(Color(0xFFE08A3C), Color(0xFFC26E22), listOf(Color(0xFFE08A3C), Color(0xFFF5B96E))),
    "green" to AccentScheme(Color(0xFF4CAF7D), Color(0xFF389062), listOf(Color(0xFF4CAF7D), Color(0xFF8AD9B4)))
)

/** 当前强调色 key：快照状态，改一处全局刷新（所有直接引用 Accent/AccentDeep/AccentPill 的地方自动生效） */
private var accentKey by mutableStateOf("purple")

/** 应用强调色（未知 key 回落默认紫），MainActivity 启动及设置变更时调用 */
fun applyAccent(key: String?) {
    accentKey = if (key != null && ACCENT_SCHEMES.containsKey(key)) key else "purple"
}

/** 全局默认强调色：紫色填充胶囊（搜索栏/按钮/chips 默认态统一用这套紫） */
val Accent: Color get() = (ACCENT_SCHEMES[accentKey] ?: ACCENT_SCHEMES.getValue("purple")).base
/** 选中态更深一档（与默认态区分） */
val AccentDeep: Color get() = (ACCENT_SCHEMES[accentKey] ?: ACCENT_SCHEMES.getValue("purple")).deep
/** 全局按钮/胶囊统一的渐变（与站源弹窗标题栏同一套） */
val AccentPill: List<Color> get() = (ACCENT_SCHEMES[accentKey] ?: ACCENT_SCHEMES.getValue("purple")).pill

val BgPrimary = Color(0xFF0B0C10)
val BgSurface = Color(0xFF16181D)
val BgCard = Color(0xFF1F2229)
val TextSecondary = Color(0xFF9AA0AA)

@Composable
fun FanHuaTheme(themeMode: String = "dark", content: @Composable () -> Unit) {
    // 全局恒定深色背景（用户要求：恢复默认，不要白色）——外观模式选项保留，但浅色/跟随系统也渲染深色
    val dark = true
    // 读一次快照状态：强调色切换时整个主题跟着重建（Switch/按钮等 Material 组件同步换色）
    val accent = Accent
    val darkColors = darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        background = BgPrimary,
        onBackground = Color.White,
        surface = BgSurface,
        onSurface = Color.White,
        surfaceVariant = BgCard,
        error = Color(0xFFE24B4A)
    )
    val lightColors = lightColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        background = Color.White,
        onBackground = Color(0xFF16181D),
        surface = Color(0xFFF5F5F5),
        onSurface = Color(0xFF16181D)
    )
    MaterialTheme(
        colorScheme = if (dark) darkColors else lightColors,
        content = content
    )
}
