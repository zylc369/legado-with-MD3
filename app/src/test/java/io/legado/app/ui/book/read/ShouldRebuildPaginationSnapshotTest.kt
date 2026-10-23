package io.legado.app.ui.book.read

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldRebuildPaginationSnapshotTest {

    @Test
    fun `快照缺失但当前章页已成型时重建`() {
        assertTrue(
            shouldRebuildPaginationSnapshot(
                hasSnapshot = false,
                hasShapedPages = true,
                isStreaming = false,
            )
        )
    }

    @Test
    fun `快照存在时不重建`() {
        assertFalse(
            shouldRebuildPaginationSnapshot(
                hasSnapshot = true,
                hasShapedPages = true,
                isStreaming = false,
            )
        )
    }

    @Test
    fun `逐页流出中不重建`() {
        assertFalse(
            shouldRebuildPaginationSnapshot(
                hasSnapshot = false,
                hasShapedPages = true,
                isStreaming = true,
            )
        )
    }

    @Test
    fun `没有成型页不重建`() {
        assertFalse(
            shouldRebuildPaginationSnapshot(
                hasSnapshot = false,
                hasShapedPages = false,
                isStreaming = false,
            )
        )
    }
}
