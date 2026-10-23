package io.legado.app.domain.model.readaloud

/**
 * 单元内偏移 ↔ 章节位置的唯一映射。
 *
 * 契约：锚点之间恒等平移——`位置 = 锚点.position + (offset - 锚点.offset)`。
 * 未删除任何字符的单元只有一条锚点 `[(0, start)]`，此时同一组查表函数的
 * 结果恒等于 `start + offset`；清洗（删过段落分隔符等）的单元在每个删除块
 * 边界多一条锚点。全程序的位置换算只允许经过这里，禁止任何使用处自行加减。
 */
data class PlaybackPositionMap(
    val anchors: List<Anchor>,
) {
    data class Anchor(val offset: Int, val position: Int)

    init {
        require(anchors.isNotEmpty()) { "anchors must not be empty" }
        require(anchors.first().offset == 0) { "first anchor must sit at offset 0" }
        anchors.zipWithNext().forEach { (cur, next) ->
            require(cur.offset < next.offset) { "anchor offsets must increase" }
            // 删除只可能让位置跳得更远：位置增量必须不小于偏移增量，
            // 否则两条锚点的覆盖区间重叠（映射不再单射）。
            require(
                next.position - cur.position >= next.offset - cur.offset
            ) { "anchor position delta must cover offset delta" }
        }
    }

    /** 单元首字符的章节位置。 */
    val startPosition: Int get() = anchors.first().position

    /** 单元内偏移 → 章节位置。[offset] 须在单元文本范围内；越界收敛到首字符。 */
    fun positionAt(offset: Int): Int {
        val anchor = lastAnchorAtOffset(offset.coerceAtLeast(0))
        return anchor.position + (offset.coerceAtLeast(0) - anchor.offset)
    }

    /**
     * 章节位置 → 单元内偏移。
     *
     * 位置落在删除块（锚点间隙，单元文本里不存在）时向前对齐到下一个实字；
     * 位置早于单元起点时对齐到首字。绝不外推、绝不猜测。
     */
    fun offsetAt(position: Int): Int {
        val anchor = lastAnchorAtPosition(position)
        val next = anchorIndexAfter(anchor)
        return if (next == null) {
            (anchor.offset + (position - anchor.position)).coerceAtLeast(0)
        } else {
            val runEndPosition = positionAt(next.offset - 1)
            if (position <= runEndPosition) {
                (anchor.offset + (position - anchor.position)).coerceAtLeast(0)
            } else {
                next.offset
            }
        }
    }

    /**
     * 单元起点裁剪：把映射整体前移 [offset] 个字符（锚点丢弃被裁前缀）。
     * 与 [PlaybackUnit.text] 的 substring 配套使用，是“从指定位置开始朗读”的唯一裁剪实现。
     */
    fun advancedBy(offset: Int): PlaybackPositionMap {
        if (offset <= 0) return this
        val remaining = anchors.mapNotNull { anchor ->
            anchor.copy(offset = anchor.offset - offset).takeIf { it.offset >= 0 }
        }
        val head = when {
            remaining.firstOrNull()?.offset == 0 -> emptyList()
            else -> listOf(PlaybackPositionMap.Anchor(0, positionAt(offset)))
        }
        val tail = remaining.dropWhile { it.offset == 0 && head.isNotEmpty() }
        return PlaybackPositionMap(head + tail)
    }

    private fun lastAnchorAtOffset(offset: Int): Anchor {
        var result = anchors.first()
        for (anchor in anchors) {
            if (anchor.offset <= offset) result = anchor else break
        }
        return result
    }

    private fun lastAnchorAtPosition(position: Int): Anchor {
        var result = anchors.first()
        for (anchor in anchors) {
            if (anchor.position <= position) result = anchor else break
        }
        return result
    }

    private fun anchorIndexAfter(anchor: Anchor): Anchor? {
        val index = anchors.indexOf(anchor)
        return anchors.getOrNull(index + 1)
    }

    companion object {
        /** 原文连续切片（未删字符）的映射：恒等平移。 */
        fun identity(startPosition: Int): PlaybackPositionMap =
            PlaybackPositionMap(listOf(Anchor(0, startPosition)))
    }
}

/**
 * 引擎无关的播放单元：所有引擎（系统 TTS / HTTP / 云 / 混合）的最小朗读单位。
 *
 * [text] 是发给引擎的原文；[map] 把 text 的每个字符下标映射到章节位置。
 * 章节标题在独立坐标系里（与正文不同轴），因此 [isChapterTitle] 单元不携带映射。
 */
data class PlaybackUnit(
    val text: String,
    val map: PlaybackPositionMap?,
    val isChapterTitle: Boolean = false,
) {
    init {
        require(map != null || isChapterTitle) { "non-title unit must carry a position map" }
    }

    /** 单元首字符的章节位置；标题单元为 0（标题坐标系）。 */
    val startPosition: Int get() = map?.startPosition ?: 0

    companion object {
        fun chapterTitle(title: String): PlaybackUnit =
            PlaybackUnit(text = title, map = null, isChapterTitle = true)
    }
}
