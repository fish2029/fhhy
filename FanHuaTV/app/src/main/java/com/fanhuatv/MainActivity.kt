package com.fanhuatv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.fanhuatv.core.storage.SettingsDataStore
import com.fanhuatv.core.storage.UiSettings
import com.fanhuatv.navigation.AppNavHost
import com.fanhuatv.ui.theme.FanHuaTheme
import com.fanhuatv.ui.theme.applyAccent
import com.fanhuatv.ui.theme.applyButtonStyle
import com.fanhuatv.ui.theme.applyTextColor
import com.fanhuatv.ui.theme.applyThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 界面设置驱动全局：外观模式 / 强调色 / 界面缩放（旧存档缺字段时用默认值兜底）
            val ui by settings.ui.collectAsState(initial = UiSettings())

            // 强调色是全局快照状态，设置一变所有直接引用 Accent 的地方自动刷新
            applyAccent(ui.accent)
            // 外观模式同样走快照状态：三档背景深浅即时切换
            applyThemeMode(ui.themeMode)
            // 按钮样式与全局文字颜色：快照状态，切换即时全局刷新
            applyButtonStyle(ui.buttonStyle)
            applyTextColor(ui.textColor)

            FanHuaTheme(themeMode = ui.themeMode) {
                // 界面缩放：按百分比放大字体（布局密度不变，避免小屏溢出）
                val current = LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalDensity provides Density(current.density, (ui.fontScale ?: 100) / 100f)
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
