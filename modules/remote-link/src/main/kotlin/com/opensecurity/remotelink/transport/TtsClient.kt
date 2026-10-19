package com.opensecurity.remotelink.transport

/**
 * HTTP 语义 API 调用客户端抽象。
 *
 * [LocalTtsGateway] 依赖此接口而非具体 [P2pClient]——测试可注入 fake 实现。
 * 生产实现: [P2pClient]（P2P 传输）。
 */
interface TtsClient {

    /**
     * 发起一次 HTTP 语义调用。
     * @param body 结构化 JSON body（协议层编码为 msgpack map——节点侧还原为 dict 后
     *   作为 HTTP JSON body 转发；GET 等无 body 请求传 null）。
     * @param timeoutMs 请求级超时——默认与服务端 TTS 超时对齐（95s）；
     *   混合调度场景传 10s 实现快速降级。
     */
    fun request(
        method: String, path: String, body: Map<String, Any?>?, token: String = "",
        timeoutMs: Int = DEFAULT_API_TIMEOUT_MS,
    ): Triple<Int, String, Any?>

    /** 快速健康探测（用于网关 /health）。 */
    fun ping(): Boolean

    /** 关闭会话（幂等）。 */
    fun close()

    companion object {
        /** > 服务端 TTS 超时（90s）——避免客户端先放弃、服务端继续白跑。 */
        const val DEFAULT_API_TIMEOUT_MS = 95_000
    }
}
