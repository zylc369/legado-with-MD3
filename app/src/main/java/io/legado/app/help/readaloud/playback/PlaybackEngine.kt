package io.legado.app.help.readaloud.playback

import io.legado.app.domain.model.readaloud.PlaybackUnit
import java.io.File

/**
 * 混合朗读的播放端口。
 *
 * 驱动只依赖这个接口，不直接接触 TextToSpeech / ExoPlayer；引擎实现负责“怎么发声/播放”，
 * 驱动负责“什么时候播、播完推进到哪”。位置一律由 [PlaybackUnit] 的锚点表给出。
 */
interface PlaybackEngine {

    /** 系统 TTS 直接朗读一个单元（不落文件）。 */
    suspend fun playSystemUtterance(unit: PlaybackUnit)

    /** 播放一段本地音频文件，直到结束。 */
    suspend fun playAudioFile(file: File)
}
