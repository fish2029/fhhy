package com.fanhuatv.feature.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.rememberAsyncImagePainter
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.feature.settings.SettingsViewModel
import com.fanhuatv.player.LivePlayBus
import kotlinx.coroutines.launch

private data class HistoryRow(
    val id: Long,
    val title: String,
    val cover: String,
    val playUrl: String,
    val subtitle: String,
    /** 断点续播所需：影片标识与来源，点击时还原成轻量影片对象 */
    val vodId: String = "",
    val siteKey: String = "",
    val source: String = "json",
    val scriptId: String = "",
    val seekTo: Long = 0L,
    /** 上次看的这集的剧集 id，续播时重新解析新直链 */
    val episodeId: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    navController: NavController,
    favoriteOnly: Boolean = false,
    asTab: Boolean = false,
    vm: SettingsViewModel = hiltViewModel()
) {
    val repo = vm.repository
    val history by repo.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val favorites by repo.favorites.collectAsStateWithLifecycle(initialValue = emptyList())

    val rows: List<HistoryRow> = if (favoriteOnly) {
        favorites.map {
            HistoryRow(it.uid, it.title, it.cover, "", "已收藏")
        }
    } else {
        history.map {
            // 倒序展示（数据库已按时间倒排）；副标题带集数 + 进度百分比
            val pct = if (it.duration > 0) "已观看 ${it.progress * 100 / it.duration}%" else ""
            val sub = listOf(it.episode, pct).filter { s -> s.isNotBlank() }.joinToString(" · ")
            HistoryRow(
                it.uid, it.title, it.cover, it.playUrl, sub,
                vodId = it.vodId, siteKey = it.siteKey,
                source = it.source, scriptId = it.scriptId,
                seekTo = it.progress, episodeId = it.episodeId
            )
        }
    }

    Scaffold(
        topBar = {
            if (asTab) {
                // 作为底部主标签时没有返回键，只显示标题
                TopAppBar(
                    title = { Text(if (favoriteOnly) "我的收藏" else "观看历史") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = com.fanhuatv.ui.theme.BgSurface,
                        titleContentColor = Color.White
                    )
                )
            } else {
                SubTopBar(if (favoriteOnly) "我的收藏" else "观看历史", onBack = { navController.popBackStack() })
            }
        }
    ) { padding ->
        if (rows.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text(
                    if (favoriteOnly) "还没有收藏" else "还没有观看记录",
                    color = com.fanhuatv.ui.theme.TextSecondary
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                val scope = rememberCoroutineScope()
                var resuming by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (row.playUrl.isBlank() || resuming) return@clickable
                            // 断点续播：还原轻量影片对象（退出播放页时进度能继续落库），
                            // 并带上次的播放位置，播放器就绪后自动跳过去
                            val item = com.fanhuatv.core.model.VodItem(
                                id = row.vodId,
                                title = row.title,
                                cover = row.cover,
                                siteKey = row.siteKey,
                                source = row.source,
                                scriptId = row.scriptId,
                                playUrl = row.playUrl
                            )
                            scope.launch {
                                resuming = true
                                // 存下来的直链多带时效 token，过会儿就失效（表现为容器不支持），
                                // 有剧集 id 的先重新解析新直链；解析失败回退存的直链让播放器自己试
                                if (row.episodeId.isNotBlank()) {
                                    val fresh = runCatching {
                                        kotlinx.coroutines.withTimeoutOrNull(35_000) {
                                            repo.resolvePlayUrl(item, row.episodeId)
                                        }
                                    }.getOrNull()
                                    val url = (fresh as? com.fanhuatv.core.util.Resource.Success)?.data
                                        .orEmpty().ifBlank { row.playUrl }
                                    LivePlayBus.play(url, row.title, item, seekTo = row.seekTo)
                                } else {
                                    LivePlayBus.play(row.playUrl, row.title, item, seekTo = row.seekTo)
                                }
                                resuming = false
                                navController.navigate(com.fanhuatv.navigation.Routes.PLAYER)
                            }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(row.cover),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp, 88.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(row.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text(row.subtitle, color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
