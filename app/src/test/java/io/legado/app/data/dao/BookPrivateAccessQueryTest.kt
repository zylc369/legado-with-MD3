package io.legado.app.data.dao

import androidx.room.Room
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 私密判定的 SQL 侧（新模型：只有单本私密一种机制）。
 *
 * 公开/私密的分离全部由 SQL 完成：isPrivate = 1 的书不进入任何公开
 * 列表/分组/计数/搜索，只出现在私密书架查询里——换成内存实现就验证不到了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class BookPrivateAccessQueryTest {

    private lateinit var db: AppDatabase

    private val groupId = 0b1000L
    private val publicBook = "http://example.com/book/public"
    private val privateBook = "http://example.com/book/private"
    private val anotherPrivateBook = "http://example.com/book/private-2"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        runBlocking {
            db.bookGroupDao.insert(BookGroup(groupId = groupId, groupName = "分组"))
            db.bookDao.insert(
                newBook(publicBook, groupId),
                newBook(privateBook, groupId),
                newBook(anotherPrivateBook, 0L)
            )
            db.bookDao.setBooksPrivate(
                setOf(privateBook, anotherPrivateBook),
                isPrivate = true
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun singleBookMark_roundTripsThroughSetAndQuery() = runBlocking {
        assertEquals(
            listOf(anotherPrivateBook, privateBook).sorted(),
            db.bookDao.flowPrivateBookUrls().first().sorted()
        )
    }

    @Test
    fun singleBookMark_canBeRemovedAgain() = runBlocking {
        db.bookDao.setBooksPrivate(setOf(privateBook), isPrivate = false)

        assertEquals(listOf(anotherPrivateBook), db.bookDao.flowPrivateBookUrls().first())
    }

    @Test
    fun isBookPrivate_matchesMark() = runBlocking {
        assertTrue(db.bookDao.isBookPrivate(privateBook))
        assertFalse(db.bookDao.isBookPrivate(publicBook))
    }

    @Test
    fun isBookPrivateByName_matchesMark() = runBlocking {
        val book = db.bookDao.getBook(privateBook)!!
        assertTrue(db.bookDao.isBookPrivateByName(book.name, book.author))
        val public = db.bookDao.getBook(publicBook)!!
        assertFalse(db.bookDao.isBookPrivateByName(public.name, public.author))
    }

    @Test
    fun isBookPrivate_unknownBookIsNotPrivate() = runBlocking {
        assertFalse(db.bookDao.isBookPrivate("http://example.com/book/none"))
    }

    @Test
    fun privateBooks_areHiddenFromPublicShelf() = runBlocking {
        val urls = db.bookDao.flowBookShelf().first().map { it.bookUrl }

        assertEquals(listOf(publicBook), urls)
    }

    @Test
    fun privateBooks_areHiddenFromTheirGroupAndCounts() = runBlocking {
        // 分组列表与分组计数都必须看不见私密书
        val urls = db.bookDao.flowBookShelfByUserGroup(groupId).first().map { it.bookUrl }
        assertEquals(listOf(publicBook), urls)
        assertEquals(1, db.bookDao.flowUserGroupBookCount(groupId).first())
    }

    @Test
    fun privateShelf_showsOnlyPrivateBooks() = runBlocking {
        val urls = db.bookDao.flowPrivateBookShelf().first().map { it.bookUrl }

        assertEquals(listOf(anotherPrivateBook, privateBook).sorted(), urls.sorted())
    }

    @Test
    fun unmark_bringsBookBackToPublicQueries() = runBlocking {
        db.bookDao.setBooksPrivate(setOf(privateBook), isPrivate = false)

        val urls = db.bookDao.flowBookShelfByUserGroup(groupId).first().map { it.bookUrl }
        assertEquals(listOf(privateBook, publicBook).sorted(), urls.sorted())
    }

    // ---------- 私密分组一次性迁移（旧数据 → 单本私密） ----------

    @Test
    fun migrator_convertsPrivateGroupBooksToSingleMarks() = runBlocking {
        val legacyPrivateGroupId = 0b10L
        val legacyGroupBook = "http://example.com/book/legacy-private"
        db.bookGroupDao.insert(
            BookGroup(groupId = legacyPrivateGroupId, groupName = "旧私密分组", isPrivate = true)
        )
        db.bookDao.insert(newBook(legacyGroupBook, legacyPrivateGroupId))

        val mask = db.bookDao.sumPrivateGroupIds()
        db.bookDao.markGroupBooksPrivate(mask)
        db.bookDao.clearGroupPrivateFlags()

        assertTrue(db.bookDao.isBookPrivate(legacyGroupBook))
        // 旧私密分组的书进入私密书架；分组本身不再是私密
        assertTrue(
            legacyGroupBook in db.bookDao.flowPrivateBookShelf().first().map { it.bookUrl }
        )
        assertEquals(0L, db.bookDao.sumPrivateGroupIds())
        // 新模型下公开分组查询按 isPrivate 过滤，组内已迁移的书不再可见
        assertFalse(
            legacyGroupBook in db.bookDao
                .flowBookShelfByUserGroup(legacyPrivateGroupId).first().map { it.bookUrl }
        )
    }

    @Test
    fun migrator_clearFlagsOnCleanDatabaseIsNoop() = runBlocking {
        // 没有任何私密分组时迁移不误伤任何书
        db.bookDao.markGroupBooksPrivate(db.bookDao.sumPrivateGroupIds())
        db.bookDao.clearGroupPrivateFlags()

        assertTrue(db.bookDao.isBookPrivate(publicBook).not())
        assertEquals(
            listOf(anotherPrivateBook, privateBook).sorted(),
            db.bookDao.flowPrivateBookUrls().first().sorted()
        )
    }

    private fun newBook(url: String, group: Long) = SearchBook(
        bookUrl = url,
        origin = "http://example.com",
        name = "书-$url",
        author = "作者"
    ).toBook().apply {
        this.group = group
    }
}
