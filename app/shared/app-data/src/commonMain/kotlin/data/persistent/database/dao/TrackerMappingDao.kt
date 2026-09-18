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
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * Bangumi 条目到某个远程站点 ID 的映射, 来自 `bangumi-data` 订阅 (见 [TrackerMappingSubscription]).
 * [site] 使用 bangumi-data 自身的站点 key (例如 `"aniList"`, `"mal"`), 不与 [me.him188.ani.app.tracker.api.TrackerService.id] 强制一致,
 * 由 tracker 实现自行映射.
 */
@Entity(
    tableName = "tracker_mapping",
    primaryKeys = ["bangumiId", "site"],
    indices = [Index(value = ["site", "externalId"])],
)
data class TrackerMappingEntity(
    val bangumiId: Int,
    val site: String,
    val externalId: String,
    val subscriptionId: String,
)

@Dao
interface TrackerMappingDao {
    @Query("SELECT * FROM tracker_mapping WHERE bangumiId = :bangumiId AND site = :site LIMIT 1")
    fun mappingFlow(bangumiId: Int, site: String): Flow<TrackerMappingEntity?>

    @Query("SELECT * FROM tracker_mapping WHERE bangumiId = :bangumiId AND site = :site LIMIT 1")
    suspend fun getMapping(bangumiId: Int, site: String): TrackerMappingEntity?

    @Query("SELECT * FROM tracker_mapping WHERE site = :site")
    suspend fun getMappingsBySite(site: String): List<TrackerMappingEntity>

    @Upsert
    suspend fun upsertMapping(mapping: TrackerMappingEntity)

    @Upsert
    suspend fun upsertMappings(mappings: List<TrackerMappingEntity>)

    @Query("DELETE FROM tracker_mapping WHERE bangumiId = :bangumiId AND site = :site")
    suspend fun deleteMapping(bangumiId: Int, site: String)

    @Query("DELETE FROM tracker_mapping WHERE subscriptionId = :subscriptionId AND bangumiId NOT IN (:keepBangumiIds)")
    suspend fun deleteStale(subscriptionId: String, keepBangumiIds: Collection<Int>)
}

@TestOnly
fun createMemoryTrackerMappingDao(): TrackerMappingDao {
    return object : TrackerMappingDao {
        private val store = MutableStateFlow(emptyList<TrackerMappingEntity>())

        private fun List<TrackerMappingEntity>.find(bangumiId: Int, site: String) =
            find { it.bangumiId == bangumiId && it.site == site }

        override fun mappingFlow(bangumiId: Int, site: String): Flow<TrackerMappingEntity?> {
            return store.map { it.find(bangumiId, site) }
        }

        override suspend fun getMapping(bangumiId: Int, site: String): TrackerMappingEntity? {
            return store.value.find(bangumiId, site)
        }

        override suspend fun getMappingsBySite(site: String): List<TrackerMappingEntity> {
            return store.value.filter { it.site == site }
        }

        override suspend fun upsertMapping(mapping: TrackerMappingEntity) {
            upsertMappings(listOf(mapping))
        }

        override suspend fun upsertMappings(mappings: List<TrackerMappingEntity>) {
            var current = store.value
            for (mapping in mappings) {
                val existing = current.find(mapping.bangumiId, mapping.site)
                current = if (existing == null) current + mapping else current.map { if (it === existing) mapping else it }
            }
            store.value = current
        }

        override suspend fun deleteMapping(bangumiId: Int, site: String) {
            store.value = store.value.filterNot { it.bangumiId == bangumiId && it.site == site }
        }

        override suspend fun deleteStale(subscriptionId: String, keepBangumiIds: Collection<Int>) {
            store.value = store.value.filterNot {
                it.subscriptionId == subscriptionId && it.bangumiId !in keepBangumiIds
            }
        }
    }
}
