package com.fanhuatv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 全局默认强调色：紫色填充胶囊（搜索栏/按钮/chips 默认态统一用这套紫） */
val Accent = Color(0xFF9D5CE0)
/** 选中态更深一档的紫（与默认态区分） */
val AccentDeep = Color(0xFF7A45E0)
/** 全局按钮/胶囊统一的紫色渐变（与站源弹窗标题栏同一套） */
val AccentPill = listOf(Color(0xFF7B5FE0), Color(0xFFB06DE6))
val BgPrimary = Color(0xFF0B0C10)
val BgSurface = Color(0xFF16181D)
val BgCard = Color(0xFF1F2229)
val TextSecondary = Color(0xFF9AA0AA)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    background = BgPrimary,
    onBackground = Color.White,
    surface = BgSurface,
    onSurface = Color.White,
    surfaceVariant = BgCard,
    error = Color(0xFFE24B4A)
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.Black,
    background = Color.White,
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF16181D)
)

@Composable
fun FanHuaTheme(themeMode: String = "dark", content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
