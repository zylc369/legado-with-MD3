package io.legado.app.data.repository

import android.net.Uri
import io.legado.app.utils.FileDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class ImportFolderScannerAlgorithmTest {

    /** path 为 /scan 下的相对路径；name 取最后一段。 */
    private fun dir(path: String) = FileDoc(
        name = path.substringAfterLast('/'),
        isDir = true,
        size = 0L,
        lastModified = 0L,
        uri = Uri.fromFile(File("/scan/$path")),
    )

    private fun file(path: String) = FileDoc(
        name = path.substringAfterLast('/'),
        isDir = false,
        size = 10L,
        lastModified = 0L,
        uri = Uri.fromFile(File("/scan/$path")),
    )

    private fun names(docs: List<FileDoc>) = docs.map { it.name }.toSet()

    @Test
    fun `平铺目录仅保留书籍与压缩包`() = runTest {
        val tree = mapOf(
            "root" to listOf(
                file("root/a.txt"), file("root/b.epub"), file("root/c.jpg"),
                file("root/d.zip"), file("root/e.docx"), file("root/ignore")
            ),
        )
        val result = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertEquals(setOf("a.txt", "b.epub", "d.zip"), names(result))
    }

    @Test
    fun `嵌套子目录递归收集`() = runTest {
        val tree = mapOf(
            "root" to listOf(dir("root/l1")),
            "root/l1" to listOf(file("root/l1/one.txt"), dir("root/l1/l2")),
            "root/l1/l2" to listOf(file("root/l1/l2/two.epub"), dir("root/l1/l2/l3")),
            "root/l1/l2/l3" to listOf(file("root/l1/l2/l3/three.pdf")),
        )
        val result = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertEquals(setOf("one.txt", "two.epub", "three.pdf"), names(result))
    }

    @Test
    fun `隐藏文件与隐藏目录被跳过`() = runTest {
        val tree = mapOf(
            "root" to listOf(
                file("root/.secret.txt"), dir("root/.git"),
                dir("root/keep"), file("root/ok.txt")
            ),
            "root/.git" to listOf(file("root/.git/hidden.txt")),
            "root/keep" to listOf(file("root/keep/kept.txt")),
        )
        val result = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertEquals(setOf("ok.txt", "kept.txt"), names(result))
    }

    @Test
    fun `列举返回 null 的目录跳过`() = runTest {
        val tree = mapOf(
            "root" to listOf(dir("root/noAccess"), dir("root/ok"), file("root/a.txt")),
            "root/ok" to listOf(file("root/ok/b.txt")),
        )
        val result = scanImportFolder(dir("root")) { doc ->
            val key = doc.uri.path!!.removePrefix("/scan/")
            if (key == "root/noAccess") null else tree[key]
        }
        assertEquals(setOf("a.txt", "b.txt"), names(result))
    }

    @Test
    fun `列举抛 SecurityException 的子树跳过其余继续`() = runTest {
        val tree = mapOf(
            "root" to listOf(dir("root/denied"), dir("root/ok"), file("root/a.txt")),
            "root/ok" to listOf(file("root/ok/b.txt")),
            "root/denied" to listOf(file("root/denied/c.txt")),
        )
        val result = scanImportFolder(dir("root")) { doc ->
            val key = doc.uri.path!!.removePrefix("/scan/")
            if (key == "root/denied") throw SecurityException("denied") else tree[key]
        }
        assertEquals(setOf("a.txt", "b.txt"), names(result))
    }

    @Test
    fun `目录环不死循环`() = runTest {
        val tree = mapOf(
            "root" to listOf(dir("root/a"), file("root/one.txt")),
            "root/a" to listOf(dir("root"), file("root/a/two.txt")),
        )
        val result = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertEquals(setOf("one.txt", "two.txt"), names(result))
    }

    @Test
    fun `空目录与无书籍目录返回空`() = runTest {
        val empty = scanImportFolder(dir("root")) { listOf() }
        assertTrue(empty.isEmpty())

        val tree = mapOf(
            "root" to listOf(file("root/a.jpg"), file("root/b.mp3"), dir("root/c")),
            "root/c" to listOf(file("root/c/d.png")),
        )
        val noBook = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertTrue(noBook.isEmpty())
    }

    @Test
    fun `同名不同目录文件都保留`() = runTest {
        val tree = mapOf(
            "root" to listOf(dir("root/x"), dir("root/y")),
            "root/x" to listOf(file("root/x/书.txt")),
            "root/y" to listOf(file("root/y/书.txt")),
        )
        val result = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertEquals(2, result.size)
        assertEquals(2, result.map { it.toString() }.toSet().size)
    }

    @Test
    fun `扩展名大小写不敏感`() = runTest {
        // bookFileRegex 与 archiveFileRegex 均忽略大小写
        val tree = mapOf(
            "root" to listOf(file("root/a.ZIP"), file("root/b.zip"), file("root/c.TXT")),
        )
        val result = scanImportFolder(dir("root")) { doc -> tree[doc.uri.path!!.removePrefix("/scan/")] }
        assertEquals(setOf("a.ZIP", "b.zip", "c.TXT"), names(result))
    }

    @Test
    fun `协程取消时不返回部分结果`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var completed = false
        val job = launch {
            scanImportFolder(dir("root")) { gate.await(); listOf(file("root/a.txt")) }
            completed = true
        }
        runCurrent()
        assertFalse(completed)
        job.cancelAndJoin()
        assertFalse(completed)
    }
}
