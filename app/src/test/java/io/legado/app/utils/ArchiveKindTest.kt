package io.legado.app.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveKindTest {

    @Test
    fun `非压缩包返回 NotArchive`() {
        assertEquals(
            ArchiveKind.NotArchive,
            ArchiveUtils.archiveKind("三体.txt", listOf("a.txt", "b.jpg"))
        )
        assertEquals(
            ArchiveKind.NotArchive,
            ArchiveUtils.archiveKind("漫画.epub", listOf("1.jpg"))
        )
    }

    @Test
    fun `任一图片条目判定为漫画压缩包`() {
        assertEquals(
            ArchiveKind.ComicArchive,
            ArchiveUtils.archiveKind("漫画.zip", listOf("001.jpg"))
        )
        assertEquals(
            ArchiveKind.ComicArchive,
            ArchiveUtils.archiveKind("漫画.cbz", listOf("a.txt", "002.png"))
        )
    }

    @Test
    fun `仅书籍条目判定为书籍压缩包`() {
        assertEquals(
            ArchiveKind.BookArchive,
            ArchiveUtils.archiveKind("打包.zip", listOf("第一章.txt", "第二章.epub"))
        )
        assertEquals(
            ArchiveKind.BookArchive,
            ArchiveUtils.archiveKind("打包.7z", listOf("书.pdf"))
        )
    }

    @Test
    fun `图片与书籍混合条目按漫画处理`() {
        // 对齐 LocalBook.importFiles 语义：any image entry → 整包漫画直接导入
        assertEquals(
            ArchiveKind.ComicArchive,
            ArchiveUtils.archiveKind("混合.rar", listOf("说明.txt", "cover.webp"))
        )
    }

    @Test
    fun `空条目返回书籍压缩包`() {
        // 空压缩包/不可读压缩包统一按书籍压缩包归类，由导入阶段报错
        assertEquals(
            ArchiveKind.BookArchive,
            ArchiveUtils.archiveKind("空.zip", emptyList())
        )
    }

    @Test
    fun `条目扩展名大小写不敏感`() {
        assertEquals(
            ArchiveKind.ComicArchive,
            ArchiveUtils.archiveKind("漫画.zip", listOf("001.JPG", "002.Png"))
        )
        assertEquals(
            ArchiveKind.BookArchive,
            ArchiveUtils.archiveKind("打包.zip", listOf("书.TXT"))
        )
    }

    @Test
    fun `无扩展名条目不参与漫画判定`() {
        assertEquals(
            ArchiveKind.BookArchive,
            ArchiveUtils.archiveKind("打包.zip", listOf("README", "cover"))
        )
    }
}
