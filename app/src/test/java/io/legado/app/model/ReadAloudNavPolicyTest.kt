package io.legado.app.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导航意图 → 朗读处置的契约表。
 *
 * 历史回归（全部曾违反本表）：
 * - 日夜/字号重排被当用户导航 → 朗读回页首（应 KeepReading）
 * - 回到朗读位置被当换章 → 朗读回章首（应 KeepReading）
 * - 同章重排后页表未同步（KeepReading 落地后由 syncLayout 兜底）
 */
class ReadAloudNavPolicyTest {

    @Test
    fun `用户导航重启朗读到可见位置`() {
        // 目录/书签/搜索跳章、播放器选章、菜单换章、跳页
        assertTrue(ReadAloudNavPolicy.restartsReadAloud(ReaderNavIntent.UserJump))
    }

    @Test
    fun `朗读驱动换章重启进入目标章`() {
        // 念完自动进下一章（withSpeechNavigation 中调用换章入口）
        assertTrue(ReadAloudNavPolicy.restartsReadAloud(ReaderNavIntent.SpeechAdvance))
    }

    @Test
    fun `回到朗读位置不重启朗读`() {
        // backToSpeakingPosition：朗读已在该章，只移动页面
        assertFalse(ReadAloudNavPolicy.restartsReadAloud(ReaderNavIntent.KeepReading))
    }

    @Test
    fun `重排不重启朗读`() {
        // 日夜切换、字号/行距/样式、正文重载（主题路径）
        assertFalse(ReadAloudNavPolicy.restartsReadAloud(ReaderNavIntent.KeepReading))
    }
}
