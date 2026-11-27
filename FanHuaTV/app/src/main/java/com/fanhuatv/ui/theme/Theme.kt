package com.fanhuatv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** 当前按钮样式 key：快照状态，capsule=胶囊（默认）/ rounded=圆角 / square=方角 */
private var buttonStyleKey by mutableStateOf("capsule")

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

/** 应用按钮样式（未知 key 回落胶囊），MainActivity 启动及设置变更时调用 */
fun applyButtonStyle(key: String?) {
    buttonStyleKey = when (key) {
        "square" -> "square"
        "rounded" -> "rounded"
        else -> "capsule"
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

/** 可选全局文字颜色（「我的-主题色彩-全局文字颜色」），24 档全色系，深底可读 */
val TEXT_COLORS = mapOf(
    "white" to Color(0xFFFFFFFF),
    "silver" to Color(0xFFCED4DA),
    "red" to Color(0xFFFF5C5C),
    "crimson" to Color(0xFFE03131),
    "rose" to Color(0xFFF06595),
    "orange" to Color(0xFFFF9F43),
    "tangerine" to Color(0xFFF76707),
    "yellow" to Color(0xFFFCC419),
    "lemon" to Color(0xFFFFE066),
    "lime" to Color(0xFFA9E34B),
    "green" to Color(0xFF51CF66),
    "forest" to Color(0xFF2F9E44),
    "cyan" to Color(0xFF22B8CF),
    "teal" to Color(0xFF0CA678),
    "sky" to Color(0xFF339AF0),
    "azure" to Color(0xFF1C7ED6),
    "indigo" to Color(0xFF5C7CFA),
    "purple" to Color(0xFF9775FA),
    "violet" to Color(0xFF7048E8),
    "magenta" to Color(0xFFD6409F),
    "pink" to Color(0xFFF783AC),
    "salmon" to Color(0xFFFF8787),
    "brown" to Color(0xFFA9745B),
    "wine" to Color(0xFF9A3B3B)
)

/** 全局主文字颜色：快照状态，切换即时全局刷新 */
val TextPrimary: Color get() = TEXT_COLORS[textColorKey] ?: Color.White

/** 全局按钮圆角形态：胶囊（默认）/ 圆角 / 方角，随「按钮样式」设置即时变化 */
fun buttonShape(): Shape = when (buttonStyleKey) {
    "square" -> androidx.compose.ui.graphics.RectangleShape
    "rounded" -> RoundedCornerShape(10.dp)
    else -> RoundedCornerShape(50)
}

/** 全局按钮填充：渐变底（与站源弹窗标题栏同一套），配合 buttonShape() 的裁剪使用 */
fun buttonFill(): Modifier = Modifier.background(Brush.horizontalGradient(AccentPill))

/** 按钮内文字/图标颜色：跟全局文字颜色走（站源条/搜索栏/筛选胶囊同一套） */
fun buttonTextColor(): Color = TextPrimary

/** Material Button 配色：渐变底配黑字 */
@Composable
fun materialButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color.Transparent,
    contentColor = Color.Black
)

/** Material Button 背景修饰符（按钮自身按 buttonShape() 裁剪） */
fun materialButtonBackground(): Modifier = Modifier.background(Brush.horizontalGradient(AccentPill))

/** Material Button 文字颜色：渐变底配黑字 */
fun materialButtonTextColor(): Color = Color.Black

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
/** 次要文字：跟全局文字颜色同一色调，降透明度区分主次——改文字颜色时全屏一起变 */
val TextSecondary: Color get() = TextPrimary.copy(alpha = 0.62f)

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
        content = {
            // 全局默认文字色：所有没写颜色的 Text/Icon 一律跟 TextPrimary（与强调色同一套快照原理）
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides TextPrimary
            ) {
                content()
            }
        }
    )
}
