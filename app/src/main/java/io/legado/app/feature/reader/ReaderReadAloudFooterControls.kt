package io.legado.app.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.widget.components.button.series.MediumTonalButton

/**
 * 朗读时页脚中间的暂停/继续按钮。
 *
 * 「在本页朗读 / 跳转回朗读页」沿用脱离时原有的悬浮胶囊，不在这里重复。
 */
@Composable
fun ReaderReadAloudFooterControls(
    running: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
    onTogglePause: () -> Unit,
) {
    AnimatedVisibility(
        visible = running,
        modifier = modifier,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        MediumTonalButton(
            onClick = onTogglePause,
            icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = stringResource(if (paused) R.string.resume else R.string.pause),
        )
    }
}
