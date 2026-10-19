package com.opensecurity.customtts.segmenter

import com.opensecurity.customtts.model.CustomTtsSegment

/**
 * 听书分段器（移植 PC 前端 segmentText.ts 的 segmentText/segmentWithOffsets——逐段一致）。
 *
 * 规则:
 * - 切点只落句末（句子永不劈开）
 * - 首段阶梯（20→40→60→80）保证"本段播放时长 ≥ 下段生成时长"，全链无断档
 * - 首句过长时在逗号处提前断（首响优先）
 * - 超长句在逗号/顿号硬切兜底；尾段并回防孤尾
 */
object Segmenter {

    private val SOFT_PUNCT = setOf('，', '、', '；', '：', ',', ';', ':')

    private fun isDigit(ch: Char?): Boolean = ch != null && ch in '0'..'9'

    /**
     * 超限守门（v2）: 源片 [p] 的超软/硬限裁切点（p 内下标，切点前含）。
     * [cv]=当前段已积累长度；soft=当段目标+首段；hard=首段×2+当段目标。
     * 返回 null=整吞；否则 cur+p[0,cut) 收段、p[cut,..) 继续处理。
     */
    private fun overflowCut(p: String, cv: Int, goal: Int, first: Int): Int? {
        val soft = goal + first
        val hard = 2 * first + goal
        if (cv + p.length <= soft) return null
        val limit = minOf(p.length, hard - cv)
        val softStart = maxOf(0, soft - cv)
        // ① 超限点之后的软标点（语义首选——"超限后遇到即断"）
        for (i in softStart until limit) {
            val ch = p[i]
            if (ch in SOFT_PUNCT && !(isDigit(p.getOrNull(i - 1)) && isDigit(p.getOrNull(i + 1)))) {
                return i + 1
            }
        }
        // ② 回落到"本片内、超限点之前"最近的软标点（下界守卫: 切出的段须 ≥ 首段目标）
        for (i in minOf(softStart, p.length) - 1 downTo 0) {
            if (cv + i + 1 < first) break
            val ch = p[i]
            if (ch in SOFT_PUNCT && !(isDigit(p.getOrNull(i - 1)) && isDigit(p.getOrNull(i + 1)))) {
                return i + 1
            }
        }
        // ③ 硬结束（无软标点且超硬限）
        if (cv + p.length > hard) return maxOf(1, limit)
        return null
    }

    /** 首段逗号级切分: 首句超过 [goal]×1.5 时在 ≥[goal] 的首个逗号/分号处断（首响优先）。 */
    private fun cutFirstComma(sentence: String, goal: Int): Int {
        if (sentence.length <= goal * 1.5) return -1
        for (i in goal until sentence.length) {
            val ch = sentence[i]
            if (ch == '，' || ch == '；') return i + 1
        }
        return -1
    }

    /** 全量分段（与 TS 版逐段一致）。 */
    fun segmentText(
        text: String,
        target: Int = SegmentMath.DEFAULT_NEXT_LEN,
        hardMax: Int = 0,
        firstTargets: List<Int> = SegmentMath.rampFor(SegmentMath.DEFAULT_FIRST_LEN, target),
    ): List<String> {
        val hm = if (hardMax > 0) hardMax else maxOf((target * 1.5).toInt(), 120)
        val sentences = SentenceScanner.splitSentences(text)
        val pieces = ArrayList<String>()
        for (s in sentences) {
            if (s.length > hm) pieces.addAll(SegmentMath.splitOversize(s, hm / 2))
            else pieces.add(s)
        }
        // 首段逗号级切分（首句过长时提前断——长首句不拖慢首响）
        if (pieces.isNotEmpty() && firstTargets.isNotEmpty()) {
            val cut = cutFirstComma(pieces[0], firstTargets[0])
            if (cut > 0) {
                val head = pieces[0].substring(0, cut)
                val rest = pieces[0].substring(cut)
                pieces.removeAt(0)
                pieces.add(0, rest)
                pieces.add(0, head)
            }
        }
        val segs = ArrayList<String>()
        var cur = ""
        val firstGoal = if (firstTargets.isNotEmpty()) firstTargets[0] else SegmentMath.DEFAULT_FIRST_LEN

        fun goalNow(): Int = if (segs.size < firstTargets.size) firstTargets[segs.size] else target

        for (p0 in pieces) {
            val queue = ArrayDeque<String>()
            queue.addLast(p0)
            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                // 预检收段（防贪心拖拽超长——超长段生成慢会拖垮无缝链）
                var g = goalNow()
                if (cur.isNotEmpty() && cur.length >= g * 0.5 && cur.length + p.length > g * 1.5) {
                    segs.add(cur)
                    cur = ""
                    g = goalNow()
                }
                // v2 超限守门
                val cut = overflowCut(p, cur.length, g, firstGoal)
                if (cut != null) {
                    segs.add(cur + p.substring(0, cut))
                    cur = ""
                    val rest = p.substring(cut)
                    if (rest.isNotEmpty()) queue.addFirst(rest)
                    continue
                }
                cur += p
                if (cur.length >= goalNow()) {
                    segs.add(cur)
                    cur = ""
                }
            }
        }
        if (cur.isNotBlank()) {
            // 短尾段并回前段（防"孤尾"）
            val tail = cur
            val prev = segs.lastOrNull()
            val mergeLimit = maxOf(target * 2, 160)
            if (prev != null && tail.length < firstGoal && prev.length + tail.length <= mergeLimit) {
                segs[segs.lastIndex] = prev + tail
            } else {
                segs.add(tail)
            }
        }
        return segs
    }

    /**
     * 从 [from] 起在原文中定位 [seg]：逐字符匹配，原文中的空白字符（换行/缩进）
     * 可被跳过（段文本已被 trim / 跨段拼接）。返回匹配区间；失败返回 null。
     */
    private fun matchSegment(text: String, seg: String, from: Int): IntRange? {
        var i = from
        var j = 0
        var start = -1
        while (i < text.length && j < seg.length) {
            val c = text[i]
            when {
                c == seg[j] -> {
                    if (start < 0) start = i
                    i++
                    j++
                }
                c.isWhitespace() -> i++
                else -> return null
            }
        }
        return if (j == seg.length && start >= 0) start until i else null
    }

    /** 带偏移的分段（本章朗读文本流——偏移用于进度/高亮映射）。 */
    fun segmentWithOffsets(
        text: String,
        target: Int = SegmentMath.DEFAULT_NEXT_LEN,
        hardMax: Int = 0,
        firstTargets: List<Int> = SegmentMath.rampFor(SegmentMath.DEFAULT_FIRST_LEN, target),
    ): List<CustomTtsSegment> {
        val segments = segmentText(text, target, hardMax, firstTargets)
        var cursor = 0
        return segments.map { seg ->
            // 句块扫描会 trim 段首尾空白，跨自然段的段内部还夹着原文换行/缩进——
            // 用"空白容忍匹配"定位原文区间（逐字符前进、跳过原文空白）
            val range = matchSegment(text, seg, cursor)
            val from: Int
            val end: Int
            if (range != null) {
                from = range.first
                end = range.last + 1
            } else {
                from = cursor
                end = (from + seg.length).coerceAtMost(text.length)
            }
            cursor = end
            CustomTtsSegment(text = seg, startOffset = from, endOffset = end)
        }
    }
}
