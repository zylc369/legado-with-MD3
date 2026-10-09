package io.legado.app.ui.book.readaloud.player

/**
 * 朗读配置内容（`ReadAloudConfigContent`）发出的意图。
 *
 * 它不再借用阅读器的 `ReadBookIntent`：配置内容的宿主是听书播放浮层与「朗读设置」路由页，
 * 两者都通过 [ReadAloudPlayerViewModel] 落到设置与播放器命令。设置写入由 [Set] 承载，
 * 导航/选择器类交给宿主由 [Host] 承载。
 */
sealed interface ReadAloudConfigIntent {
    data class Set(
        val option: ReadAloudConfigOption,
        val value: String = "",
        val selected: Boolean = false,
        val intValue: Int = 0,
    ) : ReadAloudConfigIntent

    data class Host(val action: ReadAloudPlayerConfigHostAction) : ReadAloudConfigIntent

    data object ResetCapsulePosition : ReadAloudConfigIntent

    data object ClearTtsCache : ReadAloudConfigIntent
}

enum class ReadAloudPlayerConfigHostAction {
    OpenTtsEnginesAndVoices,
    OpenTtsCache,
    OpenBookVoiceCasting,
    OpenSystemTtsSettings,
    OpenPreDownloadNumPicker,
    OpenPreSynthesisConcurrencyPicker,
    OpenParagraphIntervalPicker,
    OpenCacheCleanTimePicker,
}

/**
 * 把配置内容的 [ReadAloudConfigIntent] 分发为全局设置写入、播放器命令或宿主动作。
 *
 * 听书播放浮层与「朗读设置」路由页共用同一份配置内容与同一份设置语义。
 */
internal fun ReadAloudPlayerViewModel.applyReadAloudConfigIntent(
    intent: ReadAloudConfigIntent,
    onHostAction: (ReadAloudPlayerConfigHostAction) -> Unit,
) {
    when (intent) {
        is ReadAloudConfigIntent.Host -> onHostAction(intent.action)
        is ReadAloudConfigIntent.Set ->
            onConfigIntent(
                option = intent.option,
                value = intent.value,
                selected = intent.selected,
                intValue = intent.intValue,
            )

        ReadAloudConfigIntent.ResetCapsulePosition -> resetCapsulePosition()
        ReadAloudConfigIntent.ClearTtsCache -> clearTtsCache()
    }
}
