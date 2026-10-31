/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the GNU AGPLv3 license.
 */

package me.him188.ani.app.data.tracking

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.domain.tracking.TrackingEpisodeSynchronizer
import me.him188.ani.tracking.api.DefaultTrackingRegistry
import me.him188.ani.tracking.api.TrackingAccount
import me.him188.ani.tracking.api.TrackingAccountState
import me.him188.ani.tracking.api.TrackingBindingRecord
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
import me.him188.ani.tracking.api.TrackingSource
import me.him188.ani.tracking.api.TrackingStatus
import me.him188.ani.tracking.api.TrackingStatusOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class AniListTrackingSourceTest {
    private val accountA = TrackingAccount("account-a", "A")
    private val accountB = TrackingAccount("account-b", "B")

    @Test
    fun freshSnapshotIsReusedUntilTtlExpires() = runTest(UnconfinedTestDispatcher()) {
        val time = TestTimeSource()
        val provider = FakeProvider(TrackingAccountState.LoggedIn(accountA))
        val source = source(provider, bindingsOf("account-a:42" to "100"), time)

        openSubject(source)
        assertEquals(1, provider.refreshCalls)

        time += 59.seconds
        openSubject(source)
        assertEquals(1, provider.refreshCalls)

        time += 1.seconds
        openSubject(source)
        assertEquals(2, provider.refreshCalls)
    }

    @Test
    fun invalidateForcesARemoteReadInsideTtl() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedIn(accountA))
        val source = source(provider, bindingsOf("account-a:42" to "100"), TestTimeSource())

        openSubject(source)
        source.invalidate(42)
        openSubject(source)

        assertEquals(2, provider.refreshCalls)
    }

    @Test
    fun snapshotLoadedUnderAnotherAccountIsNotShown() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedIn(accountA))
        val source = source(provider, bindingsOf("account-a:42" to "100", "account-b:42" to "200"))

        openSubject(source).cancel()
        assertEquals("title-100", source.observe(42).first()?.media?.title)

        provider.state.value = TrackingAccountState.LoggedOut
        provider.state.value = TrackingAccountState.LoggedIn(accountB)
        // The eager test dispatcher may already have loaded B's snapshot; the invariant is that A's never shows.
        assertNotEquals("title-100", source.observe(42).first()?.media?.title)

        openSubject(source)
        assertEquals("title-200", source.observe(42).first()?.media?.title)
    }

    @Test
    fun observeWithoutStoredCredentialsMakesNoViewerRequest() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedOut, hasCredentials = false)
        val source = source(provider, bindingsOf())

        openSubject(source)

        assertNull(source.observe(42).first())
        assertEquals(0, provider.viewerRequests)
    }

    @Test
    fun observeRestoresStoredSessionFromLoggedOut() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedOut, hasCredentials = true)
        val source = source(provider, bindingsOf("account-a:42" to "100"))

        openSubject(source)

        assertEquals(1, provider.viewerRequests)
        assertEquals("title-100", source.observe(42).first()?.media?.title)
    }

    @Test
    fun episodeWatchedOnUnboundSubjectMakesNoProviderCall() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedOut, hasCredentials = true)
        val source = source(provider, bindingsOf("account-a:7" to "100"))

        source.episodeWatched(42, 1)

        assertEquals(0, provider.viewerRequests)
        assertEquals(0, provider.refreshCalls)
    }

    @Test
    fun episodeWatchedOnBoundSubjectWithoutSessionRethrowsUnauthorized() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedOut, hasCredentials = false)
        val source = source(provider, bindingsOf("account-a:42" to "100"))

        assertFailsWith<TrackingProviderException.Unauthorized> { source.episodeWatched(42, 1) }
        assertEquals(0, provider.refreshCalls)
    }

    @Test
    fun unauthorizedEpisodeWatchIsKeptPendingBySynchronizer() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedOut, hasCredentials = false)
        val pending = MapBindingStore()
        val source = source(provider, bindingsOf("account-a:42" to "100"))
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(source)), pending)

        assertFailsWith<TrackingProviderException.Unauthorized> { synchronizer.episodeWatched(42, 1) }
        assertEquals(mapOf("42:1" to "1"), pending.entries())
    }

    @Test
    fun restoredBindingsForOtherAccountsAreCountedAsUnconnected() = runTest(UnconfinedTestDispatcher()) {
        val provider = FakeProvider(TrackingAccountState.LoggedIn(accountA))
        val source = source(provider, bindingsOf())
        val records = listOf(
            TrackingBindingRecord("anilist", "account-a", 42, "100"),
            TrackingBindingRecord("anilist", "account-b", 43, "101"),
        )

        assertEquals(1, source.countUnconnectedBindings(records))

        provider.state.value = TrackingAccountState.LoggedOut
        assertEquals(2, source.countUnconnectedBindings(records))
    }

    private fun TestScope.openSubject(source: TrackingSource, subjectId: Int = 42): Job {
        val job = backgroundScope.launch { source.observe(subjectId).collect {} }
        advanceUntilIdle()
        return job
    }

    private fun source(provider: TrackingProvider, store: TrackingBindingStore, time: TestTimeSource = TestTimeSource()) =
        AniListTrackingSource(store, provider, lazy { error("episodes are not used by these tests") }, timeSource = time)

    private fun bindingsOf(vararg entries: Pair<String, String>) = MapBindingStore().apply { map.putAll(entries) }

    private class MapBindingStore : TrackingBindingStore {
        val map = mutableMapOf<String, String>()
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

    /** Mirrors the real provider: a refresh without stored credentials fails without a viewer request. */
    private class FakeProvider(initial: TrackingAccountState, private val hasCredentials: Boolean = true) : TrackingProvider {
        val state = MutableStateFlow(initial)
        var refreshCalls = 0
        var viewerRequests = 0
        private val account = TrackingAccount("account-a", "A")

        override val info = TrackingProviderInfo(TrackingProviderId("anilist"), "AniList", "https://anilist.co")
        override val capabilities = TrackingCapabilities()
        override val accountState: StateFlow<TrackingAccountState> = state
        override val statusOptions = listOf(TrackingStatusOption(TrackingStatus.CURRENT, "Watching"))
        override val scoreOptions = emptyList<TrackingScoreOption>()

        override suspend fun login(credentials: TrackingLoginCredentials): TrackingAccount = unused()

        override suspend fun refreshAccount(): TrackingAccount {
            if (!hasCredentials) {
                state.value = TrackingAccountState.LoggedOut
                throw TrackingProviderException.Unauthorized()
            }
            viewerRequests++
            state.value = TrackingAccountState.LoggedIn(account)
            return account
        }

        override suspend fun logout() = unused()
        override suspend fun search(query: String): List<TrackingMedia> = unused()
        override suspend fun prepareBinding(mediaId: TrackingMediaId): TrackingMediaWithEntry? = unused()
        override suspend fun bind(entry: TrackingListEntry): TrackingListEntry = unused()

        override suspend fun update(
            entry: TrackingListEntry,
            didWatchEpisode: Boolean,
            totalEpisodes: Int?,
        ): TrackingListEntry = unused()

        override suspend fun refresh(mediaId: TrackingMediaId): TrackingMediaWithEntry? {
            refreshCalls++
            return TrackingMediaWithEntry(
                TrackingMedia(mediaId, "title-${mediaId.value}", "https://anilist.co/anime/${mediaId.value}", null, 12),
                TrackingListEntry(mediaId, TrackingStatus.CURRENT, 1),
            )
        }

        override suspend fun delete(mediaId: TrackingMediaId) = unused()

        private fun unused(): Nothing = error("not used by this test")
    }
}
