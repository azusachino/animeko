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
import androidx.room.ForeignKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.him188.ani.utils.platform.annotations.TestOnly

/**
 * 一个 Bangumi 条目与某个第三方 tracker 远程条目的绑定关系.
 *
 * [subjectId] 外键级联 [SubjectCollectionEntity], 条目被删除时绑定关系一并清除.
 */
@Entity(
    tableName = "tracker_binding",
    primaryKeys = ["subjectId", "trackerId"],
    foreignKeys = [
        ForeignKey(
            entity = SubjectCollectionEntity::class,
            parentColumns = ["subjectId"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TrackerBindingEntity(
    val subjectId: Int,
    val trackerId: String,
    val remoteMediaId: Int,
    val remoteTitle: String,
    val totalEpisodes: Int?,
    val lastTrackedEpisode: Float = 0f,
    /**
     * Animeko 集数 + episodeOffset = 远程集数. 用于应对分季在远程被拆分 (例如 Cour 2) 的情况.
     */
    val episodeOffset: Int = 0,
    /**
     * 远程状态字符串, 由具体 tracker 实现自行解释 (例如 AniList `MediaListStatus`).
     */
    val status: String? = null,
    val score: Float? = null,
    val updatedAtMillis: Long,
)

@Dao
interface TrackerBindingDao {
    @Query("SELECT * FROM tracker_binding WHERE subjectId = :subjectId")
    fun bindingsForSubjectFlow(subjectId: Int): Flow<List<TrackerBindingEntity>>

    @Query("SELECT * FROM tracker_binding WHERE subjectId = :subjectId")
    suspend fun getBindingsForSubject(subjectId: Int): List<TrackerBindingEntity>

    @Query("SELECT * FROM tracker_binding WHERE subjectId = :subjectId AND trackerId = :trackerId LIMIT 1")
    suspend fun getBinding(subjectId: Int, trackerId: String): TrackerBindingEntity?

    @Upsert
    suspend fun upsertBinding(binding: TrackerBindingEntity)

    @Query("DELETE FROM tracker_binding WHERE subjectId = :subjectId AND trackerId = :trackerId")
    suspend fun deleteBinding(subjectId: Int, trackerId: String)
}

@TestOnly
fun createMemoryTrackerBindingDao(): TrackerBindingDao {
    return object : TrackerBindingDao {
        private val store = MutableStateFlow(emptyList<TrackerBindingEntity>())

        private fun List<TrackerBindingEntity>.find(subjectId: Int, trackerId: String) =
            find { it.subjectId == subjectId && it.trackerId == trackerId }

        override fun bindingsForSubjectFlow(subjectId: Int): Flow<List<TrackerBindingEntity>> {
            return store.map { list -> list.filter { it.subjectId == subjectId } }
        }

        override suspend fun getBindingsForSubject(subjectId: Int): List<TrackerBindingEntity> {
            return store.value.filter { it.subjectId == subjectId }
        }

        override suspend fun getBinding(subjectId: Int, trackerId: String): TrackerBindingEntity? {
            return store.value.find(subjectId, trackerId)
        }

        override suspend fun upsertBinding(binding: TrackerBindingEntity) {
            val existing = store.value.find(binding.subjectId, binding.trackerId)
            store.value = if (existing == null) {
                store.value + binding
            } else {
                store.value.map { if (it === existing) binding else it }
            }
        }

        override suspend fun deleteBinding(subjectId: Int, trackerId: String) {
            store.value = store.value.filterNot { it.subjectId == subjectId && it.trackerId == trackerId }
        }
    }
}
