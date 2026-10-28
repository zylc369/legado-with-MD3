package io.legado.app.feature.privateshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.gateway.LocalPasswordGateway
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.gateway.PrivateContentGateway
import io.legado.app.domain.gateway.PrivateShelfGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.model.PrivateAccessState
import io.legado.app.ui.main.bookshelf.BookShelfItem
import io.legado.app.ui.main.bookshelf.parseTagColors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PrivateShelfViewModel(
    private val accessGateway: PrivateAccessGateway,
    private val contentGateway: PrivateContentGateway,
    private val privateShelfGateway: PrivateShelfGateway,
    private val localPasswordGateway: LocalPasswordGateway,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val themeSettingsGateway: ThemeSettingsGateway,
) : ViewModel() {

    private data class InternalState(
        val pendingUnmark: BookShelfItem? = null,
    )

    private val _internal = MutableStateFlow(InternalState())
    private val _effects = MutableSharedFlow<PrivateShelfEffect>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val effects: SharedFlow<PrivateShelfEffect> = _effects.asSharedFlow()

    /** Eagerly：点击处理要同步读当前值（生物是否可用），不能等订阅才生效 */
    private val accessState: StateFlow<PrivateAccessState> =
        accessGateway.state
            .stateIn(viewModelScope, SharingStarted.Eagerly, PrivateAccessState())

    val uiState: StateFlow<PrivateShelfUiState> = combine(
        accessState,
        privateShelfGateway.flowPrivateBookShelf(),
        _internal,
        bookshelfSettingsGateway.settings,
        themeSettingsGateway.settings,
    ) { access, books, internal, bookshelfSettings, themeSettings ->
        val phase = when {
            !access.hasPassword -> PrivateShelfPhase.Onboarding
            !access.isSessionUnlocked -> PrivateShelfPhase.Verify
            else -> PrivateShelfPhase.Shelf
        }
        PrivateShelfUiState(
            phase = phase,
            biometricPreferred = access.canUseBiometricShortcut,
            books = books.toImmutableList(),
            pendingUnmark = internal.pendingUnmark,
            bookshelfSettings = bookshelfSettings,
            enableCustomTagColors = themeSettings.enableCustomTagColors,
            customTagColors = parseTagColors(themeSettings.customTagColorsJson),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PrivateShelfUiState(),
    )

    /**
     * 私密模式页面销毁（从返回栈移除）即锁定：覆盖"返回/顶栏退出/切到底部导航"所有
     * 离开路径；被阅读器覆盖时条目仍在栈上，VM 不清理，会话保持。
     * 不能在退出按钮里同步 lock——弹栈过渡期间旧页面还在屏上，会以验证页形态闪现。
     */
    override fun onCleared() {
        accessGateway.lock()
        super.onCleared()
    }

    fun onIntent(intent: PrivateShelfIntent) {
        when (intent) {
            is PrivateShelfIntent.SubmitPassword -> submitPassword(intent.password)

            is PrivateShelfIntent.SetupPassword -> setupPassword(intent.password, intent.confirm)

            PrivateShelfIntent.RequestBiometricUnlock -> {
                if (accessState.value.canUseBiometricShortcut) {
                    _effects.tryEmit(PrivateShelfEffect.RequestBiometricUnlock)
                }
            }

            is PrivateShelfIntent.RequestUnmark -> _internal.update {
                it.copy(pendingUnmark = intent.book)
            }

            PrivateShelfIntent.ConfirmUnmark -> confirmUnmark()
            PrivateShelfIntent.DismissUnmark -> _internal.update {
                it.copy(pendingUnmark = null)
            }

            PrivateShelfIntent.ResetPassword -> resetPassword()
        }
    }

    /** 验证密码并解锁私密模式会话；失败提示由本页给出 */
    private fun submitPassword(password: String) {
        if (password.isEmpty()) return
        viewModelScope.launch {
            val ok = runCatching { accessGateway.unlockSession(password) }.getOrDefault(false)
            if (!ok) {
                _effects.tryEmit(
                    PrivateShelfEffect.ShowToastRes(R.string.private_unlock_password_error)
                )
            }
        }
    }

    /**
     * 开通向导：设置本地密码后直接用它解锁进入。
     * 密码写入是唯一权威入口（LocalPasswordGateway），写成功再解锁，失败如实提示。
     */
    private fun setupPassword(password: String, confirm: String) {
        when {
            password.isEmpty() -> return
            password != confirm -> {
                _effects.tryEmit(
                    PrivateShelfEffect.ShowToastRes(R.string.private_mode_password_mismatch)
                )
                return
            }
        }
        viewModelScope.launch {
            runCatching { localPasswordGateway.setPassword(password) }
                .onFailure {
                    _effects.tryEmit(PrivateShelfEffect.ShowToastRes(R.string.save_failed))
                }
                .onSuccess { submitPassword(password) }
        }
    }

    /**
     * 忘记密码的唯一出口。本地数据没有可核验的第三方凭据，重置只能"降级"：
     * 清私密密码 + 取消全部私密标记，书回到公开书架——否则用户会永久锁死自己的书。
     */
    private fun resetPassword() {
        viewModelScope.launch {
            runCatching {
                accessGateway.resetPassword()
                val privateUrls = contentGateway.flowPrivateBookUrls().first()
                if (privateUrls.isNotEmpty()) {
                    contentGateway.setBooksPrivate(privateUrls, false)
                }
            }.onFailure {
                _effects.tryEmit(PrivateShelfEffect.ShowToastRes(R.string.save_failed))
            }
        }
    }

    private fun confirmUnmark() {
        val book = _internal.value.pendingUnmark ?: return
        _internal.update { it.copy(pendingUnmark = null) }
        viewModelScope.launch {
            runCatching { contentGateway.setBooksPrivate(setOf(book.bookUrl), false) }
                .onFailure {
                    _effects.tryEmit(PrivateShelfEffect.ShowToastRes(R.string.save_failed))
                }
        }
    }
}
