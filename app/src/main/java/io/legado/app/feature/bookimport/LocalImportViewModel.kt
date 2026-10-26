package io.legado.app.feature.bookimport

import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.repository.ImportFolderScanner
import io.legado.app.data.repository.ScannedBookFile
import io.legado.app.domain.gateway.FolderPrepareResult
import io.legado.app.domain.gateway.ImportBookSettingsGateway
import io.legado.app.domain.gateway.ImportFileOutcome
import io.legado.app.domain.gateway.LocalBookImportGateway
import io.legado.app.domain.gateway.LocalImportGateway
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface LocalImportIntent {
    data object PickFilesClick : LocalImportIntent
    data object PickFolderClick : LocalImportIntent
    data object PickArchiveClick : LocalImportIntent
    data object PickMangaFolderClick : LocalImportIntent
    data class FilesPicked(val uris: List<Uri>) : LocalImportIntent
    data class FolderPicked(val uri: Uri?) : LocalImportIntent
    data class MangaFolderPicked(val uri: Uri?) : LocalImportIntent
    data object CancelScan : LocalImportIntent
    data object BackToEntry : LocalImportIntent
    data class ToggleCandidate(val id: String) : LocalImportIntent
    data object SelectAll : LocalImportIntent
    data object InvertSelection : LocalImportIntent
    data object ClearSelection : LocalImportIntent
    data object ConfirmImport : LocalImportIntent
    data class SetFileNameRule(val value: String) : LocalImportIntent
}

sealed interface LocalImportEffect {
    data object LaunchPickFiles : LocalImportEffect
    data object LaunchPickFolder : LocalImportEffect
    /** 单选压缩包；漫画包直接进书架，书籍包解压到私有目录。 */
    data object LaunchPickArchive : LocalImportEffect
    data object LaunchPickMangaFolder : LocalImportEffect
    data class ShowToastRes(val resId: Int) : LocalImportEffect
    data class ShowToastMsg(val message: String) : LocalImportEffect
}

class LocalImportViewModel(
    private val localImportGateway: LocalImportGateway,
    private val bookImportGateway: LocalBookImportGateway,
    private val scanner: ImportFolderScanner,
    private val importBookSettingsGateway: ImportBookSettingsGateway,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val deriveDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private data class InternalState(
        val phase: ImportPhase = ImportPhase.Entry,
        val scanned: List<ScannedBookFile> = emptyList(),
        val selectedIds: Set<String> = emptySet(),
        /** 扫描完成后的默认勾选=全部可导入项；任何手动选择动作会关掉该标志。 */
        val selectAllDefault: Boolean = false,
        val importingDone: Int = 0,
    ) {
        val busy: Boolean
            get() = phase is ImportPhase.Scanning || phase is ImportPhase.Importing
    }

    private var scanJob: Job? = null
    private var scanGeneration = 0

    private val _state = MutableStateFlow(InternalState())
    private val _effects = MutableSharedFlow<LocalImportEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<LocalImportEffect> = _effects.asSharedFlow()

    val uiState: StateFlow<LocalImportUiState> = combine(
        _state,
        bookImportGateway.flowLocalBookUrls(),
        importBookSettingsGateway.settings,
    ) { state, shelfUrls, settings ->
        val candidates = LocalImportLogic.resolveCandidates(state.scanned, shelfUrls)
        val selected = if (state.selectAllDefault) {
            LocalImportLogic.checkableIds(candidates).toImmutableSet()
        } else {
            val checkable = LocalImportLogic.checkableIds(candidates).toSet()
            state.selectedIds.filterTo(hashSetOf()) { it in checkable }.toImmutableSet()
        }
        LocalImportUiState(
            phase = state.phase,
            candidates = candidates,
            selectedIds = selected,
            importingDone = state.importingDone,
            fileNameRule = settings.bookImportFileName.orEmpty(),
        )
    }.flowOn(deriveDispatcher).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LocalImportUiState(),
    )

    fun onIntent(intent: LocalImportIntent) {
        when (intent) {
            LocalImportIntent.PickFilesClick -> launchOrBusy(LocalImportEffect.LaunchPickFiles)
            LocalImportIntent.PickFolderClick -> launchOrBusy(LocalImportEffect.LaunchPickFolder)
            LocalImportIntent.PickArchiveClick -> launchOrBusy(LocalImportEffect.LaunchPickArchive)
            LocalImportIntent.PickMangaFolderClick ->
                launchOrBusy(LocalImportEffect.LaunchPickMangaFolder)

            is LocalImportIntent.FilesPicked -> onFilesPicked(intent.uris)
            is LocalImportIntent.FolderPicked -> onFolderPicked(intent.uri)
            is LocalImportIntent.MangaFolderPicked -> onMangaFolderPicked(intent.uri)

            LocalImportIntent.CancelScan -> cancelScan()
            LocalImportIntent.BackToEntry -> backToEntry()

            is LocalImportIntent.ToggleCandidate -> _state.update {
                val effective = effectiveSelection(it)
                it.copy(
                    selectAllDefault = false,
                    selectedIds = LocalImportLogic
                        .toggle(effective.toImmutableSet(), intent.id)
                        .toSet(),
                )
            }

            LocalImportIntent.SelectAll -> _state.update { state ->
                state.copy(selectAllDefault = true)
            }

            LocalImportIntent.InvertSelection -> _state.update { state ->
                state.copy(
                    selectAllDefault = false,
                    selectedIds = LocalImportLogic
                        .invert(
                            uiState.value.candidates,
                            effectiveSelection(state).toImmutableSet(),
                        )
                        .toSet(),
                )
            }

            LocalImportIntent.ClearSelection -> _state.update {
                it.copy(selectAllDefault = false, selectedIds = emptySet())
            }

            LocalImportIntent.ConfirmImport -> confirmImport()

            is LocalImportIntent.SetFileNameRule -> viewModelScope.launch(ioDispatcher) {
                importBookSettingsGateway.update { it.copy(bookImportFileName = intent.value) }
            }
        }
    }

    private fun launchOrBusy(effect: LocalImportEffect) {
        if (_state.value.busy) {
            _effects.tryEmit(LocalImportEffect.ShowToastRes(R.string.import_busy))
            return
        }
        _effects.tryEmit(effect)
    }

    private fun onFilesPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_state.value.busy) {
            _effects.tryEmit(LocalImportEffect.ShowToastRes(R.string.import_busy))
            return
        }
        beginImport(uris)
        viewModelScope.launch(ioDispatcher) {
            localImportGateway.persistFileReadPermissions(uris)
            proceedImport(uris)
        }
    }

    private fun onFolderPicked(uri: Uri?) {
        uri ?: return
        if (_state.value.busy) {
            _effects.tryEmit(LocalImportEffect.ShowToastRes(R.string.import_busy))
            return
        }
        val generation = ++scanGeneration
        scanJob?.cancel()
        scanJob = viewModelScope.launch(ioDispatcher) {
            when (val prepared = localImportGateway.prepareImportFolder(uri)) {
                is FolderPrepareResult.Failed -> {
                    if (generation == scanGeneration) {
                        _effects.tryEmit(LocalImportEffect.ShowToastMsg(prepared.reason))
                        _state.update { it.resetEntries(phase = ImportPhase.Entry) }
                    }
                }

                is FolderPrepareResult.Ok -> {
                    _state.update {
                        it.copy(phase = ImportPhase.Scanning(prepared.folder.displayName))
                    }
                    val files = try {
                        scanner.scan(prepared.folder.treeUri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        _effects.tryEmit(
                            if (e.localizedMessage != null) {
                                LocalImportEffect.ShowToastMsg(e.localizedMessage!!)
                            } else {
                                LocalImportEffect.ShowToastRes(R.string.import_scan_error)
                            }
                        )
                        _state.update { it.resetEntries(phase = ImportPhase.Entry) }
                        return@launch
                    }
                    if (generation != scanGeneration) return@launch
                    if (files.isEmpty()) {
                        _effects.tryEmit(LocalImportEffect.ShowToastRes(R.string.import_scan_empty))
                        _state.update { it.resetEntries(phase = ImportPhase.Entry) }
                    } else {
                        _state.update {
                            it.copy(
                                phase = ImportPhase.Confirm(prepared.folder.displayName),
                                scanned = files,
                                selectedIds = emptySet(),
                                selectAllDefault = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onMangaFolderPicked(uri: Uri?) {
        uri ?: return
        if (_state.value.busy) {
            _effects.tryEmit(LocalImportEffect.ShowToastRes(R.string.import_busy))
            return
        }
        viewModelScope.launch(ioDispatcher) {
            when (val prepared = localImportGateway.prepareImportFolder(uri)) {
                is FolderPrepareResult.Failed ->
                    _effects.tryEmit(LocalImportEffect.ShowToastMsg(prepared.reason))

                is FolderPrepareResult.Ok -> {
                    try {
                        bookImportGateway.importMangaDirectory(prepared.folder.treeUri)
                        _effects.tryEmit(
                            LocalImportEffect.ShowToastRes(R.string.import_manga_success)
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        _effects.tryEmit(
                            if (e.localizedMessage != null) {
                                LocalImportEffect.ShowToastMsg(e.localizedMessage!!)
                            } else {
                                LocalImportEffect.ShowToastRes(R.string.import_manga_failed)
                            }
                        )
                    }
                }
            }
        }
    }

    private suspend fun proceedImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val extractedToPrivateDir = localImportGateway.hasBookArchive(uris)
        val outcomes = mutableListOf<ImportFileOutcome>()
        bookImportGateway.importUrisFlow(uris).collect { outcome ->
            outcomes.add(outcome)
            _state.update { it.copy(importingDone = outcomes.size) }
        }
        val summary = LocalImportLogic.summarize(outcomes)
        _state.update {
            it.copy(
                phase = ImportPhase.Done(summary, extractedToPrivateDir),
                selectedIds = emptySet(),
            )
        }
        _effects.tryEmit(
            if (summary.allFailed) {
                LocalImportEffect.ShowToastRes(R.string.import_all_failed)
            } else {
                LocalImportEffect.ShowToastRes(R.string.import_result_success)
            }
        )
    }

    private fun confirmImport() {
        val state = _state.value
        if (state.phase !is ImportPhase.Confirm) return
        val uris = uiState.value.selectedIds.map { it.toUri() }
        if (uris.isEmpty()) return
        beginImport(uris)
        viewModelScope.launch(ioDispatcher) { proceedImport(uris) }
    }

    /**
     * 同步进入 Importing：必须在任何挂起点（权限持久化、压缩包条目读取）之前完成，
     * 否则 busy/phase 守卫存在窗口，连点会并发启动两个导入批次，
     * 对私有目录同名文件并发写入会产生损坏的书籍文件。
     */
    private fun beginImport(uris: List<Uri>) {
        _state.update {
            it.copy(phase = ImportPhase.Importing(total = uris.size), importingDone = 0)
        }
    }

    private fun cancelScan() {
        scanGeneration++
        scanJob?.cancel()
        scanJob = null
        _state.update { it.resetEntries(phase = ImportPhase.Entry) }
    }

    private fun backToEntry() {
        if (_state.value.busy) return
        _state.update { it.resetEntries(phase = ImportPhase.Entry) }
    }

    private fun InternalState.resetEntries(phase: ImportPhase): InternalState = copy(
        phase = phase,
        scanned = emptyList(),
        selectedIds = emptySet(),
        selectAllDefault = false,
    )

    /** 默认全选开启时取派生出的有效选择，否则取存储选择。 */
    private fun effectiveSelection(state: InternalState): Set<String> {
        return if (state.selectAllDefault) {
            uiState.value.selectedIds.toSet()
        } else {
            state.selectedIds
        }
    }
}
