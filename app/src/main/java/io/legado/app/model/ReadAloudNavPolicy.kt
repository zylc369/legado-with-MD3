package io.legado.app.model

/**
 * 页面导航的意图分类。**朗读的处置由意图唯一决定**，不再由布尔默认值推断——
 * 历史上三类回归（日夜重排回页首 / 回到朗读位置回章首 / 重排后页表 stale）
 * 全是"意图被错认"：一个加载原语被四种调用方共用，处置藏在默认参数里。
 *
 * 意图 → 处置契约表（新增导航入口必须在此登记）：
 *
 * | 意图            | 调用方                                                     | 朗读处置           |
 * |-----------------|------------------------------------------------------------|--------------------|
 * | [UserJump]      | 目录/书签/搜索跳章、播放器选章、菜单换章、跳页              | 重启到可见位置     |
 * | [SpeechAdvance] | 朗读服务驱动的换章（念完进下一章，withSpeechNavigation 中）| 重启进入目标章     |
 * | [KeepReading]   | 回到朗读位置、日夜/字号/样式重排、同章内容重载              | 不重启，只同步页表 |
 */
enum class ReaderNavIntent {
    /** 用户主动导航：朗读重启到可见位置（“读我正在看的”）。 */
    UserJump,

    /** 朗读服务驱动的换章：朗读重启进入目标章（位置由调用方给出，默认章首）。 */
    SpeechAdvance,

    /** 页面移动或重排，但朗读必须原样继续：只允许同步页表，禁止重启。 */
    KeepReading,
}

/**
 * 导航意图 → 是否允许重启朗读的唯一裁决点。
 *
 * 注意：[ReaderNavIntent.KeepReading] 的调用方必须加载**服务正在朗读或页面正在显示**的
 * 章节（同章重排/页面移动）。本策略不看章节号——旧实现里“章节不一致即重启”的防御
 * 已被意图显式化取代，向非朗读章传 KeepReading 会静默不重启。
 */
object ReadAloudNavPolicy {

    fun restartsReadAloud(intent: ReaderNavIntent): Boolean = when (intent) {
        ReaderNavIntent.UserJump, ReaderNavIntent.SpeechAdvance -> true
        ReaderNavIntent.KeepReading -> false
    }
}
