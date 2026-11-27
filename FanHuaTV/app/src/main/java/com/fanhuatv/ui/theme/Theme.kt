package com.fanhuatv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier

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

/** 当前外观模式 key：快照状态，切换时全局背景深浅跟着变（三档均为深色系，避免白底白字） */
private var themeKey by mutableStateOf("dark")

/** 当前按钮样式 key：快照状态，gradient=渐变胶囊 / solid=纯色圆角 / outline=描边幽灵 */
private var buttonStyleKey by mutableStateOf("gradient")

/** 当前全局文字颜色 key：快照状态，改一处全局刷新 */
private var textColorKey by mutableStateOf("white")

/** 应用外观模式（未知 key 回落深色），MainActivity 启动及设置变更时调用 */
fun applyThemeMode(key: String?) {
    themeKey = when (key) {
        "light" -> "light"
        "system" -> "system"
        else -> "dark"
    }
}

/** 应用强调色（未知 key 回落默认紫），MainActivity 启动及设置变更时调用 */
fun applyAccent(key: String?) {
    accentKey = if (key != null && ACCENT_SCHEMES.containsKey(key)) key else "purple"
}

/** 应用按钮样式（未知 key 回落渐变胶囊），MainActivity 启动及设置变更时调用 */
fun applyButtonStyle(key: String?) {
    buttonStyleKey = when (key) {
        "solid" -> "solid"
        "outline" -> "outline"
        else -> "gradient"
    }
}

/** 应用全局文字颜色（未知 key 回落纯白），MainActivity 启动及设置变更时调用 */
fun applyTextColor(key: String?) {
    textColorKey = if (key != null && TEXT_COLORS.containsKey(key)) key else "white"
}

/** 全局默认强调色：紫色填充胶囊（搜索栏/按钮/chips 默认态统一用这套紫） */
val Accent: Color get() = (ACCENT_SCHEMES[accentKey] ?: ACCENT_SCHEMES.getValue("purple")).base
/** 选中态更深一档（与默认态区分） */
val AccentDeep: Color get() = (ACCENT_SCHEMES[accentKey] ?: ACCENT_SCHEMES.getValue("purple")).deep
/** 全局按钮/胶囊统一的渐变（与站源弹窗标题栏同一套） */
val AccentPill: List<Color> get() = (ACCENT_SCHEMES[accentKey] ?: ACCENT_SCHEMES.getValue("purple")).pill

/** 可选全局文字颜色（「我的-主题色彩-全局文字颜色」） */
val TEXT_COLORS = mapOf(
    "white" to Color(0xFFFFFFFF),
    "warm" to Color(0xFFFFF1DC),
    "gold" to Color(0xFFFFE082),
    "blue" to Color(0xFFA6C8FF),
    "purple" to Color(0xFFD8B4F0)
)

/** 全局主文字颜色：快照状态，切换即时全局刷新 */
val TextPrimary: Color get() = TEXT_COLORS[textColorKey] ?: Color.White

/** 当前按钮样式是否为描边幽灵（按钮内文字/图标需换强调色） */
fun isOutlineButtonStyle(): Boolean = buttonStyleKey == "outline"

/** 全局按钮填充：渐变胶囊（默认）/ 纯色圆角 / 描边幽灵，在 composition 中调用即可随设置刷新 */
fun buttonFill(shape: Shape = androidx.compose.ui.graphics.RectangleShape): Modifier = when (buttonStyleKey) {
    "solid" -> Modifier.background(Accent, shape)
    "outline" -> Modifier.background(Color.Transparent, shape).border(1.dp, Accent.copy(alpha = 0.85f), shape)
    else -> Modifier.background(Brush.horizontalGradient(AccentPill), shape)
}

/** 按钮内文字/图标颜色：描边样式用强调色，其余白色 */
fun buttonTextColor(): Color = if (isOutlineButtonStyle()) Accent else Color.White

/** Material Button 配色：随「按钮样式」设置变化（渐变/纯色底配黑字，描边配强调色字） */
@Composable
fun materialButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = if (isOutlineButtonStyle()) Accent else Color.Black
)

/** Material Button 背景修饰符：渐变胶囊/纯色/描边统一走这里（按钮自身会裁剪圆角） */
fun materialButtonBackground(): Modifier = when (buttonStyleKey) {
    "solid" -> Modifier.background(Accent)
    "outline" -> Modifier
    else -> Modifier.background(Brush.horizontalGradient(AccentPill))
}

/** Material Button 描边：仅描边幽灵样式返回边框 */
fun materialButtonBorder(): BorderStroke? =
    if (isOutlineButtonStyle()) BorderStroke(1.dp, Accent.copy(alpha = 0.85f)) else null

/** Material Button 文字颜色：渐变/纯色底配黑字，描边配强调色字 */
fun materialButtonTextColor(): Color = if (isOutlineButtonStyle()) Accent else Color.Black

/** 背景三档：浅色日间=亮灰调，跟随系统=默认深黑，深色夜间=纯黑（白字在三档下均可读） */
val BgPrimary: Color get() = when (themeKey) {
    "light" -> Color(0xFF1E2128)
    "system" -> Color(0xFF0B0C10)
    else -> Color(0xFF000000)
}
val BgSurface: Color get() = when (themeKey) {
    "light" -> Color(0xFF262A33)
    else -> Color(0xFF16181D)
}
val BgCard: Color get() = when (themeKey) {
    "light" -> Color(0xFF2E323D)
    else -> Color(0xFF1F2229)
}
val TextSecondary: Color get() = if (themeKey == "light") Color(0xFFA8AEB8) else Color(0xFF9AA0AA)

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
        onBackground = TextPrimary,
        surface = BgSurface,
        onSurface = TextPrimary,
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
