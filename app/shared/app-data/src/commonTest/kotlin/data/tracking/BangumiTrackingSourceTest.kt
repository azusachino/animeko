/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.data.tracking

import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.models.bangumi.BangumiSyncState
import me.him188.ani.app.data.models.episode.EpisodeCollectionInfo
import me.him188.ani.app.data.models.episode.EpisodeInfo
import me.him188.ani.app.data.models.subject.SubjectCollectionCounts
import me.him188.ani.app.data.models.subject.SubjectCollectionInfo
import me.him188.ani.app.data.models.subject.createTestSubjectCollection
import me.him188.ani.app.data.persistent.MemoryDataStore
import me.him188.ani.app.data.repository.subject.CollectionsFilterQuery
import me.him188.ani.app.data.repository.subject.OfflineSubjectDisplayInfo
import me.him188.ani.app.data.repository.subject.SubjectCollectionRepository
import me.him188.ani.app.data.repository.user.TokenRepository
import me.him188.ani.app.data.repository.user.TokenSave
import me.him188.ani.app.data.repository.user.UserRepository
import me.him188.ani.app.domain.session.InvalidSessionReason
import me.him188.ani.app.domain.session.SessionEvent
import me.him188.ani.app.domain.session.SessionManager
import me.him188.ani.app.domain.session.SessionState
import me.him188.ani.app.domain.session.SessionStateProvider
import me.him188.ani.datasources.api.EpisodeSort
import me.him188.ani.datasources.api.EpisodeType
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import me.him188.ani.utils.ktor.ApiInvoker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BangumiTrackingSourceTest {
    private val subjectId = 42

    @Test
    fun wishBecomesDoingOnFirstWatchedEpisode() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeSubjectCollectionRepository(collection(UnifiedCollectionType.WISH, watched = 1))

        bangumiSource(repo).episodeWatched(subjectId, episodeId(1))

        assertEquals(listOf<UnifiedCollectionType?>(UnifiedCollectionType.DOING), repo.writes)
    }

    @Test
    fun doingBecomesDoneWhenLastMainStoryEpisodeIsWatched() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeSubjectCollectionRepository(collection(UnifiedCollectionType.DOING, watched = TOTAL))

        bangumiSource(repo).episodeWatched(subjectId, episodeId(TOTAL))

        assertEquals(listOf<UnifiedCollectionType?>(UnifiedCollectionType.DONE), repo.writes)
    }

    @Test
    fun doingStaysDoingMidSeries() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeSubjectCollectionRepository(collection(UnifiedCollectionType.DOING, watched = 2))

        bangumiSource(repo).episodeWatched(subjectId, episodeId(2))

        assertEquals(emptyList(), repo.writes)
    }

    @Test
    fun otherCollectionTypesAreUntouched() = runTest(UnconfinedTestDispatcher()) {
        for (type in listOf(
            UnifiedCollectionType.DROPPED,
            UnifiedCollectionType.ON_HOLD,
            UnifiedCollectionType.NOT_COLLECTED,
            UnifiedCollectionType.DONE,
        )) {
            val repo = FakeSubjectCollectionRepository(collection(type, watched = TOTAL))

            bangumiSource(repo).episodeWatched(subjectId, episodeId(TOTAL))

            assertEquals(emptyList(), repo.writes, "collection type $type must not change")
        }
    }

    @Test
    fun nonMainStoryEpisodeIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val special = episode(id = 900, number = 1, watched = true, type = EpisodeType.SP)
        val repo = FakeSubjectCollectionRepository(
            createTestSubjectCollection(
                id = subjectId,
                episodes = episodes(watched = TOTAL) + special,
                collectionType = UnifiedCollectionType.DOING,
            ),
        )

        bangumiSource(repo).episodeWatched(subjectId, special.episodeId)

        assertEquals(emptyList(), repo.writes)
    }

    @Test
    fun notConnectedMakesNoRepositoryCalls() = runTest(UnconfinedTestDispatcher()) {
        val sessions = listOf(
            SessionState.Invalid(InvalidSessionReason.NO_TOKEN),
            SessionState.Valid(bangumiConnected = false),
        )
        for (session in sessions) {
            val repo = FakeSubjectCollectionRepository(collection(UnifiedCollectionType.WISH, watched = 1))

            bangumiSource(repo, session).episodeWatched(subjectId, episodeId(1))

            assertEquals(0, repo.reads, "session $session must not read collection state")
            assertEquals(emptyList(), repo.writes, "session $session must not write collection state")
        }
    }

    @Test
    fun rerunningAfterTransitionIsNoOp() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeSubjectCollectionRepository(collection(UnifiedCollectionType.WISH, watched = TOTAL))
        val source = bangumiSource(repo)

        source.episodeWatched(subjectId, episodeId(TOTAL))
        assertEquals(listOf<UnifiedCollectionType?>(UnifiedCollectionType.DOING, UnifiedCollectionType.DONE), repo.writes)

        source.episodeWatched(subjectId, episodeId(TOTAL))
        assertEquals(listOf<UnifiedCollectionType?>(UnifiedCollectionType.DOING, UnifiedCollectionType.DONE), repo.writes)
    }

    @Test
    fun writeFailureIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val repo = FakeSubjectCollectionRepository(collection(UnifiedCollectionType.WISH, watched = 1))
        repo.failWrites = true

        assertFailsWith<IllegalStateException> {
            bangumiSource(repo).episodeWatched(subjectId, episodeId(1))
        }
    }

    private companion object {
        const val TOTAL = 12
    }

    private fun episodeId(number: Int) = 100 + number

    private fun episode(
        id: Int,
        number: Int,
        watched: Boolean,
        type: EpisodeType? = EpisodeType.MainStory,
    ) = EpisodeCollectionInfo(
        episodeInfo = EpisodeInfo(
            episodeId = id,
            type = type,
            ep = EpisodeSort(number),
            sort = EpisodeSort(number),
        ),
        collectionType = if (watched) UnifiedCollectionType.DONE else UnifiedCollectionType.NOT_COLLECTED,
    )

    private fun episodes(watched: Int): List<EpisodeCollectionInfo> =
        (1..TOTAL).map { number -> episode(episodeId(number), number, watched = number <= watched) }

    private fun collection(type: UnifiedCollectionType, watched: Int): SubjectCollectionInfo =
        createTestSubjectCollection(id = subjectId, episodes = episodes(watched), collectionType = type)

    private fun TestScope.bangumiSource(
        repo: FakeSubjectCollectionRepository,
        session: SessionState = SessionState.Valid(bangumiConnected = true),
    ): BangumiTrackingSource {
        val sessionStateProvider = FakeSessionStateProvider(session)
        return BangumiTrackingSource(
            sessionStateProvider = sessionStateProvider,
            userRepository = UserRepository(
                dataStore = MemoryDataStore(null),
                sessionStateProvider = sessionStateProvider,
                userApi = unusedApi(),
                authApi = unusedApi(),
                profileApi = unusedApi(),
                bangumiApi = unusedApi(),
                oauthApi = unusedApi(),
                sessionManager = SessionManager(
                    TokenRepository(MemoryDataStore(TokenSave.Initial)),
                    backgroundScope,
                    refreshSession = { error("refresh should not be called") },
                ),
            ),
            subjectCollectionRepository = lazy { repo },
            setEpisodeCollectionType = { _, _, _ -> error("episode writes are not used") },
            episodeCollectionRepository = lazy { error("episodes are not used") },
        )
    }

    private fun <Api> unusedApi(): ApiInvoker<Api> = object : ApiInvoker<Api> {
        override suspend fun <R> invoke(action: suspend Api.() -> R): R = error("network is not used")
    }

    private class FakeSessionStateProvider(initial: SessionState) : SessionStateProvider {
        override val stateFlow = MutableStateFlow(initial)
        override val eventFlow: Flow<SessionEvent> = emptyFlow()
    }

    /** Stores one subject's collection; writes update the stored type, like the real repository does. */
    private class FakeSubjectCollectionRepository(initial: SubjectCollectionInfo) : SubjectCollectionRepository() {
        private val state = MutableStateFlow(initial)
        val writes = mutableListOf<UnifiedCollectionType?>()
        var reads = 0
        var failWrites = false

        override fun subjectCollectionFlow(subjectId: Int): Flow<SubjectCollectionInfo> {
            reads++
            return state
        }

        override suspend fun setSubjectCollectionTypeOrDelete(subjectId: Int, type: UnifiedCollectionType?) {
            if (failWrites) throw IllegalStateException("write failed")
            writes += type
            state.value = state.value.copy(collectionType = type ?: UnifiedCollectionType.NOT_COLLECTED)
        }

        override fun subjectCollectionCountsFlow(): Flow<SubjectCollectionCounts?> = unsupported()
        override fun subjectCollectionsPager(
            query: CollectionsFilterQuery,
            pagingConfig: PagingConfig,
        ): Flow<PagingData<SubjectCollectionInfo>> = unsupported()

        override fun cachedValidSubjectIds(): Flow<List<Int>> = unsupported()
        override suspend fun updateRecentlyUpdatedSubjectCollections(
            limit: Int,
            type: UnifiedCollectionType?,
            offset: Int,
        ): Unit = unsupported()

        override fun mostRecentlyUpdatedSubjectCollectionsFlow(
            limit: Int,
            types: List<UnifiedCollectionType>?,
        ): Flow<List<SubjectCollectionInfo>> = unsupported()

        override suspend fun updateRating(
            subjectId: Int,
            score: Int?,
            comment: String?,
            tags: List<String>?,
            isPrivate: Boolean?,
        ): Unit = unsupported()

        override fun getSubjectCollectionTypeOffline(subjectId: Int): Flow<UnifiedCollectionType?> = unsupported()
        override fun getSubjectDisplayInfoOffline(subjectId: Int): Flow<OfflineSubjectDisplayInfo?> = unsupported()
        override suspend fun getSubjectNamesCnByCollectionType(types: List<UnifiedCollectionType>): Flow<List<String>> =
            unsupported()

        override suspend fun performBangumiFullSync(): Unit = unsupported()
        override suspend fun getBangumiFullSyncState(): BangumiSyncState? = unsupported()
        override suspend fun invalidateCache(subjectIds: List<Int>): Unit = unsupported()
        override suspend fun invalidateAllCaches(): Unit = unsupported()

        private fun <T> unsupported(): T = throw UnsupportedOperationException("not used by BangumiTrackingSource.episodeWatched")
    }
}
