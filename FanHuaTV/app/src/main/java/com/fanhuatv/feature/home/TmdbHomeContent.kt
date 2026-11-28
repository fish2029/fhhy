package com.fanhuatv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fanhuatv.feature.history.TmdbClient
import com.fanhuatv.feature.history.TmdbItem
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.BgCard
import com.fanhuatv.ui.theme.TextPrimary
import com.fanhuatv.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 首页 TMDB 数据：拉两页热门电影，前 5 条有横图的做轮播，其余进格子 */
@Composable
internal fun rememberTmdbTrending(key: String): List<TmdbItem> {
    var items by remember { mutableStateOf<List<TmdbItem>>(emptyList()) }
    LaunchedEffect(key) {
        items = withContext(Dispatchers.IO) {
            TmdbClient.trending("movie", key, 1) + TmdbClient.trending("movie", key, 2)
        }.distinctBy { it.title }
    }
    return items
}

/** TMDB 轮播大图：横版大图自动轮播，压片名 + 评分，点图匹配片源 */
@Composable
internal fun TmdbBanner(
    items: List<TmdbItem>,
    onClick: (String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()

    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(5000)
                val next = (pagerState.currentPage + 1) % items.size
                scope.launch { pagerState.animateScrollToPage(next) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
    ) {
        HorizontalPager(state = pagerState) { page ->
            val item = items[page]
            AsyncImage(
                model = item.backdrop.ifBlank { item.poster },
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onClick(item.title) }
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                        )
                    )
            ) {
                Text(
                    item.title,
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                )
                if (item.rating.isNotBlank()) {
                    Text(
                        "★ ${item.rating}",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            repeat(items.size) { index ->
                Box(
                    modifier = Modifier
                        .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (pagerState.currentPage == index) Accent
                            else Color.White.copy(alpha = 0.5f)
                        )
                )
            }
        }
    }
}

/** TMDB 影片格子：3 列海报 + 评分角标 + 年份，点整卡匹配片源 */
@Composable
internal fun TmdbGrid(
    items: List<TmdbItem>,
    matching: Boolean,
    onClick: (String) -> Unit
) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Text("热门影视加载中…", color = TextSecondary)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.title + it.year }) { item ->
            Column(
                modifier = Modifier.clickable { onClick(item.title) }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    AsyncImage(
                        model = item.poster,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(BgCard),
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(BgCard)
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                                )
                            )
                    )
                    Text(
                        item.title,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )
                    if (item.rating.isNotBlank()) {
                        Text(
                            "★${item.rating}",
                            color = Color(0xFFFFC107),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                        )
                    }
                }
                if (item.year.isNotBlank()) {
                    Text(
                        item.year,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                    )
                }
            }
        }
    }
}
