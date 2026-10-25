package io.legado.app.ui.book.readaloud.engines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.playback.ReadAloudEngineGroupType
import io.legado.app.help.readaloud.playback.ReadAloudEngineOption
import io.legado.app.help.readaloud.playback.ReadAloudEngineScope
import io.legado.app.help.readaloud.playback.ReadAloudEngineTag
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.ToggleChip
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.TinyClickableSettingItem
import io.legado.app.ui.widget.components.text.AppText
import org.koin.compose.koinInject

/**
 * 「朗读引擎」快捷切换：入口行 + 分组面板。
 *
 * - 入口行常显**本书生效**的引擎与音色（本书未单独设置时继承全局默认）；
 * - 面板列出全部可用引擎（系统/HTTP/云，数量由运行时目录决定），点选即切换；
 * - 面板可见性与作用域选择由宿主自持：本组件可能被三个宿主同时组合
 *   （阅读菜单 / 听书浮层设置 / 独立设置页），共享 VM 里的开关会串场。
 */
@Composable
fun ReadAloudEngineQuickSwitch(
    bookUrl: String?,
    modifier: Modifier = Modifier,
    onManageEngines: () -> Unit,
    viewModel: ReadAloudEnginesViewModel = koinInject(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 面板可见性/作用域为宿主本地状态：见头注释
    var panelVisible by rememberSaveable { mutableStateOf(false) }
    var scope by rememberSaveable { mutableStateOf(ReadAloudEngineScope.Book) }
    LaunchedEffect(bookUrl) {
        viewModel.bindBook(bookUrl)
        scope = ReadAloudEngineScope.Book
    }

    val defaultVoice = stringResource(R.string.read_aloud_engine_default_voice)
    val voiceLabel = state.currentVoiceName.ifBlank { defaultVoice }
    val scopeLabel = stringResource(
        if (state.bookOverride) {
            R.string.read_aloud_engine_scope_book
        } else {
            R.string.read_aloud_engine_scope_global
        }
    )

    TinyClickableSettingItem(
        title = stringResource(R.string.read_aloud_engine_switch),
        description = stringResource(R.string.read_aloud_engine_switch_summary),
        modifier = modifier,
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                AppText(text = state.currentName.ifBlank { "—" }, fontSize = 13.sp)
                AppText(
                    text = "$voiceLabel · $scopeLabel",
                    fontSize = 11.sp,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = {
            viewModel.refresh()
            panelVisible = true
        },
    )

    AppModalBottomSheet(
        show = panelVisible,
        onDismissRequest = { panelVisible = false },
        title = stringResource(R.string.read_aloud_engine_switch),
        endAction = {
            Text(
                text = stringResource(R.string.read_aloud_engine_manage),
                color = LegadoTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clickable {
                        panelVisible = false
                        onManageEngines()
                    },
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            AppText(
                text = stringResource(R.string.read_aloud_engine_switch_hint),
                fontSize = 12.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            if (bookUrl != null) {
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ToggleChip(
                        label = stringResource(R.string.read_aloud_engine_scope_book),
                        selected = scope == ReadAloudEngineScope.Book,
                        onToggle = {
                            scope = ReadAloudEngineScope.Book
                            viewModel.setScope(scope)
                        },
                    )
                    ToggleChip(
                        label = stringResource(R.string.read_aloud_engine_scope_global),
                        selected = scope == ReadAloudEngineScope.Global,
                        onToggle = {
                            scope = ReadAloudEngineScope.Global
                            viewModel.setScope(scope)
                        },
                    )
                }
                AppText(
                    text = stringResource(
                        if (scope == ReadAloudEngineScope.Book) {
                            R.string.read_aloud_engine_scope_book_hint
                        } else {
                            R.string.read_aloud_engine_scope_global_hint
                        }
                    ),
                    fontSize = 11.sp,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (state.loadFailed) {
                TinyClickableSettingItem(
                    title = stringResource(R.string.read_aloud_engine_load_failed),
                    description = stringResource(R.string.read_aloud_engine_load_failed_summary),
                    imageVector = Icons.Default.Refresh,
                    onClick = viewModel::retry,
                )
            } else {
                state.groups.forEach { group ->
                    AppText(
                        text = stringResource(groupTitleRes(group.type)),
                        fontSize = 11.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                    )
                    group.options.forEach { option ->
                        EngineOptionRow(
                            option = option,
                            onClick = {
                                viewModel.select(option.engineType, option.engineId)
                                panelVisible = false
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineOptionRow(
    option: ReadAloudEngineOption,
    onClick: () -> Unit,
) {
    val defaultVoice = stringResource(R.string.read_aloud_engine_default_voice)
    val voiceLabel = option.voiceName.ifBlank { defaultVoice }
    TinyClickableSettingItem(
        title = option.name,
        description = "$voiceLabel · ${stringResource(typeDetailRes(option.engineType))}",
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val tagRes = when (option.tag) {
                    ReadAloudEngineTag.BookCurrent -> R.string.read_aloud_engine_tag_book
                    ReadAloudEngineTag.GlobalCurrent -> R.string.read_aloud_engine_tag_global
                    ReadAloudEngineTag.None -> null
                }
                if (tagRes != null) {
                    AppText(
                        text = stringResource(tagRes),
                        fontSize = 10.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (option.selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = LegadoTheme.colorScheme.primary,
                    )
                }
            }
        },
        onClick = onClick,
    )
}

private fun groupTitleRes(type: ReadAloudEngineGroupType): Int = when (type) {
    ReadAloudEngineGroupType.System -> R.string.read_aloud_engine_group_system
    ReadAloudEngineGroupType.Http -> R.string.read_aloud_engine_group_http
    ReadAloudEngineGroupType.Cloud -> R.string.read_aloud_engine_group_cloud
}

private fun typeDetailRes(engineType: String): Int = when (engineType) {
    ReadAloudVoice.ENGINE_HTTP -> R.string.read_aloud_engine_detail_http
    ReadAloudVoice.ENGINE_CLOUD -> R.string.read_aloud_engine_detail_cloud
    else -> R.string.read_aloud_engine_detail_system
}
