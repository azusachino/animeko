/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.tracking.TrackingBindingStore
import me.him188.ani.tracking.api.DefaultTrackingRegistry
import me.him188.ani.tracking.api.TrackingAccountState
import me.him188.ani.tracking.api.TrackingEdit
import me.him188.ani.tracking.api.TrackingMediaId
import me.him188.ani.tracking.api.TrackingProviderId
import me.him188.ani.tracking.api.TrackingProviderInfo
import me.him188.ani.tracking.api.TrackingSnapshot
import me.him188.ani.tracking.api.TrackingSource
import me.him188.ani.tracking.api.TrackingSourceCapabilities
import me.him188.ani.tracking.api.TrackingScoreOption
import me.him188.ani.tracking.api.TrackingStatusOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TrackingEpisodeSynchronizerTest {
    @Test
    fun watchedEventContinuesAfterOneSourceFails() = runTest {
        val first = FakeSource("first", fail = true)
        val second = FakeSource("second")
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(first, second)))

        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }
        assertEquals(1, second.calls)
    }

    @Test
    fun failedEpisodeIsKeptPendingUntilLaterSuccess() = runTest {
        val source = FakeSource("only", fail = true)
        val store = MemoryBindingStore()
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(source)), store)

        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }
        assertEquals(mapOf("42:7" to "1"), store.entries())

        source.fail = false
        synchronizer.episodeWatched(42, 8)
        assertEquals(emptyMap(), store.entries())
        assertEquals(listOf(7, 7, 8), source.episodes)
    }

    @Test
    fun failuresForLaterAndEarlierEpisodesAreBothKeptAndDelivered() = runTest {
        val source = FakeSource("only", fail = true)
        val store = MemoryBindingStore()
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(source)), store)
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 5) }
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 4) }
        assertEquals(mapOf("42:5" to "1", "42:4" to "1"), store.entries())

        source.fail = false
        source.episodes.clear()
        synchronizer.retryPending()
        assertEquals(emptyMap(), store.entries())
        assertEquals(listOf(5, 4), source.episodes)
    }

    @Test
    fun retryPendingKeepsEntriesOnFailureAndDrainsThemOnSuccess() = runTest {
        val source = FakeSource("only", fail = true)
        val store = MemoryBindingStore()
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(source)), store)
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }

        synchronizer.retryPending()
        assertEquals(mapOf("42:7" to "1"), store.entries())

        source.fail = false
        synchronizer.retryPending()
        assertEquals(emptyMap(), store.entries())
        assertEquals(listOf(7, 7, 7), source.episodes)
    }

    @Test
    fun retryPendingDropsMalformedEntriesAndDispatchesValidOnes() = runTest {
        val source = FakeSource("only")
        val store = MemoryBindingStore(
            mapOf("not-a-subject:7" to "1", "42:oops" to "1", "42:7:extra" to "1", "43:9" to "1"),
        )
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(source)), store)

        synchronizer.retryPending()
        assertEquals(emptyMap(), store.entries())
        assertEquals(listOf(9), source.episodes)
    }

    @Test
    fun backoffRetryFiresAfterFirstDelayAndDrains() = runTest {
        val source = FakeSource("only", fail = true)
        val store = MemoryBindingStore()
        val synchronizer = TrackingEpisodeSynchronizer(
            DefaultTrackingRegistry(listOf(source)), store, scope = backgroundScope,
        )
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }

        source.fail = false
        advanceTimeBy(4.minutes)
        assertEquals(1, source.calls)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, source.calls)
        assertEquals(emptyMap(), store.entries())
    }

    @Test
    fun backoffStopsAfterThreeRetriesWhileStillFailing() = runTest {
        val source = FakeSource("only", fail = true)
        val store = MemoryBindingStore()
        val synchronizer = TrackingEpisodeSynchronizer(
            DefaultTrackingRegistry(listOf(source)), store, scope = backgroundScope,
        )
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }

        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(2, source.calls)
        advanceTimeBy(10.minutes)
        runCurrent()
        assertEquals(3, source.calls)
        advanceTimeBy(20.minutes)
        runCurrent()
        assertEquals(4, source.calls)

        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(4, source.calls)
        assertEquals(mapOf("42:7" to "1"), store.entries())

        // A failure after the cycle gave up starts a fresh cycle: both entries are retried after 5 minutes.
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 8) }
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(8, source.calls)
    }

    @Test
    fun successfulDrainEndsBackoffCycle() = runTest {
        val source = FakeSource("only", fail = true)
        val store = MemoryBindingStore()
        val synchronizer = TrackingEpisodeSynchronizer(
            DefaultTrackingRegistry(listOf(source)), store, scope = backgroundScope,
        )
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }

        source.fail = false
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(emptyMap(), store.entries())
        assertEquals(2, source.calls)

        // A failure after a cycle has ended starts a new cycle, so the next failure gets its own retry.
        source.fail = true
        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 8) }
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(4, source.calls)
    }

    private class MemoryBindingStore(initial: Map<String, String> = emptyMap()) : TrackingBindingStore {
        private val values = LinkedHashMap(initial)
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }

        override fun entries(): Map<String, String> = LinkedHashMap(values)
        override fun putAll(entries: Map<String, String>) {
            values.putAll(entries)
        }
    }

    private class FakeSource(id: String, var fail: Boolean = false) : TrackingSource {
        override val info = TrackingProviderInfo(TrackingProviderId(id), id, "https://example.org")
        override val connection: Flow<TrackingAccountState> = flowOf(TrackingAccountState.LoggedOut)
        override val capabilities = TrackingSourceCapabilities()
        override val statusOptions = emptyList<TrackingStatusOption>()
        override val scoreOptions = emptyList<TrackingScoreOption>()
        var calls = 0
        val episodes = mutableListOf<Int>()
        override fun observe(subjectId: Int): Flow<TrackingSnapshot?> = flowOf(null)
        override suspend fun bind(subjectId: Int, mediaId: TrackingMediaId): TrackingSnapshot = error("unused")
        override suspend fun edit(subjectId: Int, edit: TrackingEdit): TrackingSnapshot = error("unused")
        override suspend fun unlink(subjectId: Int) = Unit
        override suspend fun episodeWatched(subjectId: Int, episodeId: Int) {
            calls++
            episodes += episodeId
            if (fail) error("source unavailable")
        }
    }
}
