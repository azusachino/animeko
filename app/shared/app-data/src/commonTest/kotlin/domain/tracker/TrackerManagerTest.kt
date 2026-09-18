/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.domain.tracker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.database.dao.TrackerBindingEntity
import me.him188.ani.app.data.persistent.database.dao.createMemoryTrackerBindingDao
import me.him188.ani.app.tracker.api.TrackerAccount
import me.him188.ani.app.tracker.api.TrackerBinding
import me.him188.ani.app.tracker.api.TrackerEntry
import me.him188.ani.app.tracker.api.TrackerMediaSummary
import me.him188.ani.app.tracker.api.TrackerService
import me.him188.ani.datasources.api.topic.UnifiedCollectionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeTrackerService(
    override val id: String = "anilist",
    var authorized: Boolean = true,
    private val syncProgressResult: (Float) -> Result<Unit> = { Result.success(Unit) },
) : TrackerService {
    override val displayName: String = "Fake"
    val syncProgressCalls = mutableListOf<Pair<TrackerBinding, Float>>()
    val syncCollectionStatusCalls = mutableListOf<Triple<TrackerBinding, UnifiedCollectionType, Float?>>()

    override fun accountFlow(): Flow<TrackerAccount?> = flowOf(null)
    override suspend fun isAuthorized(): Boolean = authorized
    override suspend fun logout() {}
    override suspend fun search(keyword: String): List<TrackerMediaSummary> = emptyList()
    override suspend fun fetchEntry(remoteMediaId: Int): TrackerEntry? = null

    override suspend fun syncProgress(binding: TrackerBinding, episodeNumber: Float): Result<Unit> {
        syncProgressCalls += binding to episodeNumber
        return syncProgressResult(episodeNumber)
    }

    override suspend fun syncCollectionStatus(
        binding: TrackerBinding,
        status: UnifiedCollectionType,
        score: Float?,
    ): Result<Unit> {
        syncCollectionStatusCalls += Triple(binding, status, score)
        return Result.success(Unit)
    }
}

class TrackerManagerTest {

    private fun binding(
        subjectId: Int = 1,
        trackerId: String = "anilist",
        episodeOffset: Int = 0,
        lastTrackedEpisode: Float = 0f,
    ) = TrackerBindingEntity(
        subjectId = subjectId,
        trackerId = trackerId,
        remoteMediaId = 42,
        remoteTitle = "Frieren",
        totalEpisodes = 28,
        lastTrackedEpisode = lastTrackedEpisode,
        episodeOffset = episodeOffset,
        status = null,
        score = null,
        updatedAtMillis = 0,
    )

    @Test
    fun `TRK-14 有绑定时syncProgress只调用一次且传入max(episode+offset,lastTracked)`() = runTest {
        val tracker = FakeTrackerService()
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(episodeOffset = 0, lastTrackedEpisode = 0f))
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onEpisodeMarkedWatched(subjectId = 1, episodeSort = 12f)

        assertEquals(1, tracker.syncProgressCalls.size)
        assertEquals(12f, tracker.syncProgressCalls.single().second)
        assertEquals(12f, dao.getBinding(1, "anilist")?.lastTrackedEpisode)
    }

    @Test
    fun `TRK-14 episodeOffset应用到集数`() = runTest {
        val tracker = FakeTrackerService()
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(episodeOffset = -12, lastTrackedEpisode = 0f))
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onEpisodeMarkedWatched(subjectId = 1, episodeSort = 13f)

        assertEquals(1f, tracker.syncProgressCalls.single().second)
    }

    @Test
    fun `TRK-14 目标集数不大于已同步集数时跳过`() = runTest {
        val tracker = FakeTrackerService()
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(lastTrackedEpisode = 12f))
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onEpisodeMarkedWatched(subjectId = 1, episodeSort = 12f) // 相等, 不应重复同步

        assertTrue(tracker.syncProgressCalls.isEmpty())
    }

    @Test
    fun `TRK-16 无绑定时不调用任何tracker`() = runTest {
        val tracker = FakeTrackerService()
        val dao = createMemoryTrackerBindingDao()
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onEpisodeMarkedWatched(subjectId = 1, episodeSort = 12f)

        assertTrue(tracker.syncProgressCalls.isEmpty())
    }

    @Test
    fun `TRK-16 tracker未授权时跳过`() = runTest {
        val tracker = FakeTrackerService(authorized = false)
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding())
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onEpisodeMarkedWatched(subjectId = 1, episodeSort = 12f)

        assertTrue(tracker.syncProgressCalls.isEmpty())
    }

    @Test
    fun `TRK-17 同步失败不抛出异常且不更新lastTrackedEpisode`() = runTest {
        val tracker = FakeTrackerService(syncProgressResult = { Result.failure(RuntimeException("network down")) })
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(lastTrackedEpisode = 0f))
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onEpisodeMarkedWatched(subjectId = 1, episodeSort = 12f) // 不应抛出

        assertEquals(1, tracker.syncProgressCalls.size)
        assertEquals(0f, dao.getBinding(1, "anilist")?.lastTrackedEpisode)
    }

    @Test
    fun `onSubjectCollectionChanged转发状态和分数给tracker`() = runTest {
        val tracker = FakeTrackerService()
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding())
        val manager = TrackerManager(listOf(tracker), dao)

        manager.onSubjectCollectionChanged(subjectId = 1, status = UnifiedCollectionType.DONE, score = 8.5f)

        val (_, status, score) = tracker.syncCollectionStatusCalls.single()
        assertEquals(UnifiedCollectionType.DONE, status)
        assertEquals(8.5f, score)
    }
}
