package io.legado.app.utils

/**
 * 压缩包导入类型。
 *
 * 与 [ArchiveUtils.archiveKind] 配套：漫画压缩包可整包直接导入；
 * 书籍压缩包需要先解压到"保存目录"再导入书籍条目。
 */
enum class ArchiveKind {
    NotArchive,
    ComicArchive,
    BookArchive,
}
