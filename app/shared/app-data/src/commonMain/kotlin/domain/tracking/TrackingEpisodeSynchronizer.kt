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
import me.him188.ani.tracking.api.TrackingRegistry

/** Dispatches watched episodes to every registered tracking source. */
class TrackingEpisodeSynchronizer(private val registry: TrackingRegistry) {
    suspend fun episodeWatched(subjectId: Int, episodeId: Int) {
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
        failure?.let { throw it }
    }
}
