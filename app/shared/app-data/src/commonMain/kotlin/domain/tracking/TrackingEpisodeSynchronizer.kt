/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.tracking.TrackingBindingStore
import me.him188.ani.tracking.api.TrackingRegistry
import me.him188.ani.utils.logging.thisLogger
import me.him188.ani.utils.logging.warn

/**
 * Dispatches watched episodes to every registered tracking source.
 *
 * Episodes that fail to reach a source are kept in [pending] (subjectId -> latest episodeId) and re-dispatched
 * by [retryPending] and before each new [episodeWatched]. Sources are cumulative-max, so re-dispatching is safe.
 */
class TrackingEpisodeSynchronizer(
    private val registry: TrackingRegistry,
    private val pending: TrackingBindingStore? = null,
) {
    private val lock = Mutex()
    private val logger = thisLogger()

    suspend fun episodeWatched(subjectId: Int, episodeId: Int) {
        lock.withLock {
            drainPending()
            dispatch(subjectId, episodeId)
        }
    }

    /** Re-dispatches pending episodes. Never throws except for cancellation. */
    suspend fun retryPending() {
        lock.withLock { drainPending() }
    }

    private suspend fun drainPending() {
        val store = pending ?: return
        store.entries().forEach { (subjectKey, episodeKey) ->
            val subjectId = subjectKey.toIntOrNull()
            val episodeId = episodeKey.toIntOrNull()
            if (subjectId == null || episodeId == null) {
                store.remove(subjectKey)
                return@forEach
            }
            try {
                dispatch(subjectId, episodeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn(error) { "Pending tracking sync for subject $subjectId still failing" }
            }
        }
    }

    /** Records the episode as pending on failure and clears the subject's pending entry on full success. */
    private suspend fun dispatch(subjectId: Int, episodeId: Int) {
        var failure: Exception? = null
        registry.sources.forEach { source ->
            try {
                source.episodeWatched(subjectId, episodeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error
            }
        }
        if (failure == null) {
            pending?.remove(subjectId.toString())
        } else {
            pending?.put(subjectId.toString(), episodeId.toString())
        }
        failure?.let { throw it }
    }
}
