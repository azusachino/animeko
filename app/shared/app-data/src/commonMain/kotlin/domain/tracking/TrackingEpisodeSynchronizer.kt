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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.him188.ani.app.data.tracking.TrackingBindingStore
import me.him188.ani.tracking.api.TrackingRegistry
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.thisLogger
import me.him188.ani.utils.logging.warn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Dispatches watched episodes to every registered tracking source.
 *
 * Episodes that fail to reach a source are kept in [pending] (`"$subjectId:$episodeId"` -> `"1"`) and re-dispatched
 * by [retryPending] and before each new [episodeWatched]. When [scope] is set, a failure also starts a backoff cycle
 * that calls [retryPending] after each of [retryDelays] and then stops. Sources are cumulative-max, so re-dispatching
 * is safe.
 */
class TrackingEpisodeSynchronizer(
    private val registry: TrackingRegistry,
    private val pending: TrackingBindingStore? = null,
    private val scope: CoroutineScope? = null,
    private val retryDelays: List<Duration> = listOf(5.minutes, 10.minutes, 20.minutes),
) {
    private val lock = Mutex()
    private val logger = thisLogger()

    /** The running backoff cycle, if any. Guarded by [lock]. */
    private var retryJob: Job? = null

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
        store.entries().keys.forEach { key ->
            val parts = key.split(':')
            val subjectId = parts.getOrNull(0)?.toIntOrNull()
            val episodeId = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || subjectId == null || episodeId == null) {
                store.remove(key)
                return@forEach
            }
            try {
                dispatch(subjectId, episodeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.warn(error) { "Pending tracking sync for subject $subjectId episode $episodeId still failing" }
            }
        }
    }

    /** Records the episode as pending on failure and clears its pending entry on full success. */
    private suspend fun dispatch(subjectId: Int, episodeId: Int) {
        var failure: Exception? = null
        logger.info { "[tracking-sync] dispatch subjectId=$subjectId episodeId=$episodeId sources=${registry.sources.size}" }
        registry.sources.forEach { source ->
            val name = "${source.info.id.value} (${source.info.displayName})"
            try {
                source.episodeWatched(subjectId, episodeId)
                logger.info { "[tracking-sync] source $name succeeded" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                logger.info { "[tracking-sync] source $name failed: ${error::class.simpleName}" }
                failure = error
            }
        }
        val key = "$subjectId:$episodeId"
        if (failure == null) {
            pending?.remove(key)
        } else {
            pending?.put(key, "1")
            scheduleRetry()
        }
        failure?.let { throw it }
    }

    /** Starts a backoff cycle unless one is running. Must be called under [lock]. */
    private fun scheduleRetry() {
        if (scope == null || pending == null || retryJob != null || retryDelays.isEmpty()) return
        retryJob = scope.launch { runRetryCycle() }
    }

    /**
     * Calls [retryPending] after each delay in [retryDelays], stopping early once the store is empty.
     * [retryJob] is cleared under [lock] in the same step as the final drain, so a failure that lands right after
     * the cycle gives up starts a fresh cycle instead of being silently skipped.
     */
    private suspend fun runRetryCycle() {
        for ((index, backoff) in retryDelays.withIndex()) {
            delay(backoff)
            val finished = lock.withLock {
                val done = try {
                    drainPending()
                    !hasPending() || index == retryDelays.lastIndex
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger.warn(error) { "Tracking retry cycle stopped" }
                    true
                }
                if (done) retryJob = null
                done
            }
            if (finished) return
        }
    }

    private fun hasPending(): Boolean = pending?.entries()?.isNotEmpty() == true
}
