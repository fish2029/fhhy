package com.fanhuatv.feature.live

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.LiveChannel
import com.fanhuatv.core.util.Resource
import com.fanhuatv.player.LivePlayBus

/** 直播页：读取直播源列表，点击进入直播播放。 */
@Composable
fun LiveScreen(
    config: AppConfig?,
    navController: NavController,
    vm: LiveViewModel = hiltViewModel()
) {
    val liveUrl by vm.liveUrl.collectAsStateWithLifecycle(initialValue = "")
    val channelsState by vm.channels.collectAsStateWithLifecycle()

    // 订阅直播源和手动直播源任一就绪都触发加载；config 变化也要重试——
    // 首次进页时配置可能还没加载完，只盯 liveUrl 会永远错过订阅里的直播源
    LaunchedEffect(liveUrl, config?.liveSources?.size) {
        if (liveUrl.isNotBlank() || config?.liveSources?.isNotEmpty() == true) vm.loadChannels(config)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = com.fanhuatv.ui.theme.BgSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("直播", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { navController.navigate(com.fanhuatv.navigation.Routes.CONFIG) }) {
                    Text("配置直播源")
                }
            }
        }

        if (liveUrl.isBlank() && config?.liveSources?.isEmpty() != false) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("尚未配置直播源", color = com.fanhuatv.ui.theme.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { navController.navigate(com.fanhuatv.navigation.Routes.CONFIG) }) {
                        Text("去配置")
                    }
                }
            }
            return@Column
        }

        when (val state = channelsState) {
            is Resource.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            is Resource.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.message, color = com.fanhuatv.ui.theme.TextPrimary)
                    Button(onClick = { vm.loadChannels() }) { Text("重试") }
                }
            }
            is Resource.Success -> {
                val grouped = state.data.groupBy { it.group }
                LazyColumn(contentPadding = PaddingValues(12.dp)) {
                    grouped.forEach { (group, list) ->
                        item {
                            Surface(color = com.fanhuatv.ui.theme.BgPrimary, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    group,
                                    color = com.fanhuatv.ui.theme.Accent,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            }
                        }
                        items(list) { channel ->
                            ChannelRow(channel) {
                                LivePlayBus.play(channel.url, channel.name)
                                navController.navigate(com.fanhuatv.navigation.Routes.PLAYER)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: LiveChannel, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = com.fanhuatv.ui.theme.BgCard)
    ) {
        Text(
            channel.name,
            color = com.fanhuatv.ui.theme.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.padding(16.dp)
        )
    }
}
