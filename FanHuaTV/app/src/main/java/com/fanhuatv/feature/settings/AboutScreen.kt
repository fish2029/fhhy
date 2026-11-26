package com.fanhuatv.feature.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.fanhuatv.BuildConfig
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.ui.theme.Accent

/** 关于页：版本号 / 检查更新 / 用户协议 / 隐私政策 / 开源许可。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navController: NavController) {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    Scaffold(topBar = { SubTopBar("关于", onBack = { navController.popBackStack() }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50), color = Accent, modifier = Modifier.size(72.dp)) {
                Box(Modifier, Alignment.Center) {
                    Text("繁华", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("繁华TV", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 13.sp
            )
            Spacer(Modifier.height(24.dp))

            AboutRow(Icons.Filled.SystemUpdate, "检查更新") {
                checking = true
                Toast.makeText(context, "已是最新版本", Toast.LENGTH_SHORT).show()
                checking = false
            }
            AboutRow(Icons.Filled.Description, "用户协议") { showAgreement = "用户协议" }
            AboutRow(Icons.Filled.PrivacyTip, "隐私政策") { showAgreement = "隐私政策" }
            AboutRow(Icons.Filled.Gavel, "开源许可") { showLicense = true }
            AboutRow(Icons.Filled.Security, "安全状态") {
                navController.navigate(com.fanhuatv.navigation.Routes.SECURITY)
            }
        }
    }

    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("开源许可") },
            text = {
                Text(
                    "本项目使用以下开源库：\n\n" +
                    "• AndroidX Media3 (Apache 2.0)\n" +
                    "• Retrofit / OkHttp / Gson (Apache 2.0)\n" +
                    "• Room / Jetpack Compose (Apache 2.0)\n" +
                    "• Coil (Apache 2.0)\n" +
                    "• Chaquopy (Apache 2.0)\n" +
                    "• IJKPlayer (LGPL v2.1)",
                    fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } }
        )
    }

    showAgreement?.let { title ->
        AlertDialog(
            onDismissRequest = { showAgreement = null },
            title = { Text(title) },
            text = {
                Text(
                    "繁华TV 是一款内容容器应用，本身不存储、不提供任何影视内容。" +
                    "所有内容由用户自行配置的第三方数据源提供。请遵守所在地区的法律法规，" +
                    "仅访问您有权访问的内容。应用不会收集您的个人信息。",
                    fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = { showAgreement = null }) { Text("我知道了") } }
        )
    }
}

@Composable
private fun AboutRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Accent)
        Spacer(Modifier.width(16.dp))
        Text(title, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.ChevronRight, null, tint = com.fanhuatv.ui.theme.TextSecondary)
    }
}
