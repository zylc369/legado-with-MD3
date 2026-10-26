package io.legado.app.utils

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppPattern.archiveFileRegex
import io.legado.app.utils.compress.LibArchiveUtils
import splitties.init.appCtx
import java.io.File

/* 自动判断压缩文件后缀 然后再调用具体的实现 */
@Suppress("unused", "MemberVisibilityCanBePrivate")
object ArchiveUtils {

    const val TEMP_FOLDER_NAME = "ArchiveTemp"

    // 临时目录 下次启动自动删除
    val TEMP_PATH: String by lazy {
        appCtx.externalCache.getFile(TEMP_FOLDER_NAME).createFolderReplace().absolutePath
    }

    fun deCompress(
        archiveUri: Uri,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromUri(archiveUri, false), path, filter)
    }

    fun deCompress(
        archivePath: String,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(Uri.parse(archivePath), path, filter)
    }

    fun deCompress(
        archiveFile: File,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromFile(archiveFile), path, filter)
    }

    fun deCompress(
        archiveDoc: DocumentFile,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        return deCompress(FileDoc.fromDocumentFile(archiveDoc), path, filter)
    }

    fun deCompress(
        archiveFileDoc: FileDoc,
        path: String = TEMP_PATH,
        filter: ((String) -> Boolean)? = null
    ): List<File> {
        if (archiveFileDoc.isDir) throw IllegalArgumentException("Unexpected Folder input")
        val name = archiveFileDoc.name
        checkAchieve(name)
        val workPathFileDoc = getCacheFolderFileDoc(name, path)
        val workPath = workPathFileDoc.toString()

        return archiveFileDoc.openReadPfd().getOrThrow().use {
            LibArchiveUtils.unArchive(it, File(workPath), filter)
        }

    }

    /* 遍历目录获取文件名 */
    fun getArchiveFilesName(fileUri: Uri, filter: ((String) -> Boolean)? = null): List<String> =
        getArchiveFilesName(FileDoc.fromUri(fileUri, false), filter)


    fun getArchiveFilesName(
        fileDoc: FileDoc,
        filter: ((String) -> Boolean)? = null
    ): List<String> {
        val name = fileDoc.name
        checkAchieve(name)

        return fileDoc.openReadPfd().getOrThrow().use {
            try {
                LibArchiveUtils.getFilesName(it, filter)
            } catch (e: Exception) {
                emptyList()
            }

        }


    }

    fun isArchive(name: String): Boolean {
        return archiveFileRegex.matches(name)
    }

    private val comicImageExtensions =
        setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

    /**
     * 依据压缩包内条目判断导入类型：
     * - [ArchiveKind.NotArchive] 非压缩包；
     * - [ArchiveKind.ComicArchive] 任一条目为图片 → 整包按漫画直接导入，不需要解压保存目录；
     * - [ArchiveKind.BookArchive] 其余压缩包 → 需要解压到保存目录后导入书籍条目。
     */
    fun archiveKind(name: String, entries: List<String>): ArchiveKind {
        if (!isArchive(name)) return ArchiveKind.NotArchive
        val isComic = entries.any {
            it.substringAfterLast('.', "").lowercase() in comicImageExtensions
        }
        return if (isComic) ArchiveKind.ComicArchive else ArchiveKind.BookArchive
    }

    private fun checkAchieve(name: String) {
        if (!isArchive(name))
            throw IllegalArgumentException("Unexpected file suffix: Only 7z rar zip Accepted")
    }

    private fun getCacheFolderFileDoc(
        archiveName: String,
        workPath: String
    ): FileDoc {
        return FileDoc.fromUri(Uri.parse(workPath), true)
            .createFolderIfNotExist(MD5Utils.md5Encode16(archiveName))
    }
}