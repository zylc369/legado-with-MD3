package io.legado.app.feature.customtts.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.feature.customtts.CustomTtsIntent
import io.legado.app.feature.customtts.CustomTtsUiState
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem

/** 倍速显示：保留到 0.01，去掉多余的尾零（0.95→"0.95"，0.9→"0.9"，1.0→"1.0"）。 */
internal fun formatSpeed(value: Float): String {
    val two = "%.2f".format(value)
    return if (two.endsWith('0')) "%.1f".format(value) else two
}

/** 混合朗读配置区块（首段兜底/兜底引擎与音色/双速度/本地调速/超时）。 */
@Composable
fun HybridSection(
    state: CustomTtsUiState,
    onIntent: (CustomTtsIntent) -> Unit,
) {
    SectionCard(
        title = "混合朗读",
        subtitle = "与角色配音互斥：混合模式使用默认音色 + 兜底音色",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.firstSegmentFallback,
                onCheckedChange = { onIntent(CustomTtsIntent.SetFirstSegmentFallback(it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("首段兜底 TTS")
        }
        Text(
            "开启：起播段未就绪立即用兜底（不等待）；关闭：起播段阻塞等待远端合成，超时才落兜底。其余段仍是无缝切换。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionPicker(
            label = "兜底引擎",
            value = state.fallbackEngines
                .firstOrNull {
                    it.engineType == state.fallbackEngineType &&
                        it.engineId == state.fallbackEngineId
                }?.label.orEmpty(),
            options = state.fallbackEngines.map { it.engineId to it.label },
            onSelect = { id ->
                state.fallbackEngines.firstOrNull { it.engineId == id }?.let {
                    onIntent(CustomTtsIntent.SetFallbackEngine(it.engineType, it.engineId))
                }
            },
        )
        OptionPicker(
            label = "兜底音色",
            value = state.fallbackVoices
                .firstOrNull { it.speakerId == state.fallbackSpeakerId }?.label.orEmpty(),
            options = state.fallbackVoices.map { it.speakerId to it.label },
            onSelect = { onIntent(CustomTtsIntent.SetFallbackVoice(it)) },
        )

        TinySliderSettingItem(
            title = "兜底速度 ${formatSpeed(state.fallbackSpeed)}x",
            value = state.fallbackSpeed,
            valueRange = 0.5f..3.0f,
            // 拖动条连续（steps=0），写入经 quantize 落到 0.01 网格：可设 0.95/0.92 这类值；
            // +/- 每次步进 0.1，吸附网格同样 0.01，从 0.92 按 + 到 1.02 而不是被拽回 0.1 格。
            steps = 0,
            stepSize = 0.1f,
            stepSnapSize = 0.01f,
            valueMinWidth = 52.dp,
            showDecimal = true,
            valueFormat = { formatSpeed(it) },
            onValueChange = { onIntent(CustomTtsIntent.SetFallbackSpeed(it)) },
        )
        TinySliderSettingItem(
            title = "定制速度 ${formatSpeed(state.customSpeed)}x",
            value = state.customSpeed,
            valueRange = 0.5f..3.0f,
            steps = 0,
            stepSize = 0.1f,
            stepSnapSize = 0.01f,
            valueMinWidth = 52.dp,
            showDecimal = true,
            valueFormat = { formatSpeed(it) },
            onValueChange = { onIntent(CustomTtsIntent.SetCustomSpeed(it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.localSpeed,
                onCheckedChange = { onIntent(CustomTtsIntent.SetLocalSpeed(it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("本地调速")
        }
        Text(
            "混合模式下以上两个速度就是最终倍速；全局朗读语速不参与。" +
                "拖动条精度 0.01（如 0.95x），加减号步进 0.1。\n" +
                "本地调速开启：定制速度只调本地播放，远端按 1.0x 合成（缓存跨速度复用）；" +
                "关闭：定制速度随请求发给远端。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingField("远程单段超时（毫秒）", state.remoteTimeoutMs, KeyboardType.Number) {
            onIntent(CustomTtsIntent.SetRemoteTimeout(it))
        }
    }
}

/** 分段设置区块（首段字数 = 阶梯首值；阶梯可自定义）。 */
@Composable
fun SegmentSection(
    state: CustomTtsUiState,
    onIntent: (CustomTtsIntent) -> Unit,
) {
    val stairs = state.segmentStairsText
        .split(',', '，', ' ', '、')
        .mapNotNull { it.trim().toIntOrNull() }
    SectionCard(
        title = "分段设置",
        subtitle = "首段字数 = 阶梯首值；修改首段字数时保留其余档位",
    ) {
        SettingField(
            label = "首段字数（默认 20）",
            value = stairs.firstOrNull()?.toString().orEmpty(),
            keyboardType = KeyboardType.Number,
        ) { value ->
            val n = value.toIntOrNull()?.coerceIn(5, 200) ?: return@SettingField
            val rest = stairs.drop(1).ifEmpty { listOf(40, 60, 80) }
            onIntent(CustomTtsIntent.SetSegmentStairs((listOf(n) + rest).joinToString(",")))
        }
        SettingField("阶梯序列（逗号分隔）", state.segmentStairsText) {
            onIntent(CustomTtsIntent.SetSegmentStairs(it))
        }
        SettingField("常规段目标", state.segmentTarget, KeyboardType.Number) {
            onIntent(CustomTtsIntent.SetSegmentTarget(it))
        }
    }
}

@Composable
private fun OptionPicker(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { open = true }) {
            Text(value.ifBlank { "选择" })
        }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("关闭") }
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { (id, name) ->
                        TextButton(
                            onClick = {
                                onSelect(id)
                                open = false
                            },
                        ) { Text(name) }
                    }
                }
            },
        )
    }
}
