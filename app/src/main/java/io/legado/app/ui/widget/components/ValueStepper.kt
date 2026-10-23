package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.SmallOutlinedButton
import io.legado.app.ui.widget.components.card.TextCard
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

@Composable
fun ValueStepper(
    value: Float,
    displayValue: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stepSize: Float = 1f,
    showDecimal: Boolean = false,
    valueFormat: ((Float) -> String)? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SmallOutlinedButton(
            onClick = {
                onValueChange(snapToStep(value - stepSize, valueRange, stepSize))
            },
            enabled = enabled,
            icon = Icons.Default.Remove,
            contentDescription = stringResource(R.string.a11y_decrease),
        )
        if (content != null) {
            content()
        } else {
            val displayText = valueFormat?.invoke(displayValue) ?: if (showDecimal) {
                "%.1f".format(displayValue)
            } else {
                displayValue.toInt().toString()
            }
            TextCard(
                cornerRadius = 8.dp,
                horizontalPadding = 8.dp,
                verticalPadding = 4.dp,
                text = displayText,
                backgroundColor = LegadoTheme.colorScheme.surfaceContainerHigh,
                contentColor = LegadoTheme.colorScheme.onSurface
            )
        }
        SmallOutlinedButton(
            onClick = {
                onValueChange(snapToStep(value + stepSize, valueRange, stepSize))
            },
            enabled = enabled,
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.a11y_increase),
        )
    }
}

/**
 * 步进值吸附到 [stepSize] 网格，并按步长的小数位消除浮点误差。
 *
 * Compose Slider 的 steps 只约束几何位置，实际值仍是 Float；直接加减会累积
 * 0.6999999 这类毛刺。显示层不应假设调用方已经归一化。
 */
private fun snapToStep(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    stepSize: Float,
): Float {
    if (!value.isFinite() || stepSize <= 0f) return value.coerceIn(range)
    val index = ((value - range.start) / stepSize).roundToInt()
    val raw = range.start + index * stepSize
    val factor = 10.0.pow(decimalsOf(stepSize))
    return (round(raw * factor) / factor).toFloat().coerceIn(range)
}

private fun decimalsOf(stepSize: Float): Int {
    var scaled = stepSize
    var decimals = 0
    while (decimals < 4 && abs(scaled - round(scaled)) > 1e-4f) {
        scaled *= 10f
        decimals++
    }
    return decimals
}
