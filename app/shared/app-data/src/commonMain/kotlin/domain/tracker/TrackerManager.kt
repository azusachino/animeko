/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracker

import me.him188.ani.app.data.persistent.database.dao.TrackerBindingDao
import me.him188.ani.app.data.persistent.database.dao.TrackerBindingEntity
import me.him188.ani.app.tracker.api.TrackerBinding
import me.him188.ani.app.tracker.api.TrackerService
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.logger
import me.him188.ani.utils.logging.warn
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.coroutines.cancellation.CancellationException

/**
 * 把本地 "剧集标记为已看" / "条目收藏状态变更" 事件, 分发给条目已绑定的所有第三方 tracker.
 *
 * 每个 tracker 的失败都被捕获并记录, 不会互相影响, 也不会传给调用方 -- 本地写入已经完成,
 * tracker 同步失败不应该让调用方看起来像本地操作失败了.
 */
class TrackerManager(
    private val trackers: List<TrackerService>,
    private val bindingDao: TrackerBindingDao,
) {
    /**
     * @param episodeSort 已看到的最新一集集数 (未加 offset). 调用方负责去重/取最大值 --
     * 一次批量"全部标为已看"应该只调用一次, 传入批次里的最大集数, 而不是每集调用一次.
     */
    suspend fun onEpisodeMarkedWatched(subjectId: Int, episodeSort: Float) {
        forEachActiveBinding(subjectId) { tracker, binding ->
            val targetEpisode = episodeSort + binding.episodeOffset
            if (targetEpisode <= binding.lastTrackedEpisode) return@forEachActiveBinding

            tracker.syncProgress(binding.toTrackerBinding(), targetEpisode)
                .onSuccess {
                    bindingDao.upsertBinding(
                        binding.copy(lastTrackedEpisode = targetEpisode, updatedAtMillis = currentTimeMillis()),
                    )
                }
                .onFailure { e ->
                    logger.warn(e) { "Failed to sync progress to tracker ${tracker.id} for subject $subjectId" }
                }
        }
    }

    suspend fun onSubjectCollectionChanged(subjectId: Int, status: UnifiedCollectionType, score: Float? = null) {
        forEachActiveBinding(subjectId) { tracker, binding ->
            tracker.syncCollectionStatus(binding.toTrackerBinding(), status, score)
                .onFailure { e ->
                    logger.warn(e) { "Failed to sync collection status to tracker ${tracker.id} for subject $subjectId" }
                }
        }
    }

    private suspend inline fun forEachActiveBinding(
        subjectId: Int,
        action: suspend (tracker: TrackerService, binding: TrackerBindingEntity) -> Unit,
    ) {
        val bindings = bindingDao.getBindingsForSubject(subjectId)
        for (binding in bindings) {
            val tracker = trackers.firstOrNull { it.id == binding.trackerId } ?: continue
            try {
                if (!tracker.isAuthorized()) continue
                action(tracker, binding)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Unexpected error syncing tracker ${tracker.id} for subject $subjectId" }
            }
        }
    }

    private companion object {
        val logger = logger<TrackerManager>()
    }
}

private fun TrackerBindingEntity.toTrackerBinding() = TrackerBinding(
    subjectId = subjectId,
    trackerId = trackerId,
    remoteMediaId = remoteMediaId,
    episodeOffset = episodeOffset,
    lastTrackedEpisode = lastTrackedEpisode,
)
