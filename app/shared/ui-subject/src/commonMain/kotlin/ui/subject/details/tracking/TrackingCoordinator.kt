/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import me.him188.ani.app.data.models.subject.SubjectInfo
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.utils.coroutines.IO_
import me.him188.ani.tracking.api.TrackingAccountState
import me.him188.ani.tracking.api.TrackingEdit
import me.him188.ani.tracking.api.TrackingMedia
import me.him188.ani.tracking.api.TrackingMediaId
import me.him188.ani.tracking.api.TrackingProviderException
import me.him188.ani.tracking.api.TrackingProviderId
import me.him188.ani.tracking.api.TrackingRegistry
import me.him188.ani.tracking.api.TrackingSnapshot
import me.him188.ani.tracking.api.TrackingSource
import me.him188.ani.tracking.api.TrackingSourceCapabilities
import me.him188.ani.tracking.api.TrackingStatusOption
import me.him188.ani.tracking.api.TrackingScoreOption

sealed interface TrackingLoad {
    data object Loading : TrackingLoad
    data class Ready(val snapshot: TrackingSnapshot?) : TrackingLoad
    data class RateLimited(val retryAfterSeconds: Int?) : TrackingLoad
    data object Failed : TrackingLoad
}

data class TrackingCardModel(
    val providerId: TrackingProviderId,
    val providerName: String,
    val capabilities: TrackingSourceCapabilities,
    val statusOptions: List<TrackingStatusOption>,
    val scoreOptions: List<TrackingScoreOption>,
    val account: TrackingAccountState,
    val load: TrackingLoad,
) {
    val isTracked: Boolean get() = (load as? TrackingLoad.Ready)?.snapshot?.entry != null
    val isConnected: Boolean get() = account is TrackingAccountState.LoggedIn || account is TrackingAccountState.Refreshing
    val shouldShowInTrackingSheet: Boolean get() = isConnected || load is TrackingLoad.RateLimited
}

internal fun retryAfterSeconds(millis: Long): Int {
    val positiveMillis = millis.coerceAtLeast(0)
    val seconds = positiveMillis / 1_000 + if (positiveMillis % 1_000 == 0L) 0 else 1
    return seconds.coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

class TrackingCoordinator(
    private val registry: TrackingRegistry,
    private val subjectCollectionRepository: SubjectCollectionRepository,
) {
    private val refreshes = MutableStateFlow(0)

    /** Subject info of [subjectId] from the collection cache; re-emits when the collection changes. */
    fun subjectInfo(subjectId: Int): Flow<SubjectInfo> =
        subjectCollectionRepository.subjectCollectionFlow(subjectId).map { it.subjectInfo }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(subjectId: Int): Flow<List<TrackingCardModel>> {
        val cards = registry.sources.map { source ->
            combine(
                source.connection,
                source.presentation,
                refreshes.flatMapLatest { generation ->
                    val snapshots = source.observe(subjectId)
                        .flowOn(Dispatchers.IO_)
                        .map<TrackingSnapshot?, TrackingLoad> { TrackingLoad.Ready(it) }
                        .catch { failure ->
                            if (failure is CancellationException) throw failure
                            if (failure is TrackingProviderException.RateLimited) {
                                emit(TrackingLoad.RateLimited(failure.retryAfterMillis?.let(::retryAfterSeconds)))
                            } else {
                                emit(TrackingLoad.Failed)
                            }
                        }
                    if (generation == 0) snapshots.onStart { emit(TrackingLoad.Loading) } else snapshots
                },
            ) { account, presentation, load -> TrackingCardModel(
                source.info.id, presentation.name, source.capabilities,
                source.statusOptions, source.scoreOptions, account, load,
            ) }
        }
        return if (cards.isEmpty()) flowOf(emptyList()) else combine(cards) { it.toList() }
    }

    suspend fun search(providerId: TrackingProviderId, query: String): List<TrackingMedia> =
        source(providerId).search(query)

    // Writes run to completion even if the screen leaves mid-edit; sources publish the result to their observers.
    suspend fun bind(subjectId: Int, providerId: TrackingProviderId, mediaId: TrackingMediaId) {
        withContext(NonCancellable) { source(providerId).bind(subjectId, mediaId) }
    }

    suspend fun edit(subjectId: Int, providerId: TrackingProviderId, edit: TrackingEdit) {
        withContext(NonCancellable) { source(providerId).edit(subjectId, edit) }
    }

    suspend fun unlink(subjectId: Int, providerId: TrackingProviderId) {
        withContext(NonCancellable) { source(providerId).unlink(subjectId) }
    }

    /** Manual refresh only: drops every source's cached read of [subjectId] and restarts the observers. */
    fun refresh(subjectId: Int) {
        registry.sources.forEach { it.invalidate(subjectId) }
        refreshes.value += 1
    }

    private fun source(id: TrackingProviderId): TrackingSource =
        registry.sources.first { it.info.id == id }
}
