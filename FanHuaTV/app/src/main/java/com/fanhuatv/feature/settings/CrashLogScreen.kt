package com.fanhuatv.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.ui.theme.Accent
import java.io.File

/**
 * 崩溃日志页：CrashGuard 把未捕获异常写在 filesDir/logs/crash_log.txt，
 * 用户侧一直拿不到，出问题只能靠猜。这里给个入口直接看 / 复制 / 清空，
 * 闪退后把内容发出来就能定位到具体行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogScreen(navController: NavController) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val logFile = remember { File(context.filesDir, "logs/crash_log.txt") }
    var logText by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf(false) }

    LaunchedEffect(cleared) {
        logText = if (logFile.exists()) runCatching { logFile.readText() }.getOrNull().orEmpty() else ""
    }

    Scaffold(topBar = { SubTopBar("崩溃日志", onBack = { navController.popBackStack() }) }) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            val shown = logText.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (shown.isEmpty()) "暂无崩溃记录" else "最近 ${shown.length} 字符",
                    color = com.fanhuatv.ui.theme.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    if (shown.isNotBlank()) clipboard.setText(AnnotatedString(shown))
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制", color = Accent)
                }
                TextButton(onClick = {
                    runCatching { logFile.delete() }
                    cleared = !cleared
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("清空", color = Accent)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                shown.ifBlank { "没有崩溃记录，出问题时这里会有堆栈，复制发给我就行。" },
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f)
            )
        }
    }
}
