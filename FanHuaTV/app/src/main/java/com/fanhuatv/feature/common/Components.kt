package com.fanhuatv.feature.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fanhuatv.core.model.VodItem
import com.fanhuatv.ui.theme.Accent
import com.fanhuatv.ui.theme.BgCard
import com.fanhuatv.ui.theme.TextSecondary

/** 通用二级页顶栏 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubTopBar(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = com.fanhuatv.ui.theme.BgSurface,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White
        )
    )
}

/** 影片卡片：封面 + 标题 + 评分。showExtra=false 时隐藏评分角标（海报下方信息显示-仅标题）。
 *  posterHeight 非空时封面用固定高度（首页按剩余空间反算，保证两排完整显示），否则按宽高比。 */
@Composable
fun VodCard(item: VodItem, onClick: () -> Unit, modifier: Modifier = Modifier, showExtra: Boolean = true, ratio: Float = 2f / 3f, posterHeight: androidx.compose.ui.unit.Dp? = null) {
    Column(modifier = modifier.clickable(onClick = onClick)) {
        Box(modifier = (posterHeight?.let { Modifier.fillMaxWidth().height(it) }
                ?: Modifier.fillMaxWidth().aspectRatio(ratio)).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = androidx.compose.ui.graphics.painter.ColorPainter(BgCard),
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(BgCard)
            )
            if (showExtra && item.displayRating.isNotBlank()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(item.displayRating, color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
        // 标题独立于海报图下方，单行省略：任意列数下都不与图内文字冲突
        Text(
            item.displayTitle,
            color = Color.White,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp)
        )
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
        if (action != null) {
            Text(action, color = Accent, fontSize = 12.sp, modifier = Modifier.clickable { onAction() })
        }
    }
}
