package io.legado.app.help.readaloud.playback

import com.opensecurity.customtts.model.CustomTtsVoiceParams
import java.net.URLEncoder

/**
 * 定制引擎 URL 构造（纯函数——单测友好）。
 *
 * 输入定制引擎模板（含 {{speakText}}，不含 {{speakSpeed}}），
 * 展开文本并追加 voice/speed/instruct/seed/mode（服务端语义：speed 0.5..3.0）。
 */
object CustomTtsUrlBuilder {

    fun buildCustomTtsUrl(
        templateUrl: String,
        text: String,
        params: CustomTtsVoiceParams,
        requestTimeoutMs: Int? = null,
    ): String {
        val expanded = templateUrl.replace("{{speakText}}", encode(text))
        val sb = StringBuilder(expanded)
        var first = !expanded.contains('?')

        fun append(key: String, value: String) {
            sb.append(if (first) '?' else '&')
                .append(key).append('=').append(encode(value))
            first = false
        }

        if (params.voiceId.isNotEmpty()) append("voice", params.voiceId)
        append("speed", params.speed.toString())
        if (params.instruct.isNotEmpty()) append("instruct", params.instruct)
        if (params.seed >= 0) append("seed", params.seed.toString())
        if (params.mode.isNotEmpty()) append("mode", params.mode)
        // 网关请求级超时（混合调度 10s 快速降级；服务端不消费该参数）
        if (requestTimeoutMs != null && requestTimeoutMs > 0) {
            append("timeout_ms", requestTimeoutMs.toString())
        }
        return sb.toString()
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
}
