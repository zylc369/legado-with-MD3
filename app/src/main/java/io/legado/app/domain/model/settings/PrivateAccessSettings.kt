package io.legado.app.domain.model.settings

import androidx.compose.runtime.Stable

/**
 * 私密模式设置。
 *
 * 新模型只有一套机制：进入私密模式时验证一次，会话期间不再逐本/逐分组验证。
 * 因此这里只剩"离开前台多久后要求重新验证"一项；本地密码是否存在、生物识别是否
 * 可用属于能力状态，由 PrivateAccessState 承载，不混进设置项。
 */
@Stable
data class PrivateAccessSettings(
    /**
     * 离开前台后多久才要求重新验证（秒）：
     * 0 表示"离开即失效"，>0 表示这段时间内回到应用不重验。
     */
    val backgroundGraceSeconds: Int = 60,
)
