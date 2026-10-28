package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 私密分组 → 单本私密 的一次性迁移。
 *
 * 旧机制的"整组隐藏"由 SQL 位掩码实现；新模型只有 books.isPrivate 一种标记。
 * 升级时把私密分组内的书全部转为单本私密，再把分组本身还原为普通分组，
 * 之后 PUBLIC_BOOK_FILTER 只认 isPrivate。
 *
 * 两个入口：
 * - [migrateIfNeeded]：启动同步执行的一次性迁移（幂等，完成标记落 DataStore）；
 * - [recheckIfNeeded]：已迁移过的启动在后台复查——恢复旧备份会把 isPrivate=1 的分组
 *   重新带回来，而完成标记还挂着，不复查这些书会直接暴露在公开书架。
 *
 * 旧版"私密功能总开关=关闭"（PRIVATE_ENABLED=false）语义是"全部可见"：
 * 这类用户迁移时只清分组私密位、不标记书籍，保持升级前后都可见。
 */
class PrivateGroupMigrator(
    private val appDb: AppDatabase,
) {

    /** 启动同步执行；语句极小且仅首个升级启动发生 */
    suspend fun migrateIfNeeded() {
        if (AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_GROUP_MIGRATED.name) == true) {
            return
        }
        migrateOnce()
        AppConfigStore.putAllAndAwait(
            mapOf(LocalPreferencesKeys.PRIVATE_GROUP_MIGRATED.name to true)
        )
    }

    /** 已迁移过的启动后台复查：数据库又出现私密分组（恢复旧备份）时重新迁移 */
    suspend fun recheckIfNeeded() {
        if (AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_GROUP_MIGRATED.name) != true) {
            // 首启阻塞路径尚未跑过（或标记被清），交给 migrateIfNeeded
            return
        }
        if (appDb.bookDao.sumPrivateGroupIds() == 0L) return
        migrateOnce()
    }

    /**
     * 旧模型遗留宽限值的一次性修正。
     *
     * 旧版"验证频率"不是"离开应用前有效"时，宽限期没有意义，但旧设置页每次保存都会
     * 整组写入全部键——遗留的 0 会让新模型变成"离开即失效"。这类用户回默认 60 秒；
     * 真正在旧版选过宽限的用户（频率=UntilBackground）保留其选择。
     */
    suspend fun migrateLegacyGraceIfNeeded() {
        if (AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_GRACE_MIGRATED.name) == true) {
            return
        }
        val oldScope =
            AppConfigStore.getString(LocalPreferencesKeys.PRIVATE_UNLOCK_SCOPE.name)
        val storedGrace = AppConfigStore.getInt(
            LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name
        )
        val next = if (oldScope == "UntilBackground") {
            storedGrace ?: PrivateAccessRepository.DEFAULT_BACKGROUND_GRACE_SECONDS
        } else {
            PrivateAccessRepository.DEFAULT_BACKGROUND_GRACE_SECONDS
        }
        val writes = buildMap {
            put(LocalPreferencesKeys.PRIVATE_GRACE_MIGRATED.name, true)
            if (storedGrace != next) {
                put(LocalPreferencesKeys.PRIVATE_BACKGROUND_GRACE_SECONDS.name, next)
            }
        }
        AppConfigStore.putAllAndAwait(writes)
    }

    /** 旧版共用 PASSWORD 键 → 私密专用密码键 的一次性迁移 */
    suspend fun migratePrivatePasswordIfNeeded() {
        PrivatePasswordStore.migrateLegacyIfNeeded()
    }

    private suspend fun migrateOnce() {
        withContext(Dispatchers.IO) {
            val mask = appDb.bookDao.sumPrivateGroupIds()
            if (mask == 0L) return@withContext
            // 旧总开关显式关闭 = "全部可见"：只摘分组私密位，不标记书籍
            val privacyWasDisabled =
                AppConfigStore.getBoolean(LocalPreferencesKeys.PRIVATE_ENABLED.name) == false
            appDb.runInTransaction {
                if (!privacyWasDisabled) {
                    appDb.bookDao.markGroupBooksPrivate(mask)
                }
                appDb.bookDao.clearGroupPrivateFlags()
            }
        }
    }
}
