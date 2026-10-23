package io.legado.app.model

import android.content.Context
import io.legado.app.service.BaseReadAloudService

/**
 * 朗读播放/暂停的**唯一**策略入口。
 *
 * 阅读页页脚、悬浮胶囊、听书播放页、媒体键等所有 UI 都必须调用 [toggle]。
 *
 * 语义只有三条：
 * - 未运行 → 从可见位置开始（阅读页挂载时注入 [startFromVisiblePosition]）；
 * - 播放中 → 暂停；
 * - 暂停 → **永远从服务游标续播**。暂停期间是否翻页/换章不影响恢复位置
 *   （用户反馈：切页后恢复仍应从暂停处继续）。
 */
object ReadAloudResumePolicy {

    /** 当前可见位置作为新朗读起点的能力；阅读页 RESUMED 时注入，真正离屏时清除。 */
    @Volatile
    var startFromVisiblePosition: (() -> Boolean)? = null

    fun toggle(context: Context) {
        when {
            !BaseReadAloudService.isRun -> {
                if (startFromVisiblePosition?.invoke() != true) {
                    ReadBook.readAloud()
                }
            }

            !BaseReadAloudService.pause -> ReadAloud.pause(context)

            else -> ReadAloud.resume(context)
        }
    }
}
