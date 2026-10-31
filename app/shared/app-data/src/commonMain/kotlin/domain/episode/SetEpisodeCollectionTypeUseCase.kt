/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.episode

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.domain.foundation.LoadError
import me.him188.ani.app.domain.usecase.UseCase
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.logging.info
import me.him188.ani.utils.logging.logger
import org.koin.core.Koin

data class SetEpisodeCollectionTypeRequest(
    val subjectId: Int,
    val episodeId: Int,
    val collectionType: UnifiedCollectionType
)

fun interface SetEpisodeCollectionTypeUseCase : UseCase {
    suspend operator fun invoke(
        subjectId: Int,
        episodeId: Int,
        collectionType: UnifiedCollectionType,
    )

    suspend fun invokeSafe(request: SetEpisodeCollectionTypeRequest): LoadError? {
        return LoadError.runAndWrapOrThrowCancellation {
            invoke(request.subjectId, request.episodeId, request.collectionType)
        }
    }
}

fun interface EpisodeTrackingSync {
    fun onEpisodeWatched(subjectId: Int, episodeId: Int)
}

class SetEpisodeCollectionTypeUseCaseImpl(
    koin: Koin,
) : SetEpisodeCollectionTypeUseCase {
    private val episodeCollectionRepository: EpisodeCollectionRepository by koin.inject()
    private val logger = logger<SetEpisodeCollectionTypeUseCaseImpl>()

    override suspend fun invoke(subjectId: Int, episodeId: Int, collectionType: UnifiedCollectionType) {
        withContext(Dispatchers.Default) {
            logger.info { "[tracking-sync] set collection subjectId=$subjectId episodeId=$episodeId type=$collectionType" }
            // The repository persists locally, enqueues server sync, then notifies tracking sync.
            episodeCollectionRepository.setEpisodeCollectionType(subjectId, episodeId, collectionType)
        }
    }
}
