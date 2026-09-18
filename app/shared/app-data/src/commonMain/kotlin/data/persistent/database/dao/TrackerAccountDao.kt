/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.persistent.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * 已授权的第三方 tracker 账号. [trackerId] 例如 `"anilist"`, 与 tracker 实现自身的 ID 一致.
 */
@Entity(tableName = "tracker_account")
data class TrackerAccountEntity(
    @PrimaryKey val trackerId: String,
    val userId: Long,
    val username: String,
    val avatarUrl: String?,
    val accessToken: String,
    /**
     * 登录时从远程读取的评分格式 (例如 AniList 的 `mediaListOptions.scoreFormat`), 各 tracker 含义不同,
     * 原样透传给对应 [me.him188.ani.app.tracker.api.TrackerService] 实现自行解释.
     */
    val scoreFormat: String,
    /**
     * 登录/最近一次校验成功的时间, 用于按 tracker 自身的 token 生命周期假设判断是否需要重新登录
     * (例如 AniList 的 implicit grant token 不返回真实 `expires_in`, 只能假设约 365 天).
     */
    val updatedAtMillis: Long,
)

@Dao
interface TrackerAccountDao {
    @Query("SELECT * FROM tracker_account WHERE trackerId = :trackerId LIMIT 1")
    fun accountFlow(trackerId: String): Flow<TrackerAccountEntity?>

    @Query("SELECT * FROM tracker_account WHERE trackerId = :trackerId LIMIT 1")
    suspend fun getAccount(trackerId: String): TrackerAccountEntity?

    @Upsert
    suspend fun upsertAccount(account: TrackerAccountEntity)

    @Query("DELETE FROM tracker_account WHERE trackerId = :trackerId")
    suspend fun deleteAccount(trackerId: String)
}

@TestOnly
fun createMemoryTrackerAccountDao(): TrackerAccountDao {
    return object : TrackerAccountDao {
        private val store = MutableStateFlow(emptyMap<String, TrackerAccountEntity>())

        override fun accountFlow(trackerId: String): Flow<TrackerAccountEntity?> {
            return store.map { it[trackerId] }
        }

        override suspend fun getAccount(trackerId: String): TrackerAccountEntity? {
            return store.value[trackerId]
        }

        override suspend fun upsertAccount(account: TrackerAccountEntity) {
            store.value = store.value + (account.trackerId to account)
        }

        override suspend fun deleteAccount(trackerId: String) {
            store.value = store.value - trackerId
        }
    }
}
