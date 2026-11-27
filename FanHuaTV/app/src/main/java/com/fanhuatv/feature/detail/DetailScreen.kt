package com.fanhuatv.feature.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.fanhuatv.core.model.AppConfig
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.feature.common.SubTopBar
import com.fanhuatv.player.LivePlayBus

/** 详情页：简介、评分、剧集列表、收藏、播放入口。播放地址由 PY 脚本或 JSON 解析。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vodId: String,
    config: AppConfig?,
    navController: NavController,
    vm: DetailViewModel = hiltViewModel()
) {
    val item = remember(config, vodId) {
        // 优先用点进详情时登记的那份影片（分类/搜索来的带正确 jar/py 标记），
        // 再回退首页列表——不同站点同名片很多，先查首页会错拿别家源，
        // 导致 jar 站点的片子被送进脚本引擎报"脚本错误/parse=1"
        com.fanhuatv.player.LivePlayBus.findItem(vodId)
            ?: config?.homeItems?.firstOrNull { it.id == vodId || it.title == vodId }
    }
    val enriched by vm.detail.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val altSources by vm.altSources.collectAsStateWithLifecycle()
    val altLoading by vm.altLoading.collectAsStateWithLifecycle()

    LaunchedEffect(item) { item?.let { vm.loadDetail(it) } }
    val shown = enriched ?: item

    // jar 配置项（带 action）不是影片：不自动播放、不换源搜索，
    // 点它本该在列表里执行动作；万一进了详情页只给提示
    val isJarAction = shown?.source == "jar" && shown?.action?.isNotBlank() == true

    // 详情无可播内容（无剧集无地址，豆瓣源等元数据源）时，自动全站搜同名片子供换源；
    // 每个片名只搜一次，换到有剧集的源后不再触发
    var altSearchedFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(shown?.id, shown?.episodes?.isEmpty()) {
        if (isJarAction) return@LaunchedEffect
        val cur = shown ?: return@LaunchedEffect
        if (cur.episodes.isEmpty() && cur.playUrl.isBlank() && altSearchedFor != cur.displayTitle) {
            altSearchedFor = cur.displayTitle
            vm.searchAltSources(cur.displayTitle)
        }
    }

    // 点剧名进来就自动播第一集；只触发一次，从播放器返回详情页不会重播
    var autoPlayed by remember(vodId) { mutableStateOf(false) }
    LaunchedEffect(shown, autoPlayed) {
        if (autoPlayed || isJarAction) return@LaunchedEffect
        val cur = shown ?: return@LaunchedEffect
        autoPlayed = true
        LivePlayBus.pendingDetail = cur
        vm.autoPlay(cur) { detail, url ->
            LivePlayBus.play(url, detail.displayTitle, detail)
            navController.navigate(com.fanhuatv.navigation.Routes.PLAYER)
        }
    }

    var isFav by remember { mutableStateOf(false) }
    LaunchedEffect(vodId) { isFav = vm.isFavorite(vodId) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                SubTopBar(
                    title = shown?.displayTitle ?: "详情",
                onBack = { navController.popBackStack() },
                actions = {
                    IconButton(onClick = {
                        shown?.let {
                            vm.toggleFavorite(it)
                            isFav = !isFav
                        }
                    }) {
                        Icon(
                            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "收藏",
                            tint = if (isFav) Color.Red else Color.White
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (shown == null) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("未找到影片信息", color = com.fanhuatv.ui.theme.TextPrimary)
            }
            return@Scaffold
        }
        val cur = shown

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            AsyncImage(
                model = cur.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )

            Column(modifier = Modifier.padding(16.dp)) {
                Text(cur.displayTitle, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "评分 ${cur.displayRating.ifBlank { "暂无" }}  ·  ${cur.year}  ·  ${cur.category}",
                    color = com.fanhuatv.ui.theme.TextSecondary,
                    fontSize = 13.sp
                )
                // 来源站点标识：一眼看出当前详情是谁家的页面，
                // 避免同名片子多站点条目长得一样、点进去分不清（咕噜排查教训）
                // 优先用条目自带的中文站名；兜底才去站点表/查 key，绝不露出 csp_xxx
                val siteLabel = when {
                    cur.source == "dbapi" -> "豆瓣"
                    cur.siteName.isNotBlank() -> cur.siteName
                    cur.source == "py" -> cur.siteKey
                    else -> config?.sites?.firstOrNull { it.key == cur.siteKey }?.name
                        ?: cur.siteKey
                }
                if (siteLabel.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "来源：$siteLabel",
                        color = com.fanhuatv.ui.theme.TextSecondary,
                        fontSize = 12.sp
                    )
                }
                if (cur.desc.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(cur.desc, color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 13.sp, maxLines = 3)
                }
                Spacer(Modifier.height(16.dp))

                // 多线路分组：jar 详情的 vod_play_from/vod_play_url 按 $$$ 一一对应，
                // 一部片子常有整条失效的线路，必须能切；单线路不显示切换行
                val lines = cur.episodes.map { it.playFrom.ifBlank { "默认线路" } }.distinct()
                var selectedLine by remember(cur.id, lines.size) { mutableStateOf(lines.firstOrNull()) }
                val lineEps = if (lines.size > 1) {
                    cur.episodes.filter { it.playFrom.ifBlank { "默认线路" } == selectedLine }
                } else cur.episodes

                Button(
                    onClick = {
                        // 无剧集无地址（豆瓣源等元数据源）：只提示手动换源，不自动换站
                        if (cur.episodes.isEmpty() && cur.playUrl.isBlank()) {
                            vm.notifyManualSwitch()
                        } else {
                            // 立即播放 = 播当前线路第一集；空列表时才回退旧行为
                            val first = lineEps.firstOrNull()
                            vm.play(cur, first?.playableUrl ?: "", first?.playFrom ?: "") { url ->
                                LivePlayBus.play(url, cur.displayTitle, cur)
                                navController.navigate(com.fanhuatv.navigation.Routes.PLAYER)
                            }
                        }
                    },
                    colors = com.fanhuatv.ui.theme.materialButtonColors(),
                    border = com.fanhuatv.ui.theme.materialButtonBorder(),
                    modifier = Modifier.fillMaxWidth().then(com.fanhuatv.ui.theme.materialButtonBackground())
                ) {
                    Text("立即播放", color = com.fanhuatv.ui.theme.materialButtonTextColor())
                }

                if (cur.episodes.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    // 与「选集」同款分区：上面「线路」标题 + 具体线路，下面「选集」标题 + 集数
                    if (lines.size > 1) {
                        Text("线路", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        // 一排三个等宽胶囊：与播放页线路区同款，每行重量均分；
                        // 名字再长只在本格内省略，不折成 2+1、不把第三条挤出屏幕
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            lines.chunked(3).forEach { rowLines ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rowLines.forEach { line ->
                                        // 线路名常带括注废话（点击换线/勿信广告），全角/半角一并剥掉
                                        val shortLine = line.substringBefore("（")
                                            .substringBefore("(").trim().ifBlank { line }
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(50))
                                                .background(if (line == selectedLine) com.fanhuatv.ui.theme.AccentDeep else com.fanhuatv.ui.theme.Accent)
                                                .clickable { selectedLine = line }
                                                .padding(vertical = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                shortLine,
                                                color = Color.Black,
                                                fontSize = 11.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Text("选集", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(64.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(lineEps) { _, ep ->
                            Button(
                                onClick = { vm.play(cur, ep.playableUrl, ep.playFrom) { url ->
                                    LivePlayBus.play(url, "${cur.displayTitle} ${ep.name}", cur)
                                    navController.navigate(com.fanhuatv.navigation.Routes.PLAYER)
                                } },
                                colors = com.fanhuatv.ui.theme.materialButtonColors(),
                                border = com.fanhuatv.ui.theme.materialButtonBorder(),
                                modifier = Modifier.height(40.dp).then(com.fanhuatv.ui.theme.materialButtonBackground())
                            ) {
                                Text(ep.name.ifBlank { "播放" }, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // 换源：详情既没剧集也没地址（豆瓣源等元数据源）时，全站搜同名片子
                if (cur.episodes.isEmpty() && cur.playUrl.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    Text("换源", color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (altLoading && altSources.isEmpty()) {
                        Text("正在全站找片源…", color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 13.sp)
                    } else if (altSources.isEmpty()) {
                        Text("没找到同名片子，换个站点搜搜看吧", color = com.fanhuatv.ui.theme.TextSecondary, fontSize = 13.sp)
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            altSources.forEach { alt ->
                                // 换源按钮显示对应站点中文名，而不是 key/csp_xxx
                                val altName = alt.siteName.ifBlank {
                                    config?.sites?.firstOrNull { it.key == alt.siteKey }?.name
                                        ?: alt.siteKey
                                }.ifBlank { "站点" }
                                Button(
                                    onClick = { vm.loadDetail(alt) },
                                    colors = com.fanhuatv.ui.theme.materialButtonColors(),
                                    border = com.fanhuatv.ui.theme.materialButtonBorder(),
                                    modifier = Modifier.height(36.dp).then(com.fanhuatv.ui.theme.materialButtonBackground())
                                ) {
                                    Text(altName, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // 解析播放地址时的提示层：让用户知道正在处理，而不是界面卡死
        if (notice.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(
                        com.fanhuatv.ui.theme.BgSurface.copy(alpha = 0.92f),
                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(notice, color = com.fanhuatv.ui.theme.TextPrimary, fontSize = 14.sp)
            }
        }
    }
}
