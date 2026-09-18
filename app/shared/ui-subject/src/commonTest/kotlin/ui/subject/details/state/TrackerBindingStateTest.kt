/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.subject.details.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.him188.ani.app.data.persistent.database.dao.TrackerBindingDao
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 与 [me.him188.ani.app.domain.tracker.TrackerManagerTest] 里的 FakeTrackerService 同一形状,
 * 但 app-data 的那份是该模块 commonTest 的 private 类, ui-subject 是独立的 Gradle 模块访问不到,
 * 因此这里按同样约定另建一份, 而不是把它公开导出.
 */
private class FakeTrackerService(
    override val id: String = "anilist",
    var authorized: Boolean = true,
    private val searchResults: List<TrackerMediaSummary> = emptyList(),
) : TrackerService {
    override val displayName: String = "Fake"
    var searchCalls = 0
        private set

    override fun accountFlow(): Flow<TrackerAccount?> = flowOf(null)
    override suspend fun isAuthorized(): Boolean = authorized
    override suspend fun logout() {}
    override suspend fun search(keyword: String): List<TrackerMediaSummary> {
        searchCalls++
        return searchResults
    }

    override suspend fun fetchEntry(remoteMediaId: Int): TrackerEntry? = null
    override suspend fun syncProgress(binding: TrackerBinding, episodeNumber: Float): Result<Unit> =
        Result.success(Unit)

    override suspend fun syncCollectionStatus(
        binding: TrackerBinding,
        status: UnifiedCollectionType,
        score: Float?,
    ): Result<Unit> = Result.success(Unit)
}

class TrackerBindingStateTest {

    private val summary = TrackerMediaSummary(
        remoteMediaId = 42,
        title = "Frieren",
        coverUrl = null,
        totalEpisodes = 28,
    )

    private fun binding(
        subjectId: Int = 1,
        remoteMediaId: Int = 1,
        lastTrackedEpisode: Float = 5f,
        episodeOffset: Int = 0,
    ) = TrackerBindingEntity(
        subjectId = subjectId,
        trackerId = "anilist",
        remoteMediaId = remoteMediaId,
        remoteTitle = "Old",
        totalEpisodes = 12,
        lastTrackedEpisode = lastTrackedEpisode,
        episodeOffset = episodeOffset,
        updatedAtMillis = 0,
    )

    private fun createState(
        subjectId: Int = 1,
        tracker: FakeTrackerService = FakeTrackerService(searchResults = listOf(summary)),
        dao: TrackerBindingDao = createMemoryTrackerBindingDao(),
        scope: CoroutineScope,
    ): TrackerBindingState = TrackerBindingState(
        bindingFlow = dao.bindingsForSubjectFlow(subjectId)
            .map { bindings -> bindings.firstOrNull { it.trackerId == tracker.id } },
        trackerService = tracker,
        bindingDao = dao,
        subjectId = subjectId,
        initialQuery = "Frieren",
        backgroundScope = scope,
        trackerId = tracker.id,
    )

    @Test
    fun `未绑定时芯片显示未绑定状态`() = runTest {
        val state = createState(scope = backgroundScope)
        runCurrent()
        assertFalse(state.presentationFlow.value.isBound)
    }

    @Test
    fun `已绑定时芯片携带已看集数和总集数`() = runTest {
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(remoteMediaId = 42, lastTrackedEpisode = 12f))
        val state = createState(dao = dao, scope = backgroundScope)
        runCurrent()

        val presentation = state.presentationFlow.value
        assertTrue(presentation.isBound)
        assertEquals(12f, presentation.binding?.lastTrackedEpisode)
        assertEquals(12, presentation.binding?.totalEpisodes)
    }

    @Test
    fun `search 已授权时返回结果`() = runTest {
        val tracker = FakeTrackerService(searchResults = listOf(summary))
        val state = createState(tracker = tracker, scope = backgroundScope)

        state.search()
        runCurrent()

        assertEquals(listOf(summary), state.presentationFlow.value.results)
        assertEquals(1, tracker.searchCalls)
        assertEquals(true, state.presentationFlow.value.isAuthorized)
    }

    @Test
    fun `search 未授权时不发起远程搜索`() = runTest {
        val tracker = FakeTrackerService(authorized = false, searchResults = listOf(summary))
        val state = createState(tracker = tracker, scope = backgroundScope)

        state.search()
        runCurrent()

        assertEquals(0, tracker.searchCalls)
        assertEquals(false, state.presentationFlow.value.isAuthorized)
        assertTrue(state.presentationFlow.value.results.isEmpty())
    }

    @Test
    fun `选中结果后集数偏移默认为0`() = runTest {
        val state = createState(scope = backgroundScope)

        state.search()
        runCurrent()
        state.selectResult(summary)

        val presentation = state.presentationFlow.value
        assertEquals(summary, presentation.selected)
        assertEquals(0, presentation.episodeOffset)
    }

    @Test
    fun `重新绑定时选中结果预填已有的集数偏移`() = runTest {
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(episodeOffset = -12))
        val state = createState(dao = dao, scope = backgroundScope)
        runCurrent()

        state.selectResult(summary)

        assertEquals(-12, state.presentationFlow.value.episodeOffset)
    }

    @Test
    fun `confirm 写入正确的绑定字段`() = runTest {
        val dao = createMemoryTrackerBindingDao()
        val state = createState(subjectId = 7, dao = dao, scope = backgroundScope)

        state.selectResult(summary)
        state.updateEpisodeOffset(3)
        state.confirm()
        runCurrent()

        val saved = dao.getBinding(7, "anilist")
        assertEquals(42, saved?.remoteMediaId)
        assertEquals("Frieren", saved?.remoteTitle)
        assertEquals(28, saved?.totalEpisodes)
        assertEquals(3, saved?.episodeOffset)
        assertEquals(0f, saved?.lastTrackedEpisode)
        assertNull(state.presentationFlow.value.selected)
    }

    @Test
    fun `confirm 重新绑定时保留原有lastTrackedEpisode`() = runTest {
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding(lastTrackedEpisode = 5f))
        val state = createState(dao = dao, scope = backgroundScope)
        runCurrent()

        state.selectResult(summary)
        state.confirm()
        runCurrent()

        val saved = dao.getBinding(1, "anilist")
        assertEquals(5f, saved?.lastTrackedEpisode)
        assertEquals(42, saved?.remoteMediaId)
    }

    @Test
    fun `unbind 清除绑定`() = runTest {
        val dao = createMemoryTrackerBindingDao()
        dao.upsertBinding(binding())
        val state = createState(dao = dao, scope = backgroundScope)
        runCurrent()
        assertTrue(state.presentationFlow.value.isBound)

        state.unbind()
        runCurrent()

        assertFalse(state.presentationFlow.value.isBound)
        assertNull(dao.getBinding(1, "anilist"))
    }
}
