package io.legado.app.help.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「此刻 App 正在展示私密内容」的进程级信号。
 *
 * 私密模式书架与私密书阅读器各持有引用计数：任一处于前台时 [secureActive] 为真，
 * MainActivity 据此给窗口挂 `FLAG_SECURE`（任务列表无预览 + 禁止截屏/录屏）。
 * 全部退出后信号归零，截屏立即恢复。
 *
 * 用计数而不是布尔：私密书架 → 私密书阅读器是压栈关系，两个界面会短暂并存，
 * 布尔会在书架 dispose 的一瞬错误地关掉 FLAG_SECURE。
 */
class PrivateSecureController {

    private val count = AtomicInteger(0)
    private val _secureActive = MutableStateFlow(false)

    val secureActive: StateFlow<Boolean> = _secureActive.asStateFlow()

    fun retain() {
        if (count.incrementAndGet() == 1) _secureActive.value = true
    }

    fun release() {
        val now = count.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (now == 0) _secureActive.value = false
    }
}
