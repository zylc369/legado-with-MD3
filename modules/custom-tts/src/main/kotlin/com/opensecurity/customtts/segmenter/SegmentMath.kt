package com.opensecurity.customtts.segmenter

/**
 * 分段数学原语（移植 PC 前端 segmentText.ts 的 rampFor/normalizeStairs/splitOversize）。
 */
object SegmentMath {

    const val DEFAULT_FIRST_LEN = 20
    const val DEFAULT_NEXT_LEN = 80

    /**
     * 首段长度 → 渐进段长序列（第 n 段 min(n×f, target)，封顶 target）。
     *
     * 爬坡约束: 第 N 段播放时长 ≥ 第 N+1 段生成时长（否则段界断档等待）。
     * f=20 → [20,40,60,80]; f=30 → [30,60,80]; f=60 → [60,80]
     */
    fun rampFor(firstLen: Int, target: Int = DEFAULT_NEXT_LEN): List<Int> {
        val f = maxOf(1, Math.round(firstLen.toDouble()).toInt())
        val out = ArrayList<Int>()
        var n = 1
        while (n <= 100) {
            val v = minOf(f * n, target)
            out.add(v)
            if (v >= target) break
            n++
        }
        return out
    }

    /** 阶梯序列规范化（正整数、≤8 档、升序去重；非法回 null）。 */
    fun normalizeStairs(input: List<Int>?): List<Int>? {
        if (input.isNullOrEmpty()) return null
        val cleaned = LinkedHashSet<Int>()
        for (v in input) {
            val r = Math.round(v.toDouble()).toInt()
            if (r <= 0 || r > 1000) return null
            cleaned.add(r)
        }
        if (cleaned.isEmpty() || cleaned.size > 8) return null
        return cleaned.sorted()
    }

    /** 超长句兜底: 在逗号/顿号处切成 ≤[max] 的片（保持标点在片尾）；仍超则按 [max] 硬切。 */
    fun splitOversize(sentence: String, max: Int): List<String> {
        val softs = setOf('，', ',', '、')
        val parts = ArrayList<String>()
        val cur = StringBuilder()
        for (ch in sentence) {
            cur.append(ch)
            if (cur.length >= max && ch in softs) {
                parts.add(cur.toString())
                cur.setLength(0)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur.toString())
        val final = ArrayList<String>()
        for (p in parts) {
            var i = 0
            while (i < p.length) {
                final.add(p.substring(i, minOf(i + max, p.length)))
                i += max
            }
        }
        return final
    }
}
