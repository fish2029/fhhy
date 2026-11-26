package com.fanhuatv.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.ui.theme.Accent

private val Success = Color(0xFF3DBE7A)

/** 安全状态页：展示签名校验结果、完整性标记，配合安全防护层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    navController: NavController,
    vm: SecurityViewModel = hiltViewModel()
) {
    val result by vm.result.collectAsStateWithLifecycle()

    Scaffold(topBar = { SubTopBar("安全状态", onBack = { navController.popBackStack() }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            val passed = result?.passed == true
            Icon(
                if (passed) Icons.Filled.VerifiedUser else Icons.Filled.GppBad,
                contentDescription = null,
                tint = if (passed) Success else Color.Red,
                modifier = Modifier.size(72.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (passed) "签名校验通过" else "签名校验未通过",
                color = if (passed) Success else Color.Red,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(24.dp))

            SecurityItem("SHA-256 指纹", result?.fingerprint?.ifBlank { "未获取" } ?: "检测中…")
            SecurityItem("校验说明", result?.reason ?: "正在校验 APK 签名…")
            SecurityItem("防二次打包", if (passed) "已启用" else "检测到风险")
            SecurityItem("核心逻辑保护", "NDK + R8 混淆已启用")

            Spacer(Modifier.height(24.dp))
            Button(onClick = { vm.recheck() }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
                Text("重新校验", color = Color.Black)
            }
        }
    }
}

@Composable
private fun SecurityItem(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = com.fanhuatv.ui.theme.BgSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(label, color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontSize = 13.sp)
        }
    }
}
