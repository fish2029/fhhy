package com.fanhuatv.feature.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.feature.settings.SettingsViewModel
import com.fanhuatv.player.LivePlayBus
import kotlinx.coroutines.launch

/**
 * 观影海报墙：观看历史铺成满屏海报，点海报直接续播。
 * 数据全部来自本地观看记录，不依赖任何账号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosterWallScreen(
    navController: NavController,
    vm: SettingsViewModel = hiltViewModel()
) {
    val repo = vm.repository
    val history by repo.history.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    // 同一部剧只留最新一条（历史按时间倒排，首见即最新）
    val walls = remember(history) {
        history.distinctBy { it.vodId.ifBlank { it.title } }
    }

    Scaffold(
        topBar = { SubTopBar("观影海报墙", onBack = { navController.popBackStack() }) }
    ) { padding ->
        if (walls.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("还没有观看记录", color = com.fanhuatv.ui.theme.TextSecondary)
            }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            items(walls, key = { it.uid }) { h ->
                // 续播逻辑与观看历史一致：带剧集 id 的先重新解析新直链，失败回退存的直链
                var resuming by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier.clickableNoRipple {
                        if (resuming) return@clickableNoRipple
                        val item = com.fanhuatv.core.model.VodItem(
                            id = h.vodId,
                            title = h.title,
                            cover = h.cover,
                            siteKey = h.siteKey,
                            source = h.source,
                            scriptId = h.scriptId,
                            playUrl = h.playUrl
                        )
                        scope.launch {
                            com.fanhuatv.player.LivePlayBus.clearMediaExtras()
                            resuming = true
                            if (h.episodeId.isNotBlank()) {
                                val fresh = runCatching {
                                    kotlinx.coroutines.withTimeoutOrNull(35_000) {
                                        repo.resolvePlayUrl(item, h.episodeId)
                                    }
                                }.getOrNull()
                                val url = (fresh as? com.fanhuatv.core.util.Resource.Success)?.data
                                    .orEmpty().ifBlank { h.playUrl }
                                LivePlayBus.play(url, h.title, item, seekTo = h.progress)
                            } else {
                                LivePlayBus.play(h.playUrl, h.title, item, seekTo = h.progress)
                            }
                            resuming = false
                            navController.navigate(com.fanhuatv.navigation.Routes.PLAYER)
                        }
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    ) {
                        AsyncImage(
                            model = h.cover,
                            contentDescription = h.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = androidx.compose.ui.graphics.painter.ColorPainter(com.fanhuatv.ui.theme.BgCard),
                            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(com.fanhuatv.ui.theme.BgCard)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        h.title,
                        color = com.fanhuatv.ui.theme.TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
