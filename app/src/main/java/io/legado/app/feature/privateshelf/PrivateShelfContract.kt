package io.legado.app.feature.privateshelf

import androidx.compose.runtime.Stable
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.ui.config.themeConfig.TagColorPair
import io.legado.app.ui.main.bookshelf.BookShelfItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** 页面该呈现哪一种形态：未设密码 → 开通；已设但未解锁 → 验证；已解锁 → 书架 */
sealed interface PrivateShelfPhase {
    data object Onboarding : PrivateShelfPhase
    data object Verify : PrivateShelfPhase
    data object Shelf : PrivateShelfPhase
}

@Stable
data class PrivateShelfUiState(
    val phase: PrivateShelfPhase = PrivateShelfPhase.Verify,
    /** 生物快捷可用（已启用且设备可用）时验证页优先展示生物入口 */
    val biometricPreferred: Boolean = false,
    val books: ImmutableList<BookShelfItem> = persistentListOf(),
    /** 正在等一次待确认的「取消私密」 */
    val pendingUnmark: BookShelfItem? = null,
    /** 复用公开书架的外观设置（布局/标题/封面样式完全一致） */
    val bookshelfSettings: BookshelfSettings = BookshelfSettings(),
    val enableCustomTagColors: Boolean = false,
    val customTagColors: ImmutableList<TagColorPair> = persistentListOf(),
) {
    val bookCount: Int get() = books.size
}

sealed interface PrivateShelfIntent {
    data class SubmitPassword(val password: String) : PrivateShelfIntent
    data class SetupPassword(val password: String, val confirm: String) : PrivateShelfIntent
    data object RequestBiometricUnlock : PrivateShelfIntent
    data class RequestUnmark(val book: BookShelfItem) : PrivateShelfIntent
    data object ConfirmUnmark : PrivateShelfIntent
    data object DismissUnmark : PrivateShelfIntent

    /** 忘记密码：清除私密密码并取消全部私密标记（书回到公开书架） */
    data object ResetPassword : PrivateShelfIntent
}

sealed interface PrivateShelfEffect {
    /** 宿主弹出系统生物验证框（需要 FragmentActivity），成功解出的密码回传 SubmitPassword */
    data object RequestBiometricUnlock : PrivateShelfEffect
    data class ShowToastRes(val resId: Int) : PrivateShelfEffect
}
