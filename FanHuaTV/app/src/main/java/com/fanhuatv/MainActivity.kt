package com.fanhuatv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fanhuatv.navigation.AppNavHost
import com.fanhuatv.ui.theme.FanHuaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FanHuaTheme(themeMode = "dark") {
                AppNavHost()
            }
        }
    }
}
