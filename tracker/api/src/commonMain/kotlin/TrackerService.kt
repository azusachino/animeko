/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.tracker.api

import kotlinx.coroutines.flow.Flow
import me.him188.ani.datasources.api.topic.UnifiedCollectionType

/**
 * 第三方追番进度同步目标的统一接口 (AniList, 以及未来可能的 MyAnimeList / Kitsu).
 *
 * 一个 [TrackerService] 只负责与远程服务通信和账号状态; 与本地 Bangumi 条目/剧集的绑定关系由
 * `TrackerBindingEntity` (app-data) 持有, 由调用方在 [syncProgress]/[syncCollectionStatus] 时传入.
 */
interface TrackerService {
    /**
     * 稳定 ID, 例如 `"anilist"`. 与 [me.him188.ani.app.tracker.api.TrackerBinding.trackerId]
     * 及本地持久化表的 `trackerId` 列一致, 不可变更.
     */
    val id: String

    val displayName: String

    fun accountFlow(): Flow<TrackerAccount?>

    suspend fun isAuthorized(): Boolean

    suspend fun logout()

    suspend fun search(keyword: String): List<TrackerMediaSummary>

    suspend fun fetchEntry(remoteMediaId: Int): TrackerEntry?

    suspend fun syncProgress(binding: TrackerBinding, episodeNumber: Float): Result<Unit>

    suspend fun syncCollectionStatus(
        binding: TrackerBinding,
        status: UnifiedCollectionType,
        score: Float? = null,
    ): Result<Unit>
}

data class TrackerAccount(
    val trackerId: String,
    val userId: Long,
    val username: String,
    val avatarUrl: String?,
)

data class TrackerMediaSummary(
    val remoteMediaId: Int,
    val title: String,
    val coverUrl: String?,
    val totalEpisodes: Int?,
)

data class TrackerEntry(
    val remoteMediaId: Int,
    val title: String,
    val totalEpisodes: Int?,
    val progress: Float?,
    val status: String?,
    val score: Float?,
)

/**
 * 一次 Bangumi 条目与远程 tracker 条目的绑定关系. 只读快照, 由调用方从本地 DAO 加载后传入.
 */
data class TrackerBinding(
    val subjectId: Int,
    val trackerId: String,
    val remoteMediaId: Int,
    val episodeOffset: Int,
    val lastTrackedEpisode: Float,
)
