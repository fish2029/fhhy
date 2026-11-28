package com.fanhuatv.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.core.storage.entity.HistoryEntity
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.feature.settings.SettingsViewModel
import com.fanhuatv.player.LivePlayBus
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.TextPrimary
import com.fanhuatv.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 观影海报墙：观看历史铺成满屏海报，点海报直接续播，长按删除记录。
 * 海报底部有真实观看进度条，数据全部来自本地观看记录，不依赖任何账号。
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
    var deleteTarget by remember { mutableStateOf<HistoryEntity?>(null) }

    // 同一部剧只留最新一条（历史按时间倒排，首见即最新）
    val walls = remember(history) {
        history.distinctBy { it.vodId.ifBlank { it.title } }
    }

    Scaffold(
        topBar = { SubTopBar("观影海报墙", onBack = { navController.popBackStack() }) }
    ) { padding ->
        if (walls.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("还没有观看记录", color = TextSecondary)
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
                // 真实观看进度：progress/duration，与观看历史同一口径
                val fraction = remember(h.uid, h.progress, h.duration) {
                    if (h.duration > 0) (h.progress.toFloat() / h.duration).coerceIn(0f, 1f) else null
                }
                // 影视资料匹配：拿剧名搜高清海报与资料，搜不到保持原样
                var tm by remember(h.title) { mutableStateOf<TmShow?>(null) }
                LaunchedEffect(h.title) {
                    tm = withContext(Dispatchers.IO) { TvmazeClient.search(h.title) }
                }
                val show = tm
                val poster = show?.poster?.takeIf { it.isNotBlank() } ?: h.cover
                val infoLine = show?.let {
                    listOf(it.originalName.takeIf { n -> n.isNotBlank() && n != h.title },
                        it.year.takeIf { y -> y.isNotBlank() },
                        it.rating.takeIf { r -> r.isNotBlank() }?.let { r -> "TMaze $r" })
                        .filterNotNull().joinToString(" · ")
                }.orEmpty()
                Column(
                    modifier = Modifier
                        .clickableNoRipple(
                            onClick = {
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
                            },
                            onLongClick = { deleteTarget = h }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(2f / 3f)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    ) {
                        AsyncImage(
                            model = poster,
                            contentDescription = h.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            error = androidx.compose.ui.graphics.painter.ColorPainter(com.fanhuatv.ui.theme.BgCard),
                            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(com.fanhuatv.ui.theme.BgCard)
                        )
                        // 海报底部真实观看进度条
                        if (fraction != null) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(Color.Black.copy(alpha = 0.45f))
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(Accent)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        h.title,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (infoLine.isNotBlank()) {
                        Text(
                            infoLine,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (h.episode.isNotBlank()) {
                        Text(
                            h.episode,
                            color = TextSecondary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除观看记录", color = TextPrimary) },
            text = { Text("把《${target.title}》从观影记录里删掉？海报墙上也会消失。", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    val id = target.vodId
                    deleteTarget = null
                    scope.launch { repo.deleteHistory(id) }
                }) { Text("删除", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消", color = TextSecondary) }
            }
        )
    }
}
