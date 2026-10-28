package io.legado.app.data.repository

import android.app.Application
import androidx.room.Room
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.help.config.AppConfigStore
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
import splitties.init.injectAsAppCtx

/**
 * 私密分组一次性迁移的行为面：标记门控、幂等、旧总开关语义、备份恢复复查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PrivateGroupMigratorTest {

    private lateinit var db: AppDatabase
    private lateinit var migrator: PrivateGroupMigrator

    private val legacyPrivateGroupId = 0b10L
    private val legacyGroupBook = "http://example.com/book/legacy"

    @Before
    fun setUp() {
        AppConfigStore.init(RuntimeEnvironment.getApplication())
        RuntimeEnvironment.getApplication().injectAsAppCtx()
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        migrator = PrivateGroupMigrator(db)
        runBlocking {
            // Robolectric 同一测试类内静态偏好跨用例存活：逐用例复位迁移标志与遗留键
            AppConfigStore.putAllAndAwait(
                mapOf(
                    LocalPreferencesKeys.PRIVATE_GROUP_MIGRATED.name to false,
                    LocalPreferencesKeys.PRIVATE_GRACE_MIGRATED.name to false,
                    LocalPreferencesKeys.PRIVATE_UNLOCK_SCOPE.name to null,
                    LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name to null,
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedLegacyPrivateGroup() {
        db.bookGroupDao.insert(
            BookGroup(groupId = legacyPrivateGroupId, groupName = "旧私密分组", isPrivate = true)
        )
        db.bookDao.insert(newBook(legacyGroupBook, legacyPrivateGroupId))
    }

    @Test
    fun migrateIfNeeded_convertsAndSetsFlag() = runBlocking {
        seedLegacyPrivateGroup()

        migrator.migrateIfNeeded()

        assertTrue(db.bookDao.isBookPrivate(legacyGroupBook))
        assertEquals(0L, db.bookDao.sumPrivateGroupIds())
        assertTrue(
            AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_GROUP_MIGRATED.name) == true
        )
    }

    @Test
    fun migrateIfNeeded_isIdempotentByFlag() = runBlocking {
        seedLegacyPrivateGroup()
        migrator.migrateIfNeeded()

        // 再造一个私密分组但完成标记已置：同步入口必须跳过（备份恢复场景走复查入口）
        val newGroupId = 0b100_000L
        db.bookGroupDao.insert(
            BookGroup(groupId = newGroupId, groupName = "又一个", isPrivate = true)
        )
        migrator.migrateIfNeeded()

        assertEquals(newGroupId, db.bookDao.sumPrivateGroupIds())
    }

    @Test
    fun recheckIfNeeded_migratesGroupsReintroducedByBackupRestore() = runBlocking {
        seedLegacyPrivateGroup()
        migrator.migrateIfNeeded()

        // 恢复旧备份：私密分组 + isPrivate=0 的书重新进库
        db.bookGroupDao.insert(
            BookGroup(groupId = legacyPrivateGroupId, groupName = "旧私密分组", isPrivate = true)
        )
        db.bookDao.setBooksPrivate(setOf(legacyGroupBook), false)

        migrator.recheckIfNeeded()

        assertTrue(db.bookDao.isBookPrivate(legacyGroupBook))
        assertEquals(0L, db.bookDao.sumPrivateGroupIds())
    }

    @Test
    fun legacyEnabledFalse_keepsBooksVisible() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PRIVATE_ENABLED.name to false)
        )
        seedLegacyPrivateGroup()

        migrator.migrateIfNeeded()

        // 旧语义"总开关关闭=全部可见"：分组摘私密位，但书不标记、保持公开可见
        assertFalse(db.bookDao.isBookPrivate(legacyGroupBook))
        assertEquals(0L, db.bookDao.sumPrivateGroupIds())
        assertTrue(
            legacyGroupBook in db.bookDao.flowBookShelf().first().map { it.bookUrl }
        )
    }

    @Test
    fun migrateOnCleanDatabase_isNoopAndStillSetsFlag() = runBlocking {
        migrator.migrateIfNeeded()

        assertTrue(
            AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_GROUP_MIGRATED.name) == true
        )
        assertTrue(db.bookDao.flowPrivateBookUrls().first().isEmpty())
    }

    // ---------- 旧模型遗留宽限值的一次性修正 ----------

    @Test
    fun legacyGrace_zeroFromNonUntilBackgroundScope_resetsToDefault() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_UNLOCK_SCOPE.name to "AppSession",
                LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name to 0,
            )
        )

        migrator.migrateLegacyGraceIfNeeded()

        assertEquals(
            PrivateAccessRepository.DEFAULT_BACKGROUND_GRACE_SECONDS,
            AppConfigStore.getInt(LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name),
        )
    }

    @Test
    fun legacyGrace_untilBackgroundUserChoice_isKept() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_UNLOCK_SCOPE.name to "UntilBackground",
                LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name to 30,
            )
        )

        migrator.migrateLegacyGraceIfNeeded()

        assertEquals(
            30,
            AppConfigStore.getInt(LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name),
        )
    }

    @Test
    fun legacyGrace_freshInstall_setsFlagWithoutWrite() = runBlocking {
        migrator.migrateLegacyGraceIfNeeded()

        assertTrue(
            AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_GRACE_MIGRATED.name) == true
        )
        // 未写入具体值：读取走代码默认 60
        assertEquals(
            PrivateAccessRepository.DEFAULT_BACKGROUND_GRACE_SECONDS,
            AppConfigStore.getInt(
                LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name
            ) ?: PrivateAccessRepository.DEFAULT_BACKGROUND_GRACE_SECONDS,
        )
    }

    @Test
    fun legacyGrace_idempotentByFlag() = runBlocking {
        AppConfigStore.putAllAndAwait(
            mapOf(
                LocalPreferencesKeys.PRIVATE_UNLOCK_SCOPE.name to "EveryTime",
                LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name to 0,
            )
        )
        migrator.migrateLegacyGraceIfNeeded()
        // 用户此后明确选择 0（"立即"），标志已置，不得再被改写
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name to 0)
        )

        migrator.migrateLegacyGraceIfNeeded()

        assertEquals(
            0,
            AppConfigStore.getInt(LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name),
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
