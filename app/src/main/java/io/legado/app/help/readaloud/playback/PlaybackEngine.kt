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

    /**
     * 播放一段已合成音频（直到 STATE_ENDED）。
     *
     * [bakedSpeed] = 合成时烘进音频的速度；[targetSpeed] = 当前目标速度（现读）。
     * 播放端按 `target/baked` 比值调速：保存新速度立即生效，不等重新合成；
     * 新合成的段 baked == target，比值自然回到 1.0。
     */
    suspend fun playAudioFile(file: File, bakedSpeed: Float, targetSpeed: () -> Float)
}
