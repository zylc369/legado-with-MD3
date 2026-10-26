package io.legado.app.feature.bookimport

import android.net.Uri
import io.legado.app.data.repository.ImportFolderScanner
import io.legado.app.data.repository.ScannedBookFile
import io.legado.app.domain.gateway.FolderPrepareResult
import io.legado.app.domain.gateway.ImportBookSettingsGateway
import io.legado.app.domain.gateway.ImportFileOutcome
import io.legado.app.domain.gateway.LocalBookImportGateway
import io.legado.app.domain.gateway.LocalImportGateway
import io.legado.app.domain.gateway.PreparedFolder
import io.legado.app.domain.model.settings.ImportBookSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class LocalImportViewModelTest {

    private class FakeLocalImportGateway : LocalImportGateway {
        var prepareResult: FolderPrepareResult =
            FolderPrepareResult.Failed("无法读取该文件夹，请重新选择")

        var hasBookArchive = false
        var hasBookArchiveGate: CompletableDeferred<Unit>? = null
        val persistedBatches = mutableListOf<List<Uri>>()

        override suspend fun prepareImportFolder(uri: Uri): FolderPrepareResult =
            prepareResult

        override suspend fun persistFileReadPermissions(uris: List<Uri>) {
            persistedBatches.add(uris)
        }

        override suspend fun hasBookArchive(uris: List<Uri>): Boolean {
            hasBookArchiveGate?.await()
            return hasBookArchive
        }
    }

    private class FakeBookImportGateway : LocalBookImportGateway {
        val shelfUrls = MutableStateFlow<Set<String>>(emptySet())
        var importOutcomes: List<ImportFileOutcome> = emptyList()
        /** 每发射一项后挂起一次，用于观察导入中间状态；null = 不挂起。 */
        var emitGate: CompletableDeferred<Unit>? = null
        val importedBatches = mutableListOf<List<Uri>>()
        val mangaCalls = mutableListOf<Uri>()
        var mangaError: Throwable? = null

        override fun flowLocalBookUrls(): Flow<Set<String>> = shelfUrls

        override fun importUrisFlow(uris: List<Uri>): Flow<ImportFileOutcome> {
            importedBatches.add(uris)
            return kotlinx.coroutines.flow.flow {
                importOutcomes.forEach {
                    emit(it)
                    emitGate?.await()
                }
            }
        }

        override suspend fun importMangaDirectory(treeUri: Uri) {
            mangaCalls.add(treeUri)
            mangaError?.let { throw it }
        }
    }

    private class FakeScanner : ImportFolderScanner {
        var result: List<ScannedBookFile> = emptyList()
        var error: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0

        override suspend fun scan(treeUri: Uri): List<ScannedBookFile> {
            calls++
            gate?.await()
            error?.let { throw it }
            return result
        }
    }

    private class FakeImportSettings : ImportBookSettingsGateway {
        private val state = MutableStateFlow(ImportBookSettings())
        override val currentSettings: ImportBookSettings get() = state.value
        override val settings: StateFlow<ImportBookSettings> = state.asStateFlow()
        override suspend fun update(transform: (ImportBookSettings) -> ImportBookSettings) {
            state.value = transform(state.value)
        }
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var gateway: FakeLocalImportGateway
    private lateinit var books: FakeBookImportGateway
    private lateinit var scanner: FakeScanner
    private lateinit var importSettings: FakeImportSettings
    private lateinit var vm: LocalImportViewModel
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val effects = mutableListOf<LocalImportEffect>()

    private val treeUri: Uri = Uri.parse("content://test/tree/primary%3ABooks")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        gateway = FakeLocalImportGateway()
        books = FakeBookImportGateway()
        scanner = FakeScanner()
        importSettings = FakeImportSettings()
        vm = createViewModel()
        scope.launchCollectors()
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun createViewModel() = LocalImportViewModel(
        localImportGateway = gateway,
        bookImportGateway = books,
        scanner = scanner,
        importBookSettingsGateway = importSettings,
        ioDispatcher = dispatcher,
        deriveDispatcher = dispatcher,
    )

    private fun CoroutineScope.launchCollectors() {
        launch { vm.effects.collect { effects.add(it) } }
        launch { vm.uiState.collect { } }
    }

    private fun state(): LocalImportUiState = vm.uiState.value

    private fun lastToast(): LocalImportEffect? =
        effects.lastOrNull { it is LocalImportEffect.ShowToastRes || it is LocalImportEffect.ShowToastMsg }

    @Test
    fun `初始为入口页且无副作用`() {
        assertEquals(ImportPhase.Entry, state().phase)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `多选普通文件直接导入并完成`() {
        books.importOutcomes = listOf(
            ImportFileOutcome("content://f/1", "a.txt", null),
            ImportFileOutcome("content://f/2", "b.txt", null),
        )
        vm.onIntent(LocalImportIntent.FilesPicked(listOf("content://f/1", "content://f/2").map(Uri::parse)))
        assertEquals(1, books.importedBatches.size)
        assertEquals(2, books.importedBatches.first().size)
        val done = state().phase
        assertTrue(done is ImportPhase.Done)
        assertEquals(2, (done as ImportPhase.Done).summary.successCount)
        assertTrue(effects.any { it is LocalImportEffect.ShowToastRes })
    }

    @Test
    fun `空选集不做任何事`() {
        vm.onIntent(LocalImportIntent.FilesPicked(emptyList()))
        assertTrue(books.importedBatches.isEmpty())
        assertEquals(ImportPhase.Entry, state().phase)
    }

    @Test
    fun `设置好保存目录后继续待导入批次`() {
        gateway.hasBookArchive = true
        val uris = listOf(Uri.parse("content://f/book.zip"))
        books.importOutcomes = listOf(ImportFileOutcome(uris[0].toString(), "book.zip", null))
        vm.onIntent(LocalImportIntent.FilesPicked(uris))
        assertEquals(1, books.importedBatches.size)
        val done = state().phase as ImportPhase.Done
        assertTrue(done.extractedToPrivateDir)
    }

    @Test
    fun `无效目录拒绝且不进入扫描`() {
        vm.onIntent(LocalImportIntent.FolderPicked(Uri.parse("content://bad/tree/1")))
        assertEquals(ImportPhase.Entry, state().phase)
        assertEquals(0, scanner.calls)
        assertTrue(lastToast() is LocalImportEffect.ShowToastMsg)
    }

    @Test
    fun `有效目录扫描后进入确认且默认全选可导入项`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(
            ScannedBookFile("content://t/1", "a.txt", 1L, 0L),
            ScannedBookFile("content://t/2", "b.epub", 1L, 0L),
        )
        books.shelfUrls.value = setOf("content://t/1")
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        val phase = state().phase
        assertTrue(phase is ImportPhase.Confirm)
        assertEquals("Books", (phase as ImportPhase.Confirm).folderName)
        assertEquals(2, state().candidates.size)
        assertTrue(state().candidates.first { it.id == "content://t/1" }.isOnBookShelf)
        assertEquals(setOf("content://t/2"), state().selectedIds.toSet())
    }

    @Test
    fun `扫描异常提示并回入口`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.error = RuntimeException("provider 崩了")
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertEquals(ImportPhase.Entry, state().phase)
        assertTrue(lastToast() is LocalImportEffect.ShowToastMsg)
    }

    @Test
    fun `扫描中取消回入口`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        val gate = CompletableDeferred<Unit>()
        scanner.gate = gate
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertTrue(state().phase is ImportPhase.Scanning)
        vm.onIntent(LocalImportIntent.CancelScan)
        assertEquals(ImportPhase.Entry, state().phase)
        gate.complete(Unit)
    }

    @Test
    fun `扫描无结果提示并回入口`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = emptyList()
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertEquals(ImportPhase.Entry, state().phase)
        assertTrue(effects.any { it is LocalImportEffect.ShowToastRes })
    }

    @Test
    fun `手动选择动作覆盖默认全选`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(
            ScannedBookFile("content://t/1", "a.txt", 1L, 0L),
            ScannedBookFile("content://t/2", "b.txt", 1L, 0L),
        )
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertEquals(setOf("content://t/1", "content://t/2"), state().selectedIds.toSet())
        vm.onIntent(LocalImportIntent.ToggleCandidate("content://t/1"))
        assertEquals(setOf("content://t/2"), state().selectedIds.toSet())
        vm.onIntent(LocalImportIntent.ClearSelection)
        assertTrue(state().selectedIds.isEmpty())
        vm.onIntent(LocalImportIntent.SelectAll)
        assertEquals(setOf("content://t/1", "content://t/2"), state().selectedIds.toSet())
        vm.onIntent(LocalImportIntent.InvertSelection)
        assertTrue(state().selectedIds.isEmpty())
    }

    @Test
    fun `确认导入只导入选中项`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(
            ScannedBookFile("content://t/1", "a.txt", 1L, 0L),
            ScannedBookFile("content://t/2", "b.txt", 1L, 0L),
        )
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        vm.onIntent(LocalImportIntent.ToggleCandidate("content://t/1"))
        books.importOutcomes = listOf(ImportFileOutcome("content://t/2", "b.txt", null))
        vm.onIntent(LocalImportIntent.ConfirmImport)
        assertEquals(listOf("content://t/2"), books.importedBatches.single().map { it.toString() })
        assertTrue(state().phase is ImportPhase.Done)
    }

    @Test
    fun `空选择确认无操作`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(ScannedBookFile("content://t/1", "a.txt", 1L, 0L))
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        vm.onIntent(LocalImportIntent.ClearSelection)
        vm.onIntent(LocalImportIntent.ConfirmImport)
        assertTrue(books.importedBatches.isEmpty())
        assertTrue(state().phase is ImportPhase.Confirm)
    }

    @Test
    fun `部分失败进入完成页并保留明细`() {
        books.importOutcomes = listOf(
            ImportFileOutcome("content://f/1", "a.txt", null),
            ImportFileOutcome("content://f/2", "b.zip", "解压失败"),
        )
        vm.onIntent(
            LocalImportIntent.FilesPicked(listOf("content://f/1", "content://f/2").map(Uri::parse))
        )
        val done = state().phase as ImportPhase.Done
        assertEquals(1, done.summary.successCount)
        assertEquals(1, done.summary.failureCount)
        assertEquals("b.zip", done.summary.failures.first().name)
    }

    @Test
    fun `全部失败提示错误`() {
        books.importOutcomes = listOf(
            ImportFileOutcome("content://f/1", "a.txt", "err"),
            ImportFileOutcome("content://f/2", "b.txt", "err"),
        )
        vm.onIntent(LocalImportIntent.FilesPicked(listOf("content://f/1").map(Uri::parse)))
        val done = state().phase as ImportPhase.Done
        assertTrue(done.summary.allFailed)
        val toast = effects.filterIsInstance<LocalImportEffect.ShowToastRes>().lastOrNull()
        assertEquals(io.legado.app.R.string.import_all_failed, toast?.resId)
    }

    @Test
    fun `书架变化更新已上架标记并裁剪选择`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(
            ScannedBookFile("content://t/1", "a.txt", 1L, 0L),
            ScannedBookFile("content://t/2", "b.txt", 1L, 0L),
        )
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        vm.onIntent(LocalImportIntent.ToggleCandidate("content://t/2"))
        assertEquals(setOf("content://t/1"), state().selectedIds.toSet())

        books.shelfUrls.value = setOf("content://t/1")
        assertTrue(state().candidates.first { it.id == "content://t/1" }.isOnBookShelf)
        assertTrue(state().selectedIds.isEmpty())
    }

    @Test
    fun `导入过程逐项更新进度`() {
        val gate = CompletableDeferred<Unit>()
        books.emitGate = gate
        books.importOutcomes = listOf(
            ImportFileOutcome("content://f/1", "a.txt", null),
            ImportFileOutcome("content://f/2", "b.txt", null),
        )
        vm.onIntent(
            LocalImportIntent.FilesPicked(listOf("content://f/1", "content://f/2").map(Uri::parse))
        )
        // 第一项已发射并计入进度，第二项被门挂起
        assertEquals(ImportPhase.Importing(2), state().phase)
        assertEquals(1, state().importingDone)
        gate.complete(Unit)
        val done = state().phase as ImportPhase.Done
        assertEquals(2, done.summary.total)
        assertEquals(2, done.summary.successCount)
    }

    @Test
    fun `扫描中新的目录选择被忙守卫忽略`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        val gate = CompletableDeferred<Unit>()
        scanner.gate = gate
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertTrue(state().phase is ImportPhase.Scanning)
        assertEquals(1, scanner.calls)

        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertEquals(1, scanner.calls)
        assertTrue(
            effects.any { (it as? LocalImportEffect.ShowToastRes)?.resId == io.legado.app.R.string.import_busy }
        )
        gate.complete(Unit)
        assertEquals(ImportPhase.Entry, state().phase)
    }

    @Test
    fun `导入中忽略新的选择请求`() {
        val gate = CompletableDeferred<Unit>()
        books.emitGate = gate
        books.importOutcomes = listOf(ImportFileOutcome("content://f/1", "a.txt", null))
        vm.onIntent(LocalImportIntent.FilesPicked(listOf("content://f/1").map(Uri::parse)))
        assertTrue(state().phase is ImportPhase.Importing)

        vm.onIntent(
            LocalImportIntent.FilesPicked(listOf("content://f/9").map(Uri::parse))
        )
        assertEquals(1, books.importedBatches.size)
        assertTrue(
            effects.any { (it as? LocalImportEffect.ShowToastRes)?.resId == io.legado.app.R.string.import_busy }
        )
        gate.complete(Unit)
        assertTrue(state().phase is ImportPhase.Done)
    }

    @Test
    fun `确认导入连点只启动一次导入`() {
        // P1 回归：hasBookArchive 挂起期间 phase 必须已同步进入 Importing
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(
            ScannedBookFile("content://t/1", "a.zip", 1L, 0L),
            ScannedBookFile("content://t/2", "b.zip", 1L, 0L),
        )
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        val gate = CompletableDeferred<Unit>()
        gateway.hasBookArchiveGate = gate
        books.importOutcomes = listOf(
            ImportFileOutcome("content://t/1", "a.zip", null),
            ImportFileOutcome("content://t/2", "b.zip", null),
        )

        vm.onIntent(LocalImportIntent.ConfirmImport)
        assertTrue(state().phase is ImportPhase.Importing)
        vm.onIntent(LocalImportIntent.ConfirmImport)
        assertTrue(state().phase is ImportPhase.Importing)

        gate.complete(Unit)
        assertTrue(state().phase is ImportPhase.Done)
        assertEquals(1, books.importedBatches.size)
        assertEquals(2, books.importedBatches.single().size)
    }

    @Test
    fun `普通文件导入无私有目录提示`() {
        gateway.hasBookArchive = false
        books.importOutcomes = listOf(ImportFileOutcome("content://f/1", "a.txt", null))
        vm.onIntent(LocalImportIntent.FilesPicked(listOf(Uri.parse("content://f/1"))))
        val done = state().phase as ImportPhase.Done
        assertFalse(done.extractedToPrivateDir)
    }

    @Test
    fun `漫画目录导入成功与失败`() {
        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Comics"))
        vm.onIntent(LocalImportIntent.MangaFolderPicked(treeUri))
        assertEquals(listOf(treeUri), books.mangaCalls)
        assertTrue(
            effects.any { (it as? LocalImportEffect.ShowToastRes)?.resId == io.legado.app.R.string.import_manga_success }
        )

        books.mangaError = RuntimeException("目录为空")
        vm.onIntent(LocalImportIntent.MangaFolderPicked(treeUri))
        assertTrue(lastToast() is LocalImportEffect.ShowToastMsg)
    }

    @Test
    fun `完成后返回入口复位状态且可重新扫描`() {
        books.importOutcomes = listOf(ImportFileOutcome("content://f/1", "a.txt", null))
        vm.onIntent(LocalImportIntent.FilesPicked(listOf("content://f/1").map(Uri::parse)))
        assertTrue(state().phase is ImportPhase.Done)

        vm.onIntent(LocalImportIntent.BackToEntry)
        assertEquals(ImportPhase.Entry, state().phase)
        assertTrue(state().candidates.isEmpty())
        assertTrue(state().selectedIds.isEmpty())

        gateway.prepareResult = FolderPrepareResult.Ok(PreparedFolder(treeUri, "Books"))
        scanner.result = listOf(ScannedBookFile("content://t/9", "新书.txt", 1L, 0L))
        vm.onIntent(LocalImportIntent.FolderPicked(treeUri))
        assertTrue(state().phase is ImportPhase.Confirm)
        assertEquals(setOf("content://t/9"), state().selectedIds.toSet())
    }

    @Test
    fun `文件名规则写入设置`() {
        vm.onIntent(LocalImportIntent.SetFileNameRule("java.name"))
        assertEquals("java.name", importSettings.currentSettings.bookImportFileName)
    }

    @Test
    fun `入口点击发起选择器效应`() {
        vm.onIntent(LocalImportIntent.PickFilesClick)
        vm.onIntent(LocalImportIntent.PickFolderClick)
        vm.onIntent(LocalImportIntent.PickArchiveClick)
        vm.onIntent(LocalImportIntent.PickMangaFolderClick)
        assertTrue(effects.contains(LocalImportEffect.LaunchPickFiles))
        assertTrue(effects.contains(LocalImportEffect.LaunchPickFolder))
        assertTrue(effects.contains(LocalImportEffect.LaunchPickArchive))
        assertTrue(effects.contains(LocalImportEffect.LaunchPickMangaFolder))
    }
}
