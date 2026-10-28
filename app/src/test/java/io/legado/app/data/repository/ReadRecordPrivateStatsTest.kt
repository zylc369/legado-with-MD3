package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.time.LocalDate

/**
 * 私密书不产生任何阅读统计：
 * - 写入侧：saveReadSession 对私密书直接丢弃；
 * - 读取侧：首页/统计 SQL 排除私密书（覆盖标记前就存在的老记录）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ReadRecordPrivateStatsTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: ReadRecordRepository

    private val privateBook = "http://example.com/book/private"
    private val publicBook = "http://example.com/book/public"

    @Before
    fun setUp() {
        AppConfigStore.init(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = ReadRecordRepository(db.readRecordDao, db, SettingsRepository())
        runBlocking {
            db.bookDao.insert(newBook(privateBook, "私密书"), newBook(publicBook, "公开书"))
            db.bookDao.setBooksPrivate(setOf(privateBook), true)
            // 标记私密前就存在的历史记录（验证读取侧过滤）
            db.readRecordDao.insert(
                ReadRecord(bookName = "私密书", bookAuthor = "作者", readTime = 1_000L),
                ReadRecord(bookName = "公开书", bookAuthor = "作者", readTime = 2_000L),
            )
            db.readRecordDao.insertDetail(
                ReadRecordDetail(bookName = "私密书", bookAuthor = "作者", date = today(), readTime = 1_000L)
            )
            db.readRecordDao.insertDetail(
                ReadRecordDetail(bookName = "公开书", bookAuthor = "作者", date = today(), readTime = 2_000L)
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun today(): String = LocalDate.now().toString()

    private fun newBook(url: String, name: String) = SearchBook(
        bookUrl = url,
        origin = "http://example.com",
        name = name,
        author = "作者"
    ).toBook()

    @Test
    fun totalReadTime_excludesPrivateBook() = runBlocking {
        assertEquals(2_000L, repository.getTotalReadTime().first())
    }

    @Test
    fun dailyReadTime_excludesPrivateBook() = runBlocking {
        val times = db.readRecordDao.observeReadTimeByDate(today()).first()
        assertEquals(2_000L, times)
    }

    @Test
    fun totalReadBookCount_excludesPrivateBook() = runBlocking {
        assertEquals(1, db.readRecordDao.observeTotalReadBookCount().first())
    }

    @Test
    fun recentHomeBooks_excludesPrivateBook() = runBlocking {
        val rows = db.readRecordDao.observeRecentHomeBooks(10).first()
        assertEquals(listOf(publicBook), rows.map { it.bookUrl })
    }

    @Test
    fun saveReadSession_skipsPrivateBook() = runBlocking {
        val session = ReadRecordSession(
            deviceId = "",
            bookName = "私密书",
            bookAuthor = "作者",
            bookUrl = privateBook,
            startTime = System.currentTimeMillis() - 60_000,
            endTime = System.currentTimeMillis(),
            words = 100,
        )

        repository.saveReadSession(session)

        assertTrue(db.readRecordDao.allSession.isEmpty())
    }

    @Test
    fun saveReadSession_recordsPublicBook() = runBlocking {
        val session = ReadRecordSession(
            deviceId = "",
            bookName = "公开书",
            bookAuthor = "作者",
            bookUrl = publicBook,
            startTime = System.currentTimeMillis() - 60_000,
            endTime = System.currentTimeMillis(),
            words = 100,
        )

        repository.saveReadSession(session)

        assertEquals(1, db.readRecordDao.allSession.size)
    }
}
