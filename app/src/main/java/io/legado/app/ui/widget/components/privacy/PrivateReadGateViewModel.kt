package io.legado.app.ui.widget.components.privacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.repository.BookRepository
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.gateway.PrivateContentGateway
import io.legado.app.domain.model.PrivateAccessState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 阅读器入口的私密闸门。
 *
 * 私密书只应从私密模式里打开（会话已解锁，闸门直接放行）。这里仍是**唯一闸门**：
 * 任何绕过私密模式直接进阅读器的路径（阅读记录、通知栏继续阅读、深链、文件关联、
 * 音频播放页…）在会话未解锁时一律拦下，而不是给每个入口各打一个补丁——后者必然漏。
 */
sealed interface PrivateReadGateEffect {
    data class ShowMessage(val message: String) : PrivateReadGateEffect
    data object NavigateToLocalPasswordSettings : PrivateReadGateEffect
}

class PrivateReadGateViewModel(
    private val privateContentGateway: PrivateContentGateway,
    private val privateAccessGateway: PrivateAccessGateway,
    private val bookRepository: BookRepository,
) : ViewModel() {

    data class UiState(
        val isChecking: Boolean = true,
        val bookUrl: String? = null,
        val isPrivate: Boolean = false,
        val access: PrivateAccessState = PrivateAccessState(),
    ) {
        val isLocked: Boolean
            get() = !isChecking && bookUrl != null && isPrivate && !access.isSessionUnlocked
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PrivateReadGateEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    /** 已判定过的入参，避免重组时重复查库 */
    private var resolvedKey: String? = null
    private var hasResolved = false

    init {
        viewModelScope.launch {
            privateAccessGateway.state.collect { access ->
                _uiState.update { it.copy(access = access) }
            }
        }
    }

    /**
     * 判定一次。
     *
     * bookUrl 为空时按**阅读器自己的回退规则**（`BookRepository.getLastReadBook`）解析目标：
     * 闸门必须与阅读器看到同一本书，否则会出现"放行了 A、打开的却是 B"。
     */
    fun resolve(bookUrl: String?) {
        if (hasResolved && resolvedKey == bookUrl) return
        hasResolved = true
        resolvedKey = bookUrl
        viewModelScope.launch {
            val url = bookUrl?.takeIf { it.isNotEmpty() }
                ?: bookRepository.getLastReadBook()?.bookUrl
            val isPrivate = url?.let { privateContentGateway.isBookPrivate(it) } ?: false
            _uiState.update { it.copy(isChecking = false, bookUrl = url, isPrivate = isPrivate) }
        }
    }

    /**
     * 解锁私密模式会话。
     *
     * 成功后不需要任何"继续打开"的回调：会话状态变化会让 [UiState.isLocked]
     * 自然翻假，内容分支自己就接上了。
     */
    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        viewModelScope.launch {
            val verified = runCatching {
                privateAccessGateway.unlockSession(password)
            }.getOrDefault(false)
            if (!verified) {
                _effects.tryEmit(
                    PrivateReadGateEffect.ShowMessage(
                        appCtx.getString(R.string.private_unlock_password_error)
                    )
                )
            }
        }
    }

    /** 没有本地密码就没有解锁路径：只能去设置页设一个 */
    fun openLocalPasswordSettings() {
        _effects.tryEmit(PrivateReadGateEffect.NavigateToLocalPasswordSettings)
    }
}
