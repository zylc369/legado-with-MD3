package io.legado.app.ui.widget.components.checkBox

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme

/**
 * 多选场景的圆形勾选徽标（替代方正的 Material Checkbox）。
 *
 * 未选中 = 空心描边圆；选中 = 主题色实心圆 + 白色对勾，带缩放/淡入过渡。
 * 与 [AppCheckbox] 的区别：本组件只负责“表达选中”，不处理点击——整行点击负责切换，
 * 避免行点击与勾选点击双触发（书签多选的语义）。
 */
@Composable
fun SelectionCheckBadge(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val colors = LegadoTheme.colorScheme
    val fill by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        label = "selectionBadgeFill",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) {
            colors.primary
        } else {
            colors.onSurfaceVariant.copy(alpha = 0.55f)
        },
        label = "selectionBadgeOutline",
    )
    val checkScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.6f,
        label = "selectionBadgeCheckScale",
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        label = "selectionBadgeCheckAlpha",
    )

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            .border(BorderStroke(1.6.dp, outline), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = colors.onPrimary,
            modifier = Modifier
                .size(size * 0.62f)
                .graphicsLayer {
                    scaleX = checkScale
                    scaleY = checkScale
                    alpha = checkAlpha
                },
        )
    }
}
