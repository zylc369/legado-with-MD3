package io.legado.app.feature.reader.core.model

object ReaderTipRowLayout {
    fun lineHeight(fontTopPx: Float, fontBottomPx: Float): Float =
        (fontBottomPx - fontTopPx).coerceAtLeast(0f)

    fun extent(
        paddingTopPx: Float,
        fontTopPx: Float,
        fontBottomPx: Float,
        paddingBottomPx: Float,
        dividerExtentPx: Float = 0f,
        lineCount: Int = 1,
    ): Float = paddingTopPx + lineHeight(fontTopPx, fontBottomPx) * lineCount.coerceAtLeast(1) +
        paddingBottomPx + dividerExtentPx

    fun headerBaseline(paddingTopPx: Float, fontTopPx: Float): Float =
        paddingTopPx - fontTopPx

    fun footerBaseline(heightPx: Float, paddingBottomPx: Float, fontBottomPx: Float): Float =
        heightPx - paddingBottomPx - fontBottomPx
}
