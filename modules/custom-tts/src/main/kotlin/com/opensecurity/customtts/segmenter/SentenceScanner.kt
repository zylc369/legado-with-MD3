package com.opensecurity.customtts.segmenter

/**
 * 句块扫描（移植 PC 前端 segmentText.ts 的 scanSentence/splitSentences——逐字符对齐）。
 *
 * 规则:
 * - 句末符: `。！？…!?` 与换行; 英文 `.`（前后皆数字时跳过——小数点防误切）
 * - 句末符后的连续句末符（!!!/？？？/……）与闭引号并入当前句
 * - 纯空白块内部消化（不返回）
 */
object SentenceScanner {

    data class ScanResult(val sent: String, val nextPos: Int)

    private val SENT_ENDERS = setOf('。', '！', '？', '…', '!', '?', '\n')

    /** 闭引号/闭合符（只含「闭」——开引号不能并入，否则会吞下句开头）。 */
    private val CLOSERS = setOf('”', '’', '」', '』', '）', ')', '》')

    private fun isDigit(ch: Char?): Boolean = ch != null && ch in '0'..'9'

    private fun isEnder(text: String, index: Int): Boolean {
        val ch = text[index]
        return if (ch == '.') {
            !(isDigit(text.getOrNull(index - 1)) && isDigit(text.getOrNull(index + 1)))
        } else {
            ch in SENT_ENDERS
        }
    }

    /** 从 [pos] 扫描一个句块（返回 trim 后文本与下一位置）；无更多内容返回 null。 */
    fun scanSentence(text: String, pos: Int): ScanResult? {
        if (pos >= text.length) return null
        var cur = StringBuilder()
        var i = pos
        while (i < text.length) {
            val ch = text[i]
            cur.append(ch)
            if (isEnder(text, i)) {
                var j = i + 1
                while (j < text.length) {
                    val nc = text[j]
                    if (isEnder(text, j) || nc in CLOSERS) {
                        cur.append(nc)
                        j++
                    } else {
                        break
                    }
                }
                val sent = cur.toString().trim()
                if (sent.isNotEmpty()) return ScanResult(sent, j)
                cur = StringBuilder() // 纯空白块 → 继续扫下一块
                i = j
                continue
            }
            i++
        }
        val sent = cur.toString().trim()
        return if (sent.isNotEmpty()) ScanResult(sent, text.length) else null
    }

    /** 全文 → 句块列表（段首尾空白丢弃）。 */
    fun splitSentences(text: String): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        while (true) {
            val sc = scanSentence(text, pos) ?: break
            out.add(sc.sent)
            pos = sc.nextPos
        }
        return out
    }
}
