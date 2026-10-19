package io.legado.app.feature.customtts.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.opensecurity.customtts.model.CustomTtsParams
import com.opensecurity.customtts.model.CustomTtsVoiceParams
import io.legado.app.feature.customtts.CustomTtsIntent
import io.legado.app.feature.customtts.CustomTtsUiState
import io.legado.app.feature.customtts.CustomTtsVoiceItemUi
import kotlin.math.roundToInt

/** 音色目录区块（拉取/默认/参数编辑/试听）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSection(
    state: CustomTtsUiState,
    onIntent: (CustomTtsIntent) -> Unit,
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    SectionCard(
        title = "音色",
        subtitle = "服务端音色目录（经本地网关拉取）",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onIntent(CustomTtsIntent.RefreshVoices) },
                enabled = !state.catalogLoading,
            ) {
                Text(if (state.catalogLoading) "刷新中…" else "刷新目录")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                state.catalogError ?: "${state.voices.size} 个音色",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        state.voices.forEach { voice ->
            VoiceRow(
                voice = voice,
                isDefault = state.defaultVoiceId == voice.id,
                onEditParams = { editingId = voice.id },
                onIntent = onIntent,
            )
        }
    }
    editingId?.let { id ->
        val voice = state.voices.firstOrNull { it.id == id }
        if (voice != null) {
            VoiceParamsSheet(
                voice = voice,
                onDismiss = { editingId = null },
                onSave = { params ->
                    onIntent(CustomTtsIntent.SaveVoiceParams(id, params))
                    editingId = null
                },
                onPreview = { params -> onIntent(CustomTtsIntent.PreviewVoice(id, params)) },
            )
        }
    }
}

@Composable
private fun VoiceRow(
    voice: CustomTtsVoiceItemUi,
    isDefault: Boolean,
    onEditParams: () -> Unit,
    onIntent: (CustomTtsIntent) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(voice.name, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.width(8.dp))
            if (isDefault) {
                AssistChip(onClick = {}, label = { Text("默认") })
            }
            if (!voice.available) {
                Text("（不可用）", style = MaterialTheme.typography.bodySmall)
            }
        }
        val detail = listOfNotNull(
            voice.gender.takeIf { it.isNotBlank() },
            voice.description.takeIf { it.isNotBlank() },
            "速度 %.1fx".format(voice.params.speed),
            voice.params.instruct.takeIf { it.isNotBlank() }?.let { "指令:$it" },
        ).joinToString(" · ")
        Text(detail, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onEditParams) { Text("参数") }
            TextButton(
                onClick = { onIntent(CustomTtsIntent.SetDefaultVoice(voice.id)) },
                enabled = voice.available,
            ) { Text("设为默认") }
            TextButton(
                onClick = { onIntent(CustomTtsIntent.PreviewVoice(voice.id)) },
                enabled = voice.available,
            ) { Text("试听") }
        }
    }
}

/** 音色参数编辑（底部弹层：速度/指令/种子/模式）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceParamsSheet(
    voice: CustomTtsVoiceItemUi,
    onDismiss: () -> Unit,
    onSave: (CustomTtsVoiceParams) -> Unit,
    onPreview: (CustomTtsVoiceParams) -> Unit,
) {
    var speed by remember { mutableFloatStateOf(voice.params.speed) }
    var instruct by remember { mutableStateOf(voice.params.instruct) }
    var seedText by remember {
        mutableStateOf(if (voice.params.seed >= 0) voice.params.seed.toString() else "")
    }
    var modeInstruct by remember { mutableStateOf(voice.params.mode == CustomTtsParams.MODE_INSTRUCT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("音色参数 · ${voice.name}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            TinySliderSettingItem(
                title = "速度 %.1fx".format(speed),
                value = speed,
                valueRange = CustomTtsParams.SPEED_MIN..CustomTtsParams.SPEED_MAX,
                steps = 24,
                onValueChange = { speed = (it * 10).roundToInt() / 10f },
            )

            OutlinedTextField(
                value = instruct,
                onValueChange = { if (it.length <= CustomTtsParams.INSTRUCT_MAX) instruct = it },
                label = { Text("朗读指令（≤${CustomTtsParams.INSTRUCT_MAX} 字）") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = seedText,
                onValueChange = { seedText = it.filter(Char::isDigit).take(5) },
                label = { Text("种子（留空=默认）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(
                    onClick = { modeInstruct = false },
                    label = { Text(if (!modeInstruct) "✓ 默认（情感）" else "默认（情感）") },
                )
                AssistChip(
                    onClick = { modeInstruct = true },
                    label = { Text(if (modeInstruct) "✓ 指令控制" else "指令控制") },
                )
            }
            fun draftParams(): CustomTtsVoiceParams = voice.params.copy(
                voiceId = voice.id,
                displayName = voice.params.displayName.ifEmpty { voice.name },
                speed = speed,
                instruct = instruct.trim(),
                seed = seedText.toIntOrNull() ?: -1,
                mode = if (modeInstruct) {
                    CustomTtsParams.MODE_INSTRUCT
                } else {
                    CustomTtsParams.MODE_DEFAULT
                },
            )
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = { onPreview(draftParams()) }) { Text("试听") }
                Button(onClick = { onSave(draftParams()) }) { Text("保存") }
            }
        }
    }
}
