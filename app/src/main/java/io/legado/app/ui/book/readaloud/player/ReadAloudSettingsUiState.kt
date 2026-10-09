package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf

/**
 * 朗读设置里可在界面直接修改的选项。
 *
 * 听书播放界面与「朗读设置」路由页都直接消费同一份 [ReadAloudSettingsUiState] 与同一个
 * `ReadAloudConfigContent`，避免两套几乎相同的表单各自漂移。
 */
enum class ReadAloudConfigOption {
    DefaultInterface,
    ShowCapsule,
    CapsuleAutoCollapse,
    IgnoreAudioFocus,
    PauseOnPhoneCall,
    WakeLock,
    KeepOnExit,
    MediaButtonPerNext,
    AndroidMediaControl,
    SystemMediaCompat,
    StreamAudio,
    SpeechAnalysisMode,
    SpeechAnalysisReasoningLevel,
    UseMultiSpeaker,
    ContentSplit,
    PreDownloadNum,
    PreSynthesisConcurrency,
    ParagraphInterval,
    AudioCacheCleanTime,
}

/** 朗读设置界面需要的只读快照。 */
@Stable
data class ReadAloudSettingsUiState(
    val defaultReadAloudInterface: String = "classic",
    val showReadAloudCapsule: Boolean = true,
    val capsuleAutoCollapse: Boolean = true,
    val readAloudIgnoreAudioFocus: Boolean = false,
    val readAloudPauseOnPhoneCall: Boolean = false,
    val readAloudWakeLock: Boolean = false,
    val readAloudKeepOnExit: Boolean = false,
    val readAloudMediaButtonPerNext: Boolean = false,
    val readAloudAndroidMediaControl: Boolean = false,
    val readAloudSystemMediaCompat: Boolean = true,
    val readAloudStreamAudio: Boolean = false,
    val speechAnalysisMode: String = "rule",
    val speechAnalysisReasoningLevel: String = "",
    val useMultiSpeaker: Boolean = true,
    val readAloudContentSplitMode: String = "default",
    val readAloudContentSplitSymbols: ImmutableSet<String> = persistentSetOf(),
    val preDownloadNum: Int = 10,
    val preSynthesisConcurrency: Int = 3,
    val readAloudParagraphInterval: Int = 0,
    val audioCacheCleanTime: Int = 10,
)
