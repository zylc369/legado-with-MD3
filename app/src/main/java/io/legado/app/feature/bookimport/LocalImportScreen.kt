package io.legado.app.feature.bookimport

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.ActionItem
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SelectionBottomBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.checkBox.SelectionCheckBadge
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.ConvertUtils
import io.legado.app.utils.toastOnUi
import org.koin.androidx.compose.koinViewModel

/** 与旧版导入页一致的书籍文件 MIME 过滤。 */
val BookImportMimeTypes = arrayOf(
    "text/plain", "application/epub+zip", "application/pdf",
    "application/x-mobipocket-ebook", "application/vnd.amazon.ebook",
    "application/zip", "application/x-rar-compressed",
    "application/x-7z-compressed", "application/octet-stream",
)

/** 「从压缩包导入」的 MIME 过滤。 */
val ArchiveMimeTypes = arrayOf(
    "application/zip", "application/x-rar-compressed",
    "application/x-7z-compressed", "application/x-compressed",
    "application/octet-stream",
)

@Composable
fun LocalImportRouteScreen(
    onBackClick: () -> Unit,
    viewModel: LocalImportViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showFileNameRuleDialog by rememberSaveable { mutableStateOf(false) }
    var fileNameRuleDraft by rememberSaveable { mutableStateOf("") }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.onIntent(LocalImportIntent.FilesPicked(uris)) }
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onIntent(LocalImportIntent.FolderPicked(uri)) }
    val pickMangaFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onIntent(LocalImportIntent.MangaFolderPicked(uri)) }
    val pickArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onIntent(LocalImportIntent.FilesPicked(listOf(it))) } }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                LocalImportEffect.LaunchPickFiles -> pickFiles.launch(BookImportMimeTypes)
                LocalImportEffect.LaunchPickFolder -> pickFolder.launch(null)
                LocalImportEffect.LaunchPickArchive -> pickArchive.launch(ArchiveMimeTypes)
                LocalImportEffect.LaunchPickMangaFolder -> pickMangaFolder.launch(null)
                is LocalImportEffect.ShowToastRes -> context.toastOnUi(effect.resId)
                is LocalImportEffect.ShowToastMsg -> context.toastOnUi(effect.message)
            }
        }
    }

    LocalImportScreen(
        state = uiState,
        onIntent = viewModel::onIntent,
        onBackClick = onBackClick,
        showFileNameRuleDialog = showFileNameRuleDialog,
        fileNameRuleDraft = fileNameRuleDraft,
        onFileNameRuleDraftChange = { fileNameRuleDraft = it },
        onShowFileNameRuleDialogChange = { show ->
            if (show) fileNameRuleDraft = uiState.fileNameRule
            showFileNameRuleDialog = show
        },
    )
}

@Composable
fun LocalImportScreen(
    state: LocalImportUiState,
    onIntent: (LocalImportIntent) -> Unit,
    onBackClick: () -> Unit,
    showFileNameRuleDialog: Boolean,
    fileNameRuleDraft: String,
    onFileNameRuleDraftChange: (String) -> Unit,
    onShowFileNameRuleDialogChange: (Boolean) -> Unit,
) {
    AppAlertDialog(
        show = showFileNameRuleDialog,
        onDismissRequest = { onShowFileNameRuleDialogChange(false) },
        title = stringResource(R.string.import_file_name_rule),
        content = {
            Column {
                AppText(stringResource(R.string.import_file_name_rule_hint))
                OutlinedTextField(
                    value = fileNameRuleDraft,
                    onValueChange = onFileNameRuleDraftChange,
                    label = { AppText("js") }
                )
            }
        },
        confirmText = stringResource(android.R.string.ok),
        onConfirm = {
            onIntent(LocalImportIntent.SetFileNameRule(fileNameRuleDraft))
            onShowFileNameRuleDialogChange(false)
        },
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = { onShowFileNameRuleDialogChange(false) }
    )

    // 系统返回与顶栏返回共用同一 phase 分支；Importing 期间两者都不离开页面
    val handleBack = {
        when (state.phase) {
            is ImportPhase.Scanning -> onIntent(LocalImportIntent.CancelScan)
            is ImportPhase.Confirm, is ImportPhase.Done ->
                onIntent(LocalImportIntent.BackToEntry)

            is ImportPhase.Importing -> Unit
            ImportPhase.Entry -> onBackClick()
        }
    }
    BackHandler(onBack = handleBack)

    ImportScaffoldBody(
        state = state,
        onIntent = onIntent,
        onBackClick = onBackClick,
        handleBack = handleBack,
        onShowFileNameRuleDialogChange = onShowFileNameRuleDialogChange,
    )
}

@Composable
private fun ImportScaffoldBody(
    state: LocalImportUiState,
    onIntent: (LocalImportIntent) -> Unit,
    onBackClick: () -> Unit,
    handleBack: () -> Unit,
    onShowFileNameRuleDialogChange: (Boolean) -> Unit,
) {
    val phase = state.phase
    val isConfirm = phase is ImportPhase.Confirm

    ImportScaffold(
        title = when (phase) {
            is ImportPhase.Scanning -> phase.folderName
            is ImportPhase.Confirm -> phase.folderName
            else -> stringResource(R.string.local_book)
        },
        onBackClick = handleBack,
        bottomBar = {
            if (isConfirm) {
                SelectionBottomBar(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    onSelectAll = { onIntent(LocalImportIntent.SelectAll) },
                    onSelectInvert = { onIntent(LocalImportIntent.InvertSelection) },
                    primaryAction = ActionItem(
                        text = stringResource(R.string.import_import_selected, state.selectedCount),
                        icon = Icons.Default.CloudUpload,
                        enabled = state.selectedCount > 0,
                        onClick = { onIntent(LocalImportIntent.ConfirmImport) }
                    ),
                    dismissAction = ActionItem(
                        text = stringResource(R.string.cancel),
                        icon = Icons.Default.Close,
                        onClick = { onIntent(LocalImportIntent.BackToEntry) }
                    ),
                )
            }
        },
    ) { padding ->
        when (phase) {
            ImportPhase.Entry -> EntryContent(
                onIntent = onIntent,
                onShowFileNameRuleDialogChange = onShowFileNameRuleDialogChange,
                padding = padding,
            )

            is ImportPhase.Scanning -> ScanningContent(
                phase = phase,
                onIntent = onIntent,
                padding = padding,
            )

            is ImportPhase.Confirm -> ConfirmContent(
                state = state,
                onIntent = onIntent,
                padding = padding,
            )

            is ImportPhase.Importing -> ImportingContent(
                state = state,
                phase = phase,
                padding = padding,
            )

            is ImportPhase.Done -> DoneContent(
                phase = phase,
                onIntent = onIntent,
                onBackClick = onBackClick,
                padding = padding,
            )
        }
    }
}

@Composable
private fun ImportScaffold(
    title: String,
    onBackClick: () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = title,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding -> content(padding) }
}

@Composable
private fun EntryContent(
    onIntent: (LocalImportIntent) -> Unit,
    onShowFileNameRuleDialogChange: (Boolean) -> Unit,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryActionCard(
            title = stringResource(R.string.import_pick_files),
            description = stringResource(R.string.import_pick_files_desc),
            icon = Icons.Default.InsertDriveFile,
            onClick = { onIntent(LocalImportIntent.PickFilesClick) },
        )
        EntryActionCard(
            title = stringResource(R.string.import_pick_folder),
            description = stringResource(R.string.import_pick_folder_desc),
            icon = Icons.Default.FolderOpen,
            onClick = { onIntent(LocalImportIntent.PickFolderClick) },
        )
        EntryActionCard(
            title = stringResource(R.string.import_pick_archive),
            description = stringResource(R.string.import_pick_archive_desc),
            icon = Icons.Default.FolderZip,
            onClick = { onIntent(LocalImportIntent.PickArchiveClick) },
        )
        EntryActionCard(
            title = stringResource(R.string.import_pick_manga),
            description = stringResource(R.string.import_pick_manga_desc),
            icon = Icons.Default.Image,
            onClick = { onIntent(LocalImportIntent.PickMangaFolderClick) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SmallTonalButton(
                onClick = { onShowFileNameRuleDialogChange(true) },
                text = stringResource(R.string.import_file_name_rule),
            )
        }
        Spacer(modifier = Modifier.size(8.dp))
    }
}

@Composable
private fun EntryActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = LegadoTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = LegadoTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                AppText(
                    text = title,
                    style = LegadoTheme.typography.titleMedium,
                )
                AppText(
                    text = description,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScanningContent(
    phase: ImportPhase.Scanning,
    onIntent: (LocalImportIntent) -> Unit,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppCircularProgressIndicator()
        Spacer(modifier = Modifier.size(16.dp))
        AppText(
            text = stringResource(R.string.import_scanning) + " " + phase.folderName,
            style = LegadoTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.size(24.dp))
        SmallTonalButton(
            onClick = { onIntent(LocalImportIntent.CancelScan) },
            text = stringResource(R.string.cancel),
        )
    }
}

@Composable
private fun ConfirmContent(
    state: LocalImportUiState,
    onIntent: (LocalImportIntent) -> Unit,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        AppText(
            text = stringResource(
                R.string.import_found_count,
                state.candidates.size,
                state.onShelfCount,
            ),
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, bottom = 96.dp
            ),
        ) {
            items(state.candidates, key = { it.id }) { candidate ->
                CandidateItem(
                    candidate = candidate,
                    selected = candidate.id in state.selectedIds,
                    onClick = { onIntent(LocalImportIntent.ToggleCandidate(candidate.id)) },
                )
            }
        }
    }
}

@Composable
private fun CandidateItem(
    candidate: ImportCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        LegadoTheme.colorScheme.secondaryContainer
    } else {
        LegadoTheme.colorScheme.surfaceContainer
    }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick,
        containerColor = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionCheckBadge(selected = selected)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = candidate.name,
                    style = LegadoTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextCard(
                        text = candidate.name.substringAfterLast('.', "").uppercase(),
                        textStyle = LegadoTheme.typography.labelSmall,
                        horizontalPadding = 4.dp,
                        verticalPadding = 2.dp,
                        cornerRadius = 4.dp,
                        icon = null,
                        backgroundColor = LegadoTheme.colorScheme.surfaceContainerHighest,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    AppText(
                        text = "${ConvertUtils.formatFileSize(candidate.size)}" +
                                " - ${AppConst.dateFormat.format(candidate.lastModified)}",
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (candidate.isOnBookShelf) {
                Spacer(modifier = Modifier.width(8.dp))
                TextCard(
                    text = stringResource(R.string.import_on_shelf),
                    textStyle = LegadoTheme.typography.labelSmall,
                    horizontalPadding = 6.dp,
                    verticalPadding = 2.dp,
                    cornerRadius = 6.dp,
                    icon = null,
                    backgroundColor = LegadoTheme.colorScheme.primaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ImportingContent(
    state: LocalImportUiState,
    phase: ImportPhase.Importing,
    padding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AppCircularProgressIndicator()
        Spacer(modifier = Modifier.size(16.dp))
        AppText(
            text = stringResource(
                R.string.import_importing,
                state.importingDone,
                phase.total,
            ),
            style = LegadoTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun DoneContent(
    phase: ImportPhase.Done,
    onIntent: (LocalImportIntent) -> Unit,
    onBackClick: () -> Unit,
    padding: PaddingValues,
) {
    val summary = phase.summary
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
    ) {
        AppText(
            text = stringResource(R.string.import_result_success) + " · " +
                    stringResource(R.string.import_result_failure_count, summary.failureCount),
            style = LegadoTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (phase.extractedToPrivateDir) {
            AppText(
                text = stringResource(R.string.import_private_dir_hint),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (summary.failures.isNotEmpty()) {
            AppText(
                text = stringResource(R.string.import_result_failures),
                style = LegadoTheme.typography.titleSmall,
                color = LegadoTheme.colorScheme.error,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(summary.failures, key = { it.id }) { failure ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        containerColor = LegadoTheme.colorScheme.surfaceContainer,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            AppText(
                                text = failure.name,
                                style = LegadoTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            AppText(
                                text = failure.error,
                                style = LegadoTheme.typography.labelMedium,
                                color = LegadoTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            SmallTonalButton(
                onClick = onBackClick,
                text = stringResource(R.string.back),
            )
            SmallTonalButton(
                onClick = { onIntent(LocalImportIntent.BackToEntry) },
                text = stringResource(R.string.import_continue),
            )
        }
    }
}
