/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.him188.ani.app.data.models.subject.RatingInfo
import me.him188.ani.app.data.models.subject.SelfRatingInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionStats
import me.him188.ani.app.data.network.AnimeScheduleService
import me.him188.ani.app.data.network.EpisodeServiceImpl
import me.him188.ani.app.data.persistent.database.createTestAniDatabase
import me.him188.ani.app.data.persistent.database.dao.EpisodeCollectionEntity
import me.him188.ani.app.data.persistent.database.dao.SubjectCollectionEntity
import me.him188.ani.app.data.repository.episode.AnimeScheduleRepository
import me.him188.ani.app.data.repository.episode.EpisodeCollectionRepository
import me.him188.ani.app.data.repository.subject.GetEpisodeTypeFiltersUseCase
import me.him188.ani.app.domain.tracking.TrackingEpisodeSynchronizer
import me.him188.ani.client.apis.ScheduleAniApi
import me.him188.ani.client.apis.SubjectsAniApi
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.PackedDate
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.tracking.api.TrackingAccount
import me.him188.ani.tracking.api.DefaultTrackingRegistry
import me.him188.ani.tracking.api.TrackingAccountState
import me.him188.ani.tracking.api.TrackingCapabilities
import me.him188.ani.tracking.api.TrackingListEntry
import me.him188.ani.tracking.api.TrackingLoginCredentials
import me.him188.ani.tracking.api.TrackingMedia
import me.him188.ani.tracking.api.TrackingMediaId
import me.him188.ani.tracking.api.TrackingMediaWithEntry
import me.him188.ani.tracking.api.TrackingProvider
import me.him188.ani.tracking.api.TrackingProviderException
import me.him188.ani.tracking.api.TrackingProviderId
import me.him188.ani.tracking.api.TrackingProviderInfo
import me.him188.ani.tracking.api.TrackingScoreOption
import me.him188.ani.tracking.api.TrackingStatus
import me.him188.ani.tracking.api.TrackingStatusOption
import me.him188.ani.utils.ktor.ApiInvoker
import me.him188.ani.utils.platform.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Covers the episode lookup path of [AniListTrackingSource], which needs a real [EpisodeCollectionRepository]
 * backed by an in-memory database.
 */
class AniListTrackingSourceEpisodeTest {
    private object UnusedSubjectsApi : ApiInvoker<SubjectsAniApi> {
        override suspend fun <R> invoke(action: suspend SubjectsAniApi.() -> R): R = error("ApiInvoker not expected")
    }

    private object UnusedScheduleApi : ApiInvoker<ScheduleAniApi> {
        override suspend fun <R> invoke(action: suspend ScheduleAniApi.() -> R): R = error("ApiInvoker not expected")
    }

    @Test
    fun episodeWatchedThrowsRemoteWhenRefreshReturnsNull() = runBlocking {
        val database = createTestAniDatabase()
        try {
            database.subjectCollection().upsert(listOf(subject(42)))
            database.episodeCollection().upsert(listOf(episode(42, 1)))
            val repository = EpisodeCollectionRepository(
                subjectDao = database.subjectCollection(),
                episodeCollectionDao = database.episodeCollection(),
                pendingOpDao = database.episodeCollectionPendingOpDao(),
                episodeService = EpisodeServiceImpl(UnusedSubjectsApi),
                animeScheduleRepository = AnimeScheduleRepository(AnimeScheduleService(UnusedScheduleApi)),
                subjectCollectionRepository = lazy { error("SubjectCollectionRepository not expected") },
                getEpisodeTypeFiltersUseCase = GetEpisodeTypeFiltersUseCase { flowOf(EpisodeType.entries) },
            )
            val provider = TitleProvider()
            val source = AniListTrackingSource(
                bindings = MapBindingStore(mutableMapOf("account-a:42" to "100")),
                provider = provider,
                episodes = lazy { repository },
            )

            assertFailsWith<TrackingProviderException.Remote> { source.episodeWatched(42, 1) }
            assertEquals(1, provider.refreshCalls)
        } finally {
            database.close()
        }
    }

    @Test
    fun missingTitleStaysPendingUntilRetryFindsTitle() = runBlocking {
        val database = createTestAniDatabase()
        try {
            database.subjectCollection().upsert(listOf(subject(42)))
            database.episodeCollection().upsert(listOf(episode(42, 1)))
            val repository = EpisodeCollectionRepository(
                subjectDao = database.subjectCollection(),
                episodeCollectionDao = database.episodeCollection(),
                pendingOpDao = database.episodeCollectionPendingOpDao(),
                episodeService = EpisodeServiceImpl(UnusedSubjectsApi),
                animeScheduleRepository = AnimeScheduleRepository(AnimeScheduleService(UnusedScheduleApi)),
                subjectCollectionRepository = lazy { error("SubjectCollectionRepository not expected") },
                getEpisodeTypeFiltersUseCase = GetEpisodeTypeFiltersUseCase { flowOf(EpisodeType.entries) },
            )
            val provider = TitleProvider()
            val pending = MapBindingStore(mutableMapOf())
            val source = AniListTrackingSource(
                bindings = MapBindingStore(mutableMapOf("account-a:42" to "100")),
                provider = provider,
                episodes = lazy { repository },
            )
            val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(source)), pending)

            assertFailsWith<TrackingProviderException.Remote> { synchronizer.episodeWatched(42, 1) }
            assertEquals(mapOf("42:1" to "1"), pending.entries())

            provider.titleAvailable = true
            synchronizer.retryPending()

            assertEquals(emptyMap(), pending.entries())
            assertEquals(1, provider.updatedProgress)
        } finally {
            database.close()
        }
    }

    private fun subject(subjectId: Int) = SubjectCollectionEntity(
        subjectId = subjectId,
        name = "subject-$subjectId",
        nameCn = "条目 $subjectId",
        summary = "",
        nsfw = false,
        imageLarge = "",
        totalEpisodes = 12,
        airDate = PackedDate.Invalid,
        aliases = emptyList(),
        tags = emptyList(),
        collectionStats = SubjectCollectionStats.Zero,
        ratingInfo = RatingInfo.Empty,
        completeDate = PackedDate.Invalid,
        selfRatingInfo = SelfRatingInfo(score = 0, comment = null, tags = emptyList(), isPrivate = false),
        collectionType = UnifiedCollectionType.DOING,
        recurrence = null,
        lastUpdated = subjectId.toLong(),
        lastFetched = 0,
        cachedStaffUpdated = 0,
        cachedCharactersUpdated = 0,
    )

    private fun episode(subjectId: Int, episodeId: Int) = EpisodeCollectionEntity(
        subjectId = subjectId,
        episodeId = episodeId,
        episodeType = EpisodeType.MainStory,
        name = "ep",
        nameCn = "第 $episodeId 集",
        airDate = PackedDate.Invalid,
        comment = 0,
        desc = "",
        sort = EpisodeSort(episodeId),
        sortNumber = episodeId.toFloat(),
        ep = EpisodeSort(episodeId),
        selfCollectionType = UnifiedCollectionType.WISH,
        lastFetched = currentTimeMillis(),
    )

    private class MapBindingStore(private val map: MutableMap<String, String>) : TrackingBindingStore {
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }

        override fun remove(key: String) {
            map.remove(key)
        }

        override fun entries(): Map<String, String> = map.toMap()
        override fun putAll(entries: Map<String, String>) {
            map.putAll(entries)
        }
    }

    /** A signed-in provider whose title refresh finds nothing until [titleAvailable] is set. */
    private class TitleProvider : TrackingProvider {
        val state = MutableStateFlow<TrackingAccountState>(TrackingAccountState.LoggedIn(TrackingAccount("account-a", "A")))
        var refreshCalls = 0
        var titleAvailable = false
        var updatedProgress: Int? = null

        override val info = TrackingProviderInfo(TrackingProviderId("anilist"), "AniList", "https://anilist.co")
        override val capabilities = TrackingCapabilities()
        override val accountState: StateFlow<TrackingAccountState> = state
        override val statusOptions = listOf(TrackingStatusOption(TrackingStatus.CURRENT, "Watching"))
        override val scoreOptions = emptyList<TrackingScoreOption>()

        override suspend fun login(credentials: TrackingLoginCredentials): TrackingAccount = unused()
        override suspend fun refreshAccount(): TrackingAccount = unused()
        override suspend fun logout() = unused()
        override suspend fun search(query: String): List<TrackingMedia> = unused()
        override suspend fun prepareBinding(mediaId: TrackingMediaId): TrackingMediaWithEntry? = unused()
        override suspend fun bind(entry: TrackingListEntry): TrackingListEntry = unused()

        override suspend fun update(
            entry: TrackingListEntry,
            didWatchEpisode: Boolean,
            totalEpisodes: Int?,
        ): TrackingListEntry {
            updatedProgress = entry.progress
            return entry
        }

        override suspend fun refresh(mediaId: TrackingMediaId): TrackingMediaWithEntry? {
            refreshCalls++
            if (!titleAvailable) return null
            return TrackingMediaWithEntry(
                TrackingMedia(mediaId, "title-${mediaId.value}", "https://anilist.co/anime/${mediaId.value}", null, 12),
                TrackingListEntry(mediaId, TrackingStatus.CURRENT, progress = 0),
            )
        }

        override suspend fun delete(mediaId: TrackingMediaId) = unused()

        private fun unused(): Nothing = error("not used by this test")
    }
}
