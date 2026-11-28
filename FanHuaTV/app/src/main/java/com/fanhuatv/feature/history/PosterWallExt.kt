package com.fanhuatv.feature.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed

/** 无水波纹点击（可选长按）：海报墙整卡点击续播、长按删除用，避免网格里泛起大片涟漪 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.clickableNoRipple(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
): Modifier = composed {
    this.combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
        onLongClick = onLongClick
    )
}
