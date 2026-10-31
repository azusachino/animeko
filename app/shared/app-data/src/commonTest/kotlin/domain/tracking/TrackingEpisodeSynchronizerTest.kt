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
import kotlinx.coroutines.test.runTest
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

class TrackingEpisodeSynchronizerTest {
    @Test
    fun watchedEventContinuesAfterOneSourceFails() = runTest {
        val first = FakeSource("first", fail = true)
        val second = FakeSource("second")
        val synchronizer = TrackingEpisodeSynchronizer(DefaultTrackingRegistry(listOf(first, second)))

        assertFailsWith<IllegalStateException> { synchronizer.episodeWatched(42, 7) }
        assertEquals(1, second.calls)
    }

    private class FakeSource(id: String, private val fail: Boolean = false) : TrackingSource {
        override val info = TrackingProviderInfo(TrackingProviderId(id), id, "https://example.org")
        override val connection: Flow<TrackingAccountState> = flowOf(TrackingAccountState.LoggedOut)
        override val capabilities = TrackingSourceCapabilities()
        override val statusOptions = emptyList<TrackingStatusOption>()
        override val scoreOptions = emptyList<TrackingScoreOption>()
        var calls = 0
        override fun observe(subjectId: Int): Flow<TrackingSnapshot?> = flowOf(null)
        override suspend fun bind(subjectId: Int, mediaId: TrackingMediaId): TrackingSnapshot = error("unused")
        override suspend fun edit(subjectId: Int, edit: TrackingEdit): TrackingSnapshot = error("unused")
        override suspend fun unlink(subjectId: Int) = Unit
        override suspend fun episodeWatched(subjectId: Int, episodeId: Int) {
            calls++
            if (fail) error("source unavailable")
        }
    }
}
