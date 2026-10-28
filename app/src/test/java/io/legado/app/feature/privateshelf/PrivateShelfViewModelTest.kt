package io.legado.app.feature.privateshelf

import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.LocalPasswordGateway
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.gateway.PrivateContentGateway
import io.legado.app.domain.gateway.PrivateShelfGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.PrivateAccessState
import io.legado.app.domain.model.PrivateBiometricStatus
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.domain.model.settings.PrivateAccessSettings
import io.legado.app.domain.model.settings.ThemeSettings
import io.legado.app.ui.main.bookshelf.BookShelfItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivateShelfViewModelTest {

    private class FakeAccessGateway(
        initial: PrivateAccessState = PrivateAccessState(),
    ) : PrivateAccessGateway {
        val stateFlow = MutableStateFlow(initial)
        override val state: Flow<PrivateAccessState> get() = stateFlow
        override val settings: Flow<PrivateAccessSettings> =
            MutableStateFlow(PrivateAccessSettings())

        override suspend fun updateSettings(transform: (PrivateAccessSettings) -> PrivateAccessSettings) =
            Unit

        var unlockPassword: String = "correct"
        var unlocked = false
            private set
        var lockCalls = 0
            private set

        override suspend fun unlockSession(password: String): Boolean {
            if (password != unlockPassword) return false
            unlocked = true
            stateFlow.value = stateFlow.value.copy(isSessionUnlocked = true)
            return true
        }

        override suspend fun verifyPasswordOnly(password: String): Boolean =
            password == unlockPassword

        override fun lock() {
            lockCalls++
            unlocked = false
            stateFlow.value = stateFlow.value.copy(isSessionUnlocked = false)
        }

        override fun isSessionUnlockedNow(): Boolean = unlocked

        var passwordReset = false
            private set

        override suspend fun setPassword(password: String?) {
            unlockPassword = password.orEmpty()
            stateFlow.value = stateFlow.value.copy(hasPassword = !password.isNullOrEmpty())
        }

        override suspend fun resetPassword() {
            passwordReset = true
            stateFlow.value = stateFlow.value.copy(
                hasPassword = false,
                isSessionUnlocked = false,
            )
        }

        override suspend fun disableBiometricShortcut() = Unit
        override suspend fun storeBiometricEnvelope(ciphertext: String, iv: String) = true
        override fun refreshBiometricStatus() = Unit
    }

    private class FakeContentGateway : PrivateContentGateway {
        val unmarked = mutableListOf<String>()
        var privateUrls: Set<String> = emptySet()
        override suspend fun isBookPrivate(bookUrl: String): Boolean = false
        override suspend fun isBookPrivateByName(name: String, author: String): Boolean = false
        override suspend fun setBooksPrivate(bookUrls: Set<String>, isPrivate: Boolean) {
            if (!isPrivate) unmarked.addAll(bookUrls)
        }

        override fun flowPrivateBookUrls(): Flow<Set<String>> =
            MutableStateFlow(privateUrls)
    }

    private class FakeShelfGateway : PrivateShelfGateway {
        val booksFlow = MutableStateFlow<List<BookShelfItem>>(emptyList())
        override fun flowPrivateBookShelf(): Flow<List<BookShelfItem>> = booksFlow
    }

    private class FakeBookshelfSettingsGateway : BookshelfSettingsGateway {
        private val flow = MutableStateFlow(BookshelfSettings())
        override val currentSettings: BookshelfSettings get() = flow.value
        override val settings: Flow<BookshelfSettings> get() = flow
        override suspend fun update(transform: (BookshelfSettings) -> BookshelfSettings) {
            flow.value = transform(flow.value)
        }
    }

    private class FakeThemeSettingsGateway : ThemeSettingsGateway {
        private val flow = MutableStateFlow(ThemeSettings())
        override val currentSettings: ThemeSettings get() = flow.value
        override val settings: Flow<ThemeSettings> get() = flow
        override suspend fun update(transform: (ThemeSettings) -> ThemeSettings) {
            flow.value = transform(flow.value)
        }
    }

    private class FakePasswordGateway(
        var onSaved: (String?) -> Unit = {},
    ) : LocalPasswordGateway {
        var saved: String? = null
        override suspend fun setPassword(password: String?) {
            saved = password
            onSaved(password)
        }
    }

    private fun book(url: String) = BookShelfItem(
        bookUrl = url,
        name = "书-$url",
        author = "作者",
        origin = "test",
        originName = "test",
        coverUrl = null,
        customCoverUrl = null,
        durChapterTitle = null,
        durChapterTime = 0L,
        durChapterPos = 0,
        latestChapterTitle = null,
        latestChapterTime = 0L,
        lastCheckCount = 0,
        totalChapterNum = 0,
        durChapterIndex = 0,
        type = 0,
        group = 0L,
        order = 0,
    )

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var access: FakeAccessGateway
    private lateinit var content: FakeContentGateway
    private lateinit var shelf: FakeShelfGateway
    private lateinit var passwords: FakePasswordGateway
    private lateinit var vm: PrivateShelfViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        access = FakeAccessGateway()
        content = FakeContentGateway()
        shelf = FakeShelfGateway()
        passwords = FakePasswordGateway()
        vm = createViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = PrivateShelfViewModel(
        accessGateway = access,
        contentGateway = content,
        privateShelfGateway = shelf,
        localPasswordGateway = passwords,
        bookshelfSettingsGateway = FakeBookshelfSettingsGateway(),
        themeSettingsGateway = FakeThemeSettingsGateway(),
    )

    @Test
    fun `无密码时呈现开通向导`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = false)
        val job = backgroundScope.launch { vm.uiState.collect {} } // keep active
        assertEquals(PrivateShelfPhase.Onboarding, vm.uiState.value.phase)
        job.cancel()
    }

    @Test
    fun `有密码未解锁时呈现验证页`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true, isSessionUnlocked = false)
        val job = backgroundScope.launch { vm.uiState.collect {} }
        assertEquals(PrivateShelfPhase.Verify, vm.uiState.value.phase)
        job.cancel()
    }

    @Test
    fun `解锁后进入书架并显示私密书`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true, isSessionUnlocked = true)
        shelf.booksFlow.value = listOf(book("a"), book("b"))
        val job = backgroundScope.launch { vm.uiState.collect {} }
        assertEquals(PrivateShelfPhase.Shelf, vm.uiState.value.phase)
        assertEquals(2, vm.uiState.value.bookCount)
        job.cancel()
    }

    @Test
    fun `密码错误保持验证页并提示`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true)
        val effects = mutableListOf<PrivateShelfEffect>()
        val job = backgroundScope.launch {
            vm.effects.collect { effects.add(it) }
        }
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.onIntent(PrivateShelfIntent.SubmitPassword("wrong"))

        assertEquals(PrivateShelfPhase.Verify, vm.uiState.value.phase)
        assertTrue(effects.any { it is PrivateShelfEffect.ShowToastRes })
        stateJob.cancel(); job.cancel()
    }

    @Test
    fun `密码正确解锁进入书架`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true)
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.onIntent(PrivateShelfIntent.SubmitPassword("correct"))

        assertEquals(PrivateShelfPhase.Shelf, vm.uiState.value.phase)
        stateJob.cancel()
    }

    @Test
    fun `开通两次输入不一致不落盘`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = false)
        val effects = mutableListOf<PrivateShelfEffect>()
        val job = backgroundScope.launch {
            vm.effects.collect { effects.add(it) }
        }
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.onIntent(PrivateShelfIntent.SetupPassword("abc", "abd"))

        assertNull(passwords.saved)
        assertEquals(PrivateShelfPhase.Onboarding, vm.uiState.value.phase)
        assertTrue(effects.any { it is PrivateShelfEffect.ShowToastRes })
        stateJob.cancel(); job.cancel()
    }

    @Test
    fun `开通成功写密码并直接进入书架`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = false)
        passwords.onSaved = {
            // 真实实现里 hasPassword 由 PASSWORD 键派生，写入后翻真
            access.stateFlow.value = access.stateFlow.value.copy(hasPassword = true)
        }
        access.unlockPassword = "abc"
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.onIntent(PrivateShelfIntent.SetupPassword("abc", "abc"))

        assertEquals("abc", passwords.saved)
        assertEquals(PrivateShelfPhase.Shelf, vm.uiState.value.phase)
        stateJob.cancel()
    }

    @Test
    fun `确认取消私密清除待确认项并写库`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true, isSessionUnlocked = true)
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }
        val target = book("a")
        vm.onIntent(PrivateShelfIntent.RequestUnmark(target))
        assertEquals(target, vm.uiState.value.pendingUnmark)

        vm.onIntent(PrivateShelfIntent.ConfirmUnmark)

        assertNull(vm.uiState.value.pendingUnmark)
        assertEquals(listOf("a"), content.unmarked)
        stateJob.cancel()
    }

    @Test
    fun `取消确认框不写库`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true, isSessionUnlocked = true)
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }
        vm.onIntent(PrivateShelfIntent.RequestUnmark(book("a")))

        vm.onIntent(PrivateShelfIntent.DismissUnmark)

        assertTrue(content.unmarked.isEmpty())
        stateJob.cancel()
    }

    @Test
    fun `忘记密码重置清凭据并取消全部私密标记`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(hasPassword = true)
        content.privateUrls = setOf("a", "b")
        val stateJob = backgroundScope.launch { vm.uiState.collect {} }

        vm.onIntent(PrivateShelfIntent.ResetPassword)

        assertTrue(access.passwordReset)
        assertEquals(setOf("a", "b"), content.unmarked.toSet())
        // 凭据清掉后回到开通向导
        assertEquals(PrivateShelfPhase.Onboarding, vm.uiState.value.phase)
        stateJob.cancel()
    }

    @Test
    fun `生物解锁效应只在生物可用时发出`() = runTest(dispatcher) {
        access.stateFlow.value = PrivateAccessState(
            hasPassword = true,
            biometricEnabled = true,
            biometricStatus = PrivateBiometricStatus.Available,
        )
        val effects = mutableListOf<PrivateShelfEffect>()
        val job = backgroundScope.launch {
            vm.effects.collect { effects.add(it) }
        }

        vm.onIntent(PrivateShelfIntent.RequestBiometricUnlock)
        assertTrue(effects.contains(PrivateShelfEffect.RequestBiometricUnlock))

        // 生物不可用：不弹系统框，停留在密码验证
        access.stateFlow.value = access.stateFlow.value.copy(biometricEnabled = false)
        vm.onIntent(PrivateShelfIntent.RequestBiometricUnlock)
        assertEquals(1, effects.count { it == PrivateShelfEffect.RequestBiometricUnlock })
        job.cancel()
    }
}
