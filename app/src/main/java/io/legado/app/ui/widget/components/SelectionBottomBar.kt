package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.text.AppText
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.FloatingToolbar as MiuixFloatingToolbar
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton

data class SelectionActions(
    val primaryAction: ActionItem,
    val secondaryActions: List<ActionItem> = emptyList(),
    val onClearSelection: (() -> Unit)? = null,
    val onSelectAll: () -> Unit,
    val onSelectInvert: () -> Unit,
)

data class ActionItem(
    val text: String,
    val icon: ImageVector = Icons.Default.ExpandMore,
    /** false 时图标按钮置灰不可点（如未选中任何项时的“删除”）。 */
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionBottomBar(
    modifier: Modifier = Modifier,
    /** null = 不显示“全选”（如书签多选只需删除/取消）。 */
    onSelectAll: (() -> Unit)? = null,
    /** null = 不显示“反选”。 */
    onSelectInvert: (() -> Unit)? = null,
    primaryAction: ActionItem,
    secondaryActions: List<ActionItem> = emptyList(),
    /** 常显的“取消/退出选择”动作（不进“更多”菜单）。 */
    dismissAction: ActionItem? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val isMiuix = ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)
    val selectAllDescription = stringResource(R.string.select_all)
    val invertSelectionDescription = stringResource(R.string.invert_selection)
    val moreActionsDescription = stringResource(R.string.more_menu)
    val safeModifier = modifier.windowInsetsPadding(
        WindowInsets.navigationBars
            .union(WindowInsets.tappableElement)
            .union(WindowInsets.ime)
    )

    if (isMiuix) {
        MiuixFloatingToolbar(
            modifier = safeModifier
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(8.dp)
            ) {
                if (onSelectAll != null) {
                    MiuixIconButton(onClick = onSelectAll) {
                        MiuixIcon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = selectAllDescription
                        )
                    }
                }
                if (onSelectInvert != null) {
                    MiuixIconButton(onClick = onSelectInvert) {
                        MiuixIcon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = invertSelectionDescription
                        )
                    }
                }

                // Miuix 的 enabled=false 只拦点击、不改配色：禁用态自行降透明
                MiuixIconButton(
                    onClick = primaryAction.onClick,
                    enabled = primaryAction.enabled,
                    backgroundColor = if (primaryAction.enabled) {
                        MiuixTheme.colorScheme.secondaryContainer
                    } else {
                        MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                    },
                    minWidth = 64.dp
                ) {
                    MiuixIcon(
                        imageVector = primaryAction.icon,
                        contentDescription = primaryAction.text,
                        tint = if (primaryAction.enabled) {
                            MiuixTheme.colorScheme.onSecondaryContainer
                        } else {
                            MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.38f)
                        }
                    )
                }

                dismissAction?.let { dismiss ->
                    MiuixIconButton(onClick = dismiss.onClick) {
                        MiuixIcon(
                            imageVector = dismiss.icon,
                            contentDescription = dismiss.text
                        )
                    }
                }

                if (secondaryActions.isNotEmpty()) {
                    Box {
                        MiuixIconButton(onClick = { showMenu = true }) {
                            MiuixIcon(
                                imageVector = AppIcons.MoreVert,
                                contentDescription = moreActionsDescription
                            )
                        }
                        RoundDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            secondaryActions.forEach { action ->
                                RoundDropdownMenuItem(
                                    text = action.text,
                                    onClick = {
                                        action.onClick()
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        HorizontalFloatingToolbar(
            modifier = safeModifier,
            expanded = true,
            leadingContent = {
                if (onSelectAll != null) {
                    IconButton(onClick = onSelectAll) {
                        AppIcon(
                            imageVector = Icons.Default.SelectAll,
                            contentDescription = selectAllDescription
                        )
                    }
                }
                if (onSelectInvert != null) {
                    IconButton(onClick = onSelectInvert) {
                        AppIcon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = invertSelectionDescription
                        )
                    }
                }
            },
            trailingContent = {
                dismissAction?.let { dismiss ->
                    IconButton(onClick = dismiss.onClick) {
                        AppIcon(
                            imageVector = dismiss.icon,
                            contentDescription = dismiss.text
                        )
                    }
                }
                if (secondaryActions.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            AppIcon(
                                imageVector = AppIcons.MoreVert,
                                contentDescription = moreActionsDescription
                            )
                        }
                        RoundDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            secondaryActions.forEach { action ->
                                RoundDropdownMenuItem(
                                    text = action.text,
                                    onClick = {
                                        action.onClick()
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            content = {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        TooltipAnchorPosition.Above
                    ),
                    tooltip = { ProvideAppDensity { PlainTooltip { AppText(primaryAction.text) } } },
                    state = rememberTooltipState(),
                ) {
                    FilledIconButton(
                        modifier = Modifier.width(64.dp),
                        onClick = primaryAction.onClick,
                        enabled = primaryAction.enabled,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = LegadoTheme.colorScheme.secondaryContainer,
                            contentColor = LegadoTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        AppIcon(
                            imageVector = primaryAction.icon,
                            contentDescription = primaryAction.text
                        )
                    }
                }
            }
        )
    }
}
